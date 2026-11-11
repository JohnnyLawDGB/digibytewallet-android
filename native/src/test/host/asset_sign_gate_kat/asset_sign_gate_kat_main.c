/* asset_sign_gate_kat -- ASSET-SIGN-GATE.
 *
 * THE INVARIANT
 *   The DigiAsset sign path (buildAndSignAssetTransferTx) re-checks natively, before and after signing,
 *   that it spends only wallet or asset coins at the amounts the wallet holds, and pays only the approved
 *   outputs and fee. The check is asset_sign_check (bridge/asset_sign_gate.h), compiled here against the
 *   live core: every input is an output of a transaction the wallet holds, paying a wallet address, at
 *   that output's amount and script, and is a coin the wallet's selection would spend or a held DigiAsset
 *   coin; every output pays the approved recipient, the wallet, or is a zero-value data output; the fee
 *   is exactly the approved fee.
 *
 * ARMS
 *   default                        the shipped check.
 *   -DASSET_SIGN_GATE_UNFIXED=1    the behaviour before it: nothing checked (every verdict OK).
 *
 * CASES (each prints PASS/FAIL lines tagged with its letter)
 *   [A] GUARD, both arms. The approved send -- a held asset coin plus a plain fee coin, paying the
 *       recipient's marker, the data output, the asset change and DGB change -- passes before signing,
 *       is signed, and passes again after.
 *   [B] RED-THEN-GREEN. The plain fee coin listed below what it holds: refused (AMOUNT_MISMATCH).
 *   [C] RED-THEN-GREEN. A fee coin that is an unconfirmed coin someone else sent, which the wallet's
 *       selection never spends: refused (COIN_NOT_WALLETS).
 *   [D] RED-THEN-GREEN. An input whose transaction the wallet does not hold: refused (COIN_UNKNOWN).
 *   [E] RED-THEN-GREEN. The asset coin after another send of the wallet spent it: refused.
 *   [F] RED-THEN-GREEN. The DGB change paid to a script that is neither the wallet's nor the approved
 *       recipient's: refused (NOT_APPROVED).
 *   [G] RED-THEN-GREEN. One sat more or less than the approved fee: refused (FEE_CHANGED).
 *   [H] RED-THEN-GREEN. A signed transaction whose change was redirected after signing: the second check
 *       refuses it (NOT_APPROVED).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "asset_sign_gate.h"

static int g_fail = 0;
static void check(int c, const char *what)
{
    printf("%s: %s\n", c ? "PASS" : "FAIL", what);
    if (!c) g_fail++;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = { 0 };
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84;
static uint8_t g_own[64];   /* the wallet's own native-segwit script */
static size_t g_ownLen;
static uint8_t g_fill = 0x30;

static const uint8_t kRecip[22] = { 0x00, 0x14, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a,
                                    0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a };
static const uint8_t kOther[22] = { 0x00, 0x14, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b,
                                    0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b };
static const uint8_t kDACarrier[] = { 0x6a, 0x06, 0x44, 0x41, 0x01, 0x15, 0x00, 0x0a };   /* names output 0 */
static const uint8_t kDAData[]    = { 0x6a, 0x06, 0x44, 0x41, 0x03, 0x15, 0x00, 0x05 };   /* the send's own */

#define ASSET_SATS   700ULL
#define PLAIN_SATS   1000000000ULL   /* 10 DGB */
#define FEE_SATS     30000ULL

