// Host KAT: a DigiDollar-shaped output is credited only at or above the network's activation floor.
//
// THE RULE. The wallet credits a DigiDollar (DD) token output -- and lists it among the DD coins a
// transfer may spend -- only when its transaction is CONFIRMED at or above
// BRNetworkDigiDollarActivationHeight(): 23,627,520 on mainnet, 600 on testnet, the reference
// client's EarliestActivationFloor keyed on the coin's own height. One block below the floor the
// output is ordinary history: the transaction is kept, the address is marked used, nothing is
// credited in cents. An unconfirmed DD output credits nothing (unchanged behaviour, now stated by
// the gate itself). A re-stamp to a different confirmed height re-evaluates the gate.
//
// ARMS. Shipped: no -D. Comparison: -DDD_ACTIVATION_FLOOR_UNFIXED credits a DD-shaped output at
// any confirmed height; every case marked RED below must fail in that arm.
//
// Exit code 0 = all checks passed, 1 = check failed / ASan fault.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"
#include "BRNetwork.h"

static int g_fail = 0, g_caseFail = 0;
static void check(int c, const char *d) { printf(c ? "   PASS: %s\n" : "   FAIL: %s\n", d); if (! c) { g_fail++; g_caseFail++; } }
static void begin(const char *name) { printf("--- %s ---\n", name); g_caseFail = 0; }
static void end(const char *name) { printf("RESULT %s %s\n", name, g_caseFail ? "fail" : "pass"); }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

// BRWalletRegisterTransaction asserts BRTransactionIsSigned(), which only checks that each input's
// signature/witness pointers are non-NULL. These synthetic txs aren't really signed.
static const uint8_t kPlaceholder[1] = {0};

static const uint16_t CENTS = 5000;   // $50.00

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A DD transfer paying `spk` (zero-value token output) with `cents`, plus the "DD" OP_RETURN,
// stamped at `height` (TX_UNCONFIRMED leaves it unconfirmed). Same fixture as digidollar_wallet_kat.
static BRTransaction *ddTx(const uint8_t *spk, size_t spkLen, uint16_t centsLE, uint8_t seed, uint32_t height) {
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev; memset(prev.u8, seed, 32);
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);              // vout0: DD token (ours), zero-value
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,
                      (uint8_t)(centsLE & 0xff), (uint8_t)(centsLE >> 8)};
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));         // vout1: OP_RETURN, zero-value
    if (height != TX_UNCONFIRMED) { tx->blockHeight = height; tx->timestamp = 1784980000u; }
    finalizeTxHash(tx);
    return tx;
}

// A wallet with the BIP84 and BIP86 keys installed, created under the CURRENT network setting.
static BRWallet *mkWallet(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (w) BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));
    return w;
}

static size_t taprootSpk(BRWallet *w, uint8_t *spk, size_t cap) {
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    return BRAddressScriptPubKey(spk, cap, ta.s);
}

// Registers one DD coin confirmed at `height` in a fresh wallet and reports what it credits.
// Returns the wallet (caller frees) so a case can look further.
static BRWallet *walletWithDDAt(uint32_t height, uint8_t seed, BRTransaction **txOut) {
    BRWallet *w = mkWallet();
    uint8_t spk[64]; size_t spkLen = taprootSpk(w, spk, sizeof(spk));
    BRTransaction *tx = ddTx(spk, spkLen, CENTS, seed, height);
    BRWalletRegisterTransaction(w, tx);
    if (txOut) *txOut = tx;
    return w;
}

// Adds a confirmed 1 DGB P2WPKH coin so a DD transfer has a fee input.
static void addFeeCoin(BRWallet *w, uint8_t seed, uint32_t height) {
    BRAddress da = BRWalletReceiveAddress(w, 1);
    uint8_t dspk[64]; size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    UInt256 prev; memset(prev.u8, seed, 32);
    BRTransaction *tx = BRTransactionNew();
    tx->version = 1;
    BRTransactionAddInput(tx, prev, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 100000000, dspk, dspkLen);
    tx->blockHeight = height; tx->timestamp = 1784980000u;
    finalizeTxHash(tx);
    BRWalletRegisterTransaction(w, tx);
}

// ---- cases -------------------------------------------------------------------------------------

// GUARD: the accessor answers per network with the pinned floors.
static void case_accessor(void) {
    begin("accessor");
    BRSetNetwork(0);
    check(BRNetworkDigiDollarActivationHeight() == 23627520u, "mainnet floor is 23,627,520");
    check(BRNetworkDigiDollarActivationHeight() == DD_ACTIVATION_HEIGHT_MAINNET, "mainnet accessor matches its constant");
    BRSetNetwork(1);
    check(BRNetworkDigiDollarActivationHeight() == 600u, "testnet floor is 600");
    check(BRNetworkDigiDollarActivationHeight() == DD_ACTIVATION_HEIGHT_TESTNET, "testnet accessor matches its constant");
    BRSetNetwork(0);
    end("accessor");
}

// RED: mainnet -- one block below credits nothing and stays ordinary history; at and above credit.
static void case_mainnet_floor(void) {
    begin("mainnet_floor");
    const uint32_t F = DD_ACTIVATION_HEIGHT_MAINNET;
    BRTransaction *tx;

    BRWallet *below = walletWithDDAt(F - 1, 0x11, &tx);
    check(BRWalletDigiDollarBalance(below) == 0, "confirmed at floor-1: DD balance 0");
    check(BRWalletDigiDollarUTXOs(below, NULL, 0) == 0, "confirmed at floor-1: no DD coin listed");
    check(BRWalletTransactions(below, NULL, 0) == 1, "confirmed at floor-1: the transaction is kept as history");
    check(BRWalletAddressIsUsed(below, tx->outputs[0].address) == 1, "confirmed at floor-1: the paid address is marked used");
    check(BRWalletBalance(below) == 0, "confirmed at floor-1: DGB balance unchanged (zero-value output)");
    BRWalletFree(below);

    BRWallet *at = walletWithDDAt(F, 0x12, NULL);
    check(BRWalletDigiDollarBalance(at) == CENTS, "confirmed at the floor: DD balance credited");
    check(BRWalletDigiDollarUTXOs(at, NULL, 0) == 1, "confirmed at the floor: one DD coin listed");
    check(BRWalletBalance(at) == 0, "confirmed at the floor: DGB balance unchanged");
    BRWalletFree(at);

    BRWallet *above = walletWithDDAt(F + 1, 0x13, NULL);
    check(BRWalletDigiDollarBalance(above) == CENTS, "confirmed at floor+1: DD balance credited");
    BRWalletFree(above);
    end("mainnet_floor");
}

// RED: testnet -- the floor is 600.
static void case_testnet_floor(void) {
    begin("testnet_floor");
    const uint32_t F = DD_ACTIVATION_HEIGHT_TESTNET;
    BRSetNetwork(1);   // wallets below are created under testnet, so their addresses are testnet
    BRWallet *below = walletWithDDAt(F - 1, 0x21, NULL);
    check(BRWalletTransactions(below, NULL, 0) == 1, "testnet: the transaction registers");
    check(BRWalletDigiDollarBalance(below) == 0, "testnet, confirmed at 599: DD balance 0");
    BRWalletFree(below);
    BRWallet *at = walletWithDDAt(F, 0x22, NULL);
    check(BRWalletDigiDollarBalance(at) == CENTS, "testnet, confirmed at 600: DD balance credited");
    BRWalletFree(at);
    BRSetNetwork(0);
    end("testnet_floor");
}