static void finalize(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *b = malloc(n);
    n = BRTransactionSerialize(tx, b, n);
    BRTransaction *t = BRTransactionParse(b, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(b);
}

/* A transaction from a coin that is not the wallet's, to be given outputs and registered. */
static BRTransaction *foreignFunded(void)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;
    memset(prev.u8, g_fill++, 32);
    tx->version = 1;
    BRTransactionAddInput(tx, prev, 0, 0, kOther, sizeof(kOther), kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    return tx;
}

static BRTransaction *registerAt(BRWallet *w, BRTransaction *tx, uint32_t height)
{
    tx->blockHeight = height;
    tx->timestamp = height == TX_UNCONFIRMED ? 0 : 1700000000;
    finalize(tx);
    if (!BRWalletRegisterTransaction(w, tx)) { printf("FAIL setup: registration refused\n"); g_fail++; return NULL; }
    return tx;
}

typedef struct {
    BRWallet *w;
    BRTransaction *asset, *plain;   /* the wallet's: freed with it */
} Fixture;

/* A wallet holding one DigiAsset coin (700 sat, named by its carrier) and one plain 10 DGB coin. */
static int fixture(Fixture *f)
{
    memset(f, 0, sizeof(*f));
    f->w = BRWalletNew(NULL, 0, g_mpk84);
    BRTransaction *a = foreignFunded();
    BRTransactionAddOutput(a, ASSET_SATS, g_own, g_ownLen);
    BRTransactionAddOutput(a, 0, kDACarrier, sizeof(kDACarrier));
    f->asset = registerAt(f->w, a, 23700000);
    BRTransaction *p = foreignFunded();
    BRTransactionAddOutput(p, PLAIN_SATS, g_own, g_ownLen);
    f->plain = registerAt(f->w, p, 23700001);
    return f->asset && f->plain && BRWalletOutpointAssetState(f->w, f->asset->txHash, 0) == 1 &&
           BRWalletAssetUTXOs(f->w, NULL, 0) == 1 && BRWalletSelectableUTXOs(f->w, NULL, 0) == 1;
}

static void addInputOf(BRTransaction *tx, const BRTransaction *parent, uint32_t n, uint64_t listedAmount)
{
    const BRTxOutput *o = &parent->outputs[n];
    BRTransactionAddInput(tx, parent->txHash, n, listedAmount, o->script, o->scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
}

/* The send buildAndSignAssetTransferTx assembles: the asset coin first, then the fee coin; the recipient's
 * marker, the data output, the asset change and the DGB change (to changeScript). */
static BRTransaction *assetSend(const BRTransaction *asset, const BRTransaction *fee, uint64_t feeListed,
                                const uint8_t *changeScript, size_t changeLen)
{
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, asset, 0, asset->outputs[0].amount);
    addInputOf(tx, fee, 0, feeListed);
    BRTransactionAddOutput(tx, ASSET_SATS, kRecip, sizeof(kRecip));
    BRTransactionAddOutput(tx, 0, kDAData, sizeof(kDAData));
    BRTransactionAddOutput(tx, ASSET_SATS, g_own, g_ownLen);
    BRTransactionAddOutput(tx, fee->outputs[0].amount - ASSET_SATS - FEE_SATS, changeScript, changeLen);
    return tx;
}

static AssetSignVerdict gate(BRWallet *w, const BRTransaction *tx, uint64_t fee)
{
    return asset_sign_check(w, tx, kRecip, sizeof(kRecip), fee);
}

static void caseA(void)
{
    printf("[A] the approved send\n");
    Fixture f;
    check(fixture(&f), "A setup: one held asset coin, one selectable plain coin");
    BRTransaction *tx = assetSend(f.asset, f.plain, PLAIN_SATS, g_own, g_ownLen);
    check(gate(f.w, tx, FEE_SATS) == ASSET_SIGN_OK, "A guard: the approved send passes before signing");
    check(BRWalletSignTransaction(f.w, tx, 0, g_seed, sizeof(g_seed)) && BRTransactionIsSigned(tx), "A guard: it is signed");
    check(gate(f.w, tx, FEE_SATS) == ASSET_SIGN_OK, "A guard: the signed send passes again");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseB(void)
{
    printf("[B] a fee coin listed below what it holds\n");
    Fixture f;
    check(fixture(&f), "B setup");
    BRTransaction *tx = assetSend(f.asset, f.plain, PLAIN_SATS - 500000000ULL, g_own, g_ownLen);
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_AMOUNT_MISMATCH) { printf("FAIL: B: an input listed below its held amount is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "B: an input listed below its held amount is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseC(void)
{
    printf("[C] a fee coin someone else sent, unconfirmed\n");
    Fixture f;
    check(fixture(&f), "C setup");
    BRTransaction *u = foreignFunded();
    BRTransactionAddOutput(u, PLAIN_SATS, g_own, g_ownLen);
    u = registerAt(f.w, u, TX_UNCONFIRMED);
    check(u != NULL, "C setup: a foreign unconfirmed receive is registered");
    if (!u) { BRWalletFree(f.w); return; }
    size_t raw = BRWalletUTXOs(f.w, NULL, 0), sel = BRWalletSelectableUTXOs(f.w, NULL, 0);
    check(raw == 2 && sel == 1, "C setup: it is in the wallet's coins but not in what selection spends");
    BRTransaction *tx = assetSend(f.asset, u, PLAIN_SATS, g_own, g_ownLen);
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_COIN_NOT_WALLETS) { printf("FAIL: C: a foreign unconfirmed fee coin is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "C: a foreign unconfirmed fee coin is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseD(void)
{
    printf("[D] an input the wallet does not hold\n");
    Fixture f;
    check(fixture(&f), "D setup");
    BRTransaction *stranger = foreignFunded();
    BRTransactionAddOutput(stranger, PLAIN_SATS, g_own, g_ownLen);
    stranger->blockHeight = 23700002;
    finalize(stranger);   /* never registered */
    BRTransaction *tx = assetSend(f.asset, stranger, PLAIN_SATS, g_own, g_ownLen);
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_COIN_UNKNOWN) { printf("FAIL: D: an input the wallet does not hold is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "D: an input the wallet does not hold is refused");
    BRTransactionFree(tx);
    BRTransactionFree(stranger);
    BRWalletFree(f.w);
}

static void caseE(void)
{
    printf("[E] the asset coin, spent by another send\n");
    Fixture f;
    check(fixture(&f), "E setup");
    BRTransaction *first = assetSend(f.asset, f.plain, PLAIN_SATS, g_own, g_ownLen);
    int signedFirst = BRWalletSignTransaction(f.w, first, 0, g_seed, sizeof(g_seed));
    int reg = signedFirst && BRWalletRegisterTransaction(f.w, first);
    check(reg, "E setup: a first send of the same coins is signed and registered");
    if (!reg) BRTransactionFree(first);
    BRTransaction *again = assetSend(f.asset, f.plain, PLAIN_SATS, g_own, g_ownLen);
    AssetSignVerdict v = gate(f.w, again, FEE_SATS);
    if (v == ASSET_SIGN_OK) { printf("FAIL: E: a coin another send has spent is signed again\n"); g_fail++; }
    else check(1, "E: a coin another send has spent is refused");
    BRTransactionFree(again);
    BRWalletFree(f.w);
}

static void caseF(void)
{
    printf("[F] change to a script nobody approved\n");
    Fixture f;
    check(fixture(&f), "F setup");
    BRTransaction *tx = assetSend(f.asset, f.plain, PLAIN_SATS, kOther, sizeof(kOther));
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_NOT_APPROVED) { printf("FAIL: F: change to an unapproved script is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "F: change to an unapproved script is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseG(void)
{
    printf("[G] not the approved fee\n");
    Fixture f;
    check(fixture(&f), "G setup");
    BRTransaction *tx = assetSend(f.asset, f.plain, PLAIN_SATS, g_own, g_ownLen);
    AssetSignVerdict lo = gate(f.w, tx, FEE_SATS - 1), hi = gate(f.w, tx, FEE_SATS + 1);
    if (lo != ASSET_SIGN_FEE_CHANGED || hi != ASSET_SIGN_FEE_CHANGED) {
        printf("FAIL: G: a send paying another fee than approved is signed (verdicts %d, %d)\n", (int)lo, (int)hi); g_fail++;
    } else check(1, "G: a send paying one sat more or less than approved is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseH(void)
{
    printf("[H] a signed send altered after signing\n");
    Fixture f;
    check(fixture(&f), "H setup");
    BRTransaction *tx = assetSend(f.asset, f.plain, PLAIN_SATS, g_own, g_ownLen);
    check(BRWalletSignTransaction(f.w, tx, 0, g_seed, sizeof(g_seed)), "H setup: signed");
    BRTxOutputSetScript(&tx->outputs[3], kOther, sizeof(kOther));
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_NOT_APPROVED) { printf("FAIL: H: a signed send with redirected change is handed back (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "H: a signed send with redirected change is refused by the second check");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("ARM: %s\n", ASSET_SIGN_GATE_UNFIXED ? "comparison (ASSET_SIGN_GATE_UNFIXED=1)" : "shipped");
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    g_ownLen = BRAddressScriptPubKey(g_own, sizeof(g_own), a.s);
    BRWalletFree(w);
    if (g_ownLen != 22) { printf("FAIL setup: own script\n"); return 2; }

    caseA(); caseB(); caseC(); caseD(); caseE(); caseF(); caseG(); caseH();
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: asset_sign_gate_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