// RED: unconfirmed credits nothing; a confirmation below the floor credits nothing; at the floor credits.
static void case_unconfirmed_then_confirmed(void) {
    begin("unconfirmed_then_confirmed");
    const uint32_t F = DD_ACTIVATION_HEIGHT_MAINNET;
    BRTransaction *tx;
    BRWallet *w = walletWithDDAt(TX_UNCONFIRMED, 0x31, &tx);
    UInt256 hash = tx->txHash;
    check(BRWalletTransactions(w, NULL, 0) == 1, "unconfirmed DD receive is in the wallet");
    check(BRWalletDigiDollarBalance(w) == 0, "unconfirmed: DD balance 0 (guard, pinned elsewhere too)");
    BRWalletUpdateTransactions(w, &hash, 1, F - 1, 1784980000u);
    check(BRWalletTransactionForHash(w, hash)->blockHeight == F - 1, "now confirmed at floor-1");
    check(BRWalletDigiDollarBalance(w) == 0, "confirmed at floor-1: DD balance 0");
    BRWalletUpdateTransactions(w, &hash, 1, F, 1784980100u);
    check(BRWalletTransactionForHash(w, hash)->blockHeight == F, "now confirmed at the floor");
    check(BRWalletDigiDollarBalance(w) == CENTS, "confirmed at the floor: DD balance credited");
    BRWalletFree(w);
    end("unconfirmed_then_confirmed");
}

// RED: a reorg un-confirms, a re-stamp below the floor credits nothing, a re-stamp at the floor credits.
static void case_reorg_restamp(void) {
    begin("reorg_restamp");
    const uint32_t F = DD_ACTIVATION_HEIGHT_MAINNET;
    BRTransaction *tx;
    BRWallet *w = walletWithDDAt(F, 0x41, &tx);
    UInt256 hash = tx->txHash;
    check(BRWalletDigiDollarBalance(w) == CENTS, "credited at the floor");
    BRWalletSetTxUnconfirmedAfter(w, F - 1);
    check(BRWalletTransactionForHash(w, hash)->blockHeight == TX_UNCONFIRMED, "reorg: unconfirmed");
    check(BRWalletDigiDollarBalance(w) == 0, "reorg: DD balance 0");
    BRWalletUpdateTransactions(w, &hash, 1, F - 1, 1784980000u);
    check(BRWalletDigiDollarBalance(w) == 0, "re-stamped at floor-1: DD balance 0");
    BRWalletUpdateTransactions(w, &hash, 1, F, 1784980100u);
    check(BRWalletDigiDollarBalance(w) == CENTS, "re-stamped at the floor: DD balance credited");
    BRWalletFree(w);
    end("reorg_restamp");
}

// RED: a DD coin below the floor cannot fund a transfer; the same coin at the floor can.
static void case_spend_below_floor_null(void) {
    begin("spend_below_floor_null");
    const uint32_t F = DD_ACTIVATION_HEIGHT_MAINNET;
    uint8_t rk[32]; memset(rk, 0xAB, 32);
    BRTransaction *t;

    BRWallet *below = walletWithDDAt(F - 1, 0x51, NULL);
    addFeeCoin(below, 0x52, F - 1);
    check(BRWalletBalance(below) == 100000000, "fee coin credited");
    t = BRWalletCreateDigiDollarTransfer(below, rk, 1000);
    check(t == NULL, "only DD coin below the floor: no transfer can be built");
    if (t) BRTransactionFree(t);
    BRWalletFree(below);

    BRWallet *at = walletWithDDAt(F, 0x53, NULL);
    addFeeCoin(at, 0x54, F);
    t = BRWalletCreateDigiDollarTransfer(at, rk, 1000);
    check(t != NULL, "DD coin at the floor: the transfer builds");
    if (t) BRTransactionFree(t);
    BRWalletFree(at);
    end("spend_below_floor_null");
}

int main(void) {
    BRSetNetwork(0);
    case_accessor();
    case_mainnet_floor();
    case_testnet_floor();
    case_unconfirmed_then_confirmed();
    case_reorg_restamp();
    case_spend_below_floor_null();
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
