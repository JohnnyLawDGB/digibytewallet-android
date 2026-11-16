/* asset_sign_gate_core_kat -- the core's BRAssetSignGate.h and BROneFamilyGate.h, called directly.
 *
 * run.sh holds the core headers to the bridge suites' cases (asset_sign_gate_kat, one_family_kat), unchanged.
 * Those reach each guard the way the bridge's sign path builds a transaction; the guards below are ones no
 * such transaction reaches (BRWalletBuildAssetTransfer refuses them first, checks 4-15), so they are held
 * here on hand-built transactions and wallet state, each by a case whose verdict changes if that guard goes.
 * The header is the copy the bridge is to include in place of its own, so each of its guards answers for
 * itself.
 *
 * THE PROBE
 *   BRWalletContainsAddress is called through katContainsAddress in this unit: it forwards to the wallet and
 *   counts the calls made with an empty address. The wallet's answer for an empty address is 0 (no address
 *   the wallet derives or watches is empty), so a gate that asks it gets the same verdict; the gate's own rule
 *   is that an output with no address pays no address of the wallet's, without asking. Case G holds it.
 *
 * ARMS
 *   default                        the shipped headers: every check passes.
 *   -DASSET_SIGN_GATE_UNFIXED=1    nothing checked by BRAssetSignCheck: the named checks of B-K fail.
 *   -DONE_FAMILY_SIGNER_UNFIXED    every input accepted by BRWalletTxInputsOneFamilyOK: the named checks of M fail.
 *
 * CASES (each prints PASS/FAIL lines tagged with its letter)
 *   [A] GUARD. The approved send (a held asset coin and a plain fee coin; the recipient's marker, the data
 *       output, the asset change and the DGB change) passes, and the wallet is never asked about an empty
 *       address.
 *   [B] An input naming an output past the last of its transaction's: COIN_UNKNOWN.
 *   [C] An input with no script, listed at the held output's script length: AMOUNT_MISMATCH.
 *   [D] Outputs whose sum passes 2^64, wrapping to the inputs less the plan's fee: FEE_CHANGED.
 *   [E] Inputs whose sum passes 2^64 (nine wallet coins of MAX_MONEY), wrapping to the outputs plus the
 *       plan's fee: FEE_CHANGED.
 *   [F] Outputs one sat above the inputs, the plan's fee and the approval 2^64 - 1 (the inputs less the
 *       outputs, wrapped): FEE_CHANGED.
 *   [G] An input spending the wallet's own data output, which pays no address: COIN_NOT_WALLETS, and the
 *       wallet is not asked about the empty address.
 *   [H] A held DigiAsset coin another send has spent (BRWalletAssetUTXOs still lists it; its state is 0),
 *       with an unspent fee coin: COIN_NOT_WALLETS.
 *   [I] An approved amount of 0, the recipient paid 0: RECIPIENT_CHANGED.
 *   [J] A transaction with no inputs: COIN_UNKNOWN.
 *   [K] No wallet, or no transaction: COIN_UNKNOWN.
 *   [L] ONE-FAMILY, no wallet or no transaction: 0 (a transaction with no inputs included); a wallet and a
 *       transaction with no inputs: 1 (GUARD, every arm).
 *   [M] ONE-FAMILY, two inputs: a dual-family output first or last is refused; two plain coins pass (GUARD).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"
#include "BRNetwork.h"

static int g_emptyAsked = 0;
static int katContainsAddress(BRWallet *wallet, const char *addr);
#define BRWalletContainsAddress katContainsAddress
#include "BRAssetSignGate.h"
#undef BRWalletContainsAddress
#include "BROneFamilyGate.h"

static int katContainsAddress(BRWallet *wallet, const char *addr)
{
    if (addr && addr[0] == '\0') g_emptyAsked++;
    return BRWalletContainsAddress(wallet, addr);
}

static int g_fail = 0;
static void check(int c, const char *what)
{
    printf("%s: %s\n", c ? "PASS" : "FAIL", what);
    if (! c) g_fail++;
}

/* A refusal check: PASS when v is want, else the FAIL line `what` with the verdict. */
static void refuses(BRAssetSignVerdict v, BRAssetSignVerdict want, const char *what)
{
    if (v == want) { printf("PASS: %s is refused\n", what); return; }
    printf("FAIL: %s is not refused as it must be (verdict %d, want %d)\n", what, (int)v, (int)want);
    g_fail++;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = { 0 };
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84;
static uint8_t g_own[64];   /* the wallet's own native-segwit script */
static size_t g_ownLen;
static uint8_t g_fill = 0x40;
static uint32_t g_height = 23700000;

static const uint8_t kRecip[22] = { 0x00, 0x14, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a,
                                    0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a };
static const uint8_t kOther[22] = { 0x00, 0x14, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b,
                                    0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b, 0x6b };
static const uint8_t kDACarrier[] = { 0x6a, 0x06, 0x44, 0x41, 0x01, 0x15, 0x00, 0x0a };   /* names output 0 */
static const uint8_t kDAData[]    = { 0x6a, 0x06, 0x44, 0x41, 0x03, 0x15, 0x00, 0x05 };   /* the send's own */
static const uint8_t kDDOpReturn[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x88, 0x13 };   /* "DD" 2 [5000] */

#define ASSET_SATS   700ULL
#define PLAIN_SATS   1000000000ULL   /* 10 DGB */
#define FEE_SATS     30000ULL
#define CHANGE_SATS  (PLAIN_SATS - ASSET_SATS - FEE_SATS)
#define DD_VERSION   (((uint32_t)DD_TYPE_TRANSFER << 24) | DD_VERSION_MARKER)

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
static BRTransaction *foreignFunded(uint32_t version)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;
    memset(prev.u8, g_fill++, 32);
    tx->version = version;
    BRTransactionAddInput(tx, prev, 0, 0, kOther, sizeof(kOther), kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    return tx;
}

/* Registered confirmed, each at its own height; NULL (and freed) when the wallet refuses it. */
static BRTransaction *registerConfirmed(BRWallet *w, BRTransaction *tx)
{
    tx->blockHeight = g_height++;
    tx->timestamp = 1700000000;
    finalize(tx);
    if (! BRWalletRegisterTransaction(w, tx)) { BRTransactionFree(tx); return NULL; }
    return tx;
}

static BRTransaction *plainCoin(BRWallet *w, uint64_t sats)
{
    BRTransaction *p = foreignFunded(1);
    BRTransactionAddOutput(p, sats, g_own, g_ownLen);
    return registerConfirmed(w, p);
}

typedef struct {
    BRWallet *w;
    BRTransaction *asset, *plain;   /* the wallet's: freed with it */
} Fixture;

/* A wallet holding one DigiAsset coin (700 sat, named by its carrier, output 1) and one plain 10 DGB coin. */
static int fixture(Fixture *f)
{
    memset(f, 0, sizeof(*f));
    f->w = BRWalletNew(NULL, 0, g_mpk84);
    BRTransaction *a = foreignFunded(1);
    BRTransactionAddOutput(a, ASSET_SATS, g_own, g_ownLen);
    BRTransactionAddOutput(a, 0, kDACarrier, sizeof(kDACarrier));
    f->asset = registerConfirmed(f->w, a);
    f->plain = plainCoin(f->w, PLAIN_SATS);
    return f->asset && f->plain && BRWalletOutpointAssetState(f->w, f->asset->txHash, 0) == 1 &&
           BRWalletAssetUTXOs(f->w, NULL, 0) == 1 && BRWalletSelectableUTXOs(f->w, NULL, 0) == 1;
}

static void addInputOf(BRTransaction *tx, const BRTransaction *parent, uint32_t n)
{
    const BRTxOutput *o = &parent->outputs[n];
    BRTransactionAddInput(tx, parent->txHash, n, o->amount, o->script, o->scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
}

/* The approved send's four outputs, the DGB change at `change`. */
static void approvedOutputs(BRTransaction *tx, uint64_t change)
{
    BRTransactionAddOutput(tx, ASSET_SATS, kRecip, sizeof(kRecip));
    BRTransactionAddOutput(tx, 0, kDAData, sizeof(kDAData));
    BRTransactionAddOutput(tx, ASSET_SATS, g_own, g_ownLen);
    BRTransactionAddOutput(tx, change, g_own, g_ownLen);
}

/* The approved send: the asset coin, then the fee coin; the approved outputs. */
static BRTransaction *approvedSend(const Fixture *f)
{
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, f->asset, 0);
    addInputOf(tx, f->plain, 0);
    approvedOutputs(tx, CHANGE_SATS);
    return tx;
}

static BRAssetSignVerdict gate(BRWallet *w, const BRTransaction *tx)
{
    return BRAssetSignCheck(w, tx, kRecip, sizeof(kRecip), ASSET_SATS, FEE_SATS, FEE_SATS);
}

static void caseA(void)
{
    printf("[A] guard: the approved send\n");
    Fixture f;
    check(fixture(&f), "A setup: one held asset coin, one selectable plain coin");
    BRTransaction *tx = approvedSend(&f);
    g_emptyAsked = 0;
    check(gate(f.w, tx) == BR_ASSET_SIGN_OK, "A guard: the approved send passes");
    check(g_emptyAsked == 0, "A guard: the wallet is not asked about the data output's empty address");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseB(void)
{
    printf("[B] an input past its transaction's last output\n");
    Fixture f;
    check(fixture(&f), "B setup");
    BRTransaction *tx = BRTransactionNew();
    const BRTxOutput *o = &f.asset->outputs[0];
    /* output 2 of a transaction of two, listed at output 0's amount and script */
    BRTransactionAddInput(tx, f.asset->txHash, (uint32_t)f.asset->outCount, o->amount, o->script, o->scriptLen,
                          NULL, 0, NULL, 0, TXIN_SEQUENCE);
    addInputOf(tx, f.plain, 0);
    approvedOutputs(tx, CHANGE_SATS);
    refuses(gate(f.w, tx), BR_ASSET_SIGN_COIN_UNKNOWN, "B: an input past its transaction's last output");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseC(void)
{
    printf("[C] an input with no script\n");
    Fixture f;
    check(fixture(&f), "C setup");
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, f.asset->txHash, 0, ASSET_SATS, NULL, 0, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    tx->inputs[0].scriptLen = f.asset->outputs[0].scriptLen;   /* no script, at the held script's length */
    addInputOf(tx, f.plain, 0);
    approvedOutputs(tx, CHANGE_SATS);
    refuses(gate(f.w, tx), BR_ASSET_SIGN_AMOUNT_MISMATCH, "C: an input with no script");
    tx->inputs[0].scriptLen = 0;
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseD(void)
{
    printf("[D] outputs whose sum passes 2^64\n");
    Fixture f;
    check(fixture(&f), "D setup");
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, f.asset, 0);
    addInputOf(tx, f.plain, 0);
    BRTransactionAddOutput(tx, ASSET_SATS, kRecip, sizeof(kRecip));
    BRTransactionAddOutput(tx, 0, kDAData, sizeof(kDAData));
    /* 700 + 0 + (2^64 - 1) + (700 + CHANGE + 1) = 2^64 + 700 + PLAIN - FEE: wrapped, the inputs less FEE_SATS */
    BRTransactionAddOutput(tx, UINT64_MAX, g_own, g_ownLen);
    BRTransactionAddOutput(tx, ASSET_SATS + CHANGE_SATS + 1, g_own, g_ownLen);
    refuses(gate(f.w, tx), BR_ASSET_SIGN_FEE_CHANGED, "D: outputs whose sum passes 2^64");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseE(void)
{
    printf("[E] inputs whose sum passes 2^64\n");
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRTransaction *coins[9];
    int ok = 1;
    for (int i = 0; i < 9; i++) {
        coins[i] = plainCoin(w, (uint64_t)MAX_MONEY);
        ok = ok && coins[i] && BRWalletOutpointSelectable(w, coins[i]->txHash, 0);
    }
    check(ok, "E setup: nine selectable coins of MAX_MONEY");
    if (! ok) { BRWalletFree(w); return; }
    uint64_t wrapped = 0;
    BRTransaction *tx = BRTransactionNew();
    for (int i = 0; i < 9; i++) { addInputOf(tx, coins[i], 0); wrapped += (uint64_t)MAX_MONEY; }
    check(wrapped < (uint64_t)MAX_MONEY, "E setup: nine MAX_MONEY coins sum past 2^64");
    /* the outputs: the wrapped sum less FEE_SATS */
    BRTransactionAddOutput(tx, ASSET_SATS, kRecip, sizeof(kRecip));
    BRTransactionAddOutput(tx, wrapped - ASSET_SATS - FEE_SATS, g_own, g_ownLen);
    refuses(gate(w, tx), BR_ASSET_SIGN_FEE_CHANGED, "E: inputs whose sum passes 2^64");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void caseF(void)
{
    printf("[F] outputs above the inputs\n");
    Fixture f;
    check(fixture(&f), "F setup");
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, f.asset, 0);
    addInputOf(tx, f.plain, 0);
    approvedOutputs(tx, CHANGE_SATS + FEE_SATS + 1);   /* one sat more out than in */
    refuses(BRAssetSignCheck(f.w, tx, kRecip, sizeof(kRecip), ASSET_SATS, UINT64_MAX, UINT64_MAX),
            BR_ASSET_SIGN_FEE_CHANGED, "F: outputs one sat above the inputs, at a fee of 2^64 - 1");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseG(void)
{
    printf("[G] an input spending an output with no address\n");
    Fixture f;
    check(fixture(&f), "G setup");
    check(f.asset->outputs[1].address[0] == '\0', "G setup: the carrier output pays no address");
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, f.asset, 1);   /* the wallet's own carrier output, at its amount (0) and script */
    addInputOf(tx, f.plain, 0);
    BRTransactionAddOutput(tx, ASSET_SATS, kRecip, sizeof(kRecip));
    BRTransactionAddOutput(tx, 0, kDAData, sizeof(kDAData));
    BRTransactionAddOutput(tx, PLAIN_SATS - ASSET_SATS - FEE_SATS, g_own, g_ownLen);
    g_emptyAsked = 0;
    refuses(gate(f.w, tx), BR_ASSET_SIGN_COIN_NOT_WALLETS, "G: an input spending an output with no address");
    if (g_emptyAsked != 0) {
        printf("FAIL: G: the wallet is asked about an empty address (%d times)\n", g_emptyAsked); g_fail++;
    } else check(1, "G: the wallet is not asked about an empty address");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseH(void)
{
    printf("[H] a held asset coin another send has spent\n");
    Fixture f;
    check(fixture(&f), "H setup");
    BRTransaction *spend = BRTransactionNew();
    addInputOf(spend, f.asset, 0);
    BRTransactionAddOutput(spend, ASSET_SATS - 200, kOther, sizeof(kOther));
    int reg = BRWalletSignTransaction(f.w, spend, 0, g_seed, sizeof(g_seed)) && BRWalletRegisterTransaction(f.w, spend);
    check(reg, "H setup: a send of the asset coin alone is signed and registered");
    if (! reg) BRTransactionFree(spend);
    BRUTXO held[4];
    size_t nHeld = BRWalletAssetUTXOs(f.w, held, 4);
    check(nHeld == 1 && UInt256Eq(held[0].hash, f.asset->txHash) && held[0].n == 0 &&
          BRWalletOutpointAssetState(f.w, f.asset->txHash, 0) == 0 &&
          ! BRWalletOutpointSelectable(f.w, f.asset->txHash, 0) && BRWalletOutpointSelectable(f.w, f.plain->txHash, 0),
          "H setup: the asset coin is still listed as held, in state 0; the fee coin is selectable");
    BRTransaction *tx = approvedSend(&f);
    refuses(gate(f.w, tx), BR_ASSET_SIGN_COIN_NOT_WALLETS, "H: a held asset coin in state 0");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseI(void)
{
    printf("[I] an approved amount of 0\n");
    Fixture f;
    check(fixture(&f), "I setup");
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, f.asset, 0);
    addInputOf(tx, f.plain, 0);
    BRTransactionAddOutput(tx, 0, kRecip, sizeof(kRecip));   /* the recipient paid 0, as approved */
    BRTransactionAddOutput(tx, 0, kDAData, sizeof(kDAData));
    BRTransactionAddOutput(tx, ASSET_SATS, g_own, g_ownLen);
    BRTransactionAddOutput(tx, CHANGE_SATS + ASSET_SATS, g_own, g_ownLen);
    refuses(BRAssetSignCheck(f.w, tx, kRecip, sizeof(kRecip), 0, FEE_SATS, FEE_SATS), BR_ASSET_SIGN_RECIPIENT_CHANGED,
            "I: an approved amount of 0");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseJ(void)
{
    printf("[J] a transaction with no inputs\n");
    Fixture f;
    check(fixture(&f), "J setup");
    BRTransaction *tx = BRTransactionNew();
    approvedOutputs(tx, CHANGE_SATS);
    refuses(gate(f.w, tx), BR_ASSET_SIGN_COIN_UNKNOWN, "J: a transaction with no inputs");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseK(void)
{
    printf("[K] no wallet, no transaction\n");
    Fixture f;
    check(fixture(&f), "K setup");
    BRTransaction *tx = approvedSend(&f);
    refuses(gate(NULL, tx), BR_ASSET_SIGN_COIN_UNKNOWN, "K: no wallet");
    refuses(gate(f.w, NULL), BR_ASSET_SIGN_COIN_UNKNOWN, "K: no transaction");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseL(void)
{
    printf("[L] ONE-FAMILY: no wallet, no transaction\n");
    Fixture f;
    check(fixture(&f), "L setup");
    BRTransaction *none = BRTransactionNew();   /* no inputs */
    approvedOutputs(none, CHANGE_SATS);
    BRTransaction *tx = approvedSend(&f);
    if (BRWalletTxInputsOneFamilyOK(NULL, none) != 0) { printf("FAIL: L: no wallet is accepted\n"); g_fail++; }
    else check(1, "L: no wallet is refused");
    if (BRWalletTxInputsOneFamilyOK(f.w, NULL) != 0) { printf("FAIL: L: no transaction is accepted\n"); g_fail++; }
    else check(1, "L: no transaction is refused");
    check(BRWalletTxInputsOneFamilyOK(f.w, none) == 1, "L guard: a transaction with no inputs holds no other family");
    check(BRWalletTxInputsOneFamilyOK(f.w, tx) == 1, "L guard: the approved send holds no other family");
    BRTransactionFree(none);
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseM(void)
{
    printf("[M] ONE-FAMILY: a dual-family input first or last\n");
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRTransaction *plain1 = plainCoin(w, PLAIN_SATS), *plain2 = plainCoin(w, PLAIN_SATS);
    BRTransaction *d = foreignFunded(DD_VERSION);   /* marked DigiDollar, with a DigiAsset carrier naming output 0 */
    BRTransactionAddOutput(d, 100000000ULL, g_own, g_ownLen);
    BRTransactionAddOutput(d, 0, kDDOpReturn, sizeof(kDDOpReturn));
    BRTransactionAddOutput(d, 0, kDACarrier, sizeof(kDACarrier));
    d->blockHeight = BRNetworkDigiDollarActivationHeight() + 1000;
    d->timestamp = 1700000000;
    finalize(d);
    if (! BRWalletRegisterTransaction(w, d)) { BRTransactionFree(d); d = NULL; }
    int ok = plain1 && plain2 && d && BRWalletOutpointIsOneFamilyHeld(w, d->txHash, 0) == 1 &&
             BRWalletOutpointIsOneFamilyHeld(w, plain1->txHash, 0) == 0 && BRWalletOutpointIsOneFamilyHeld(w, plain2->txHash, 0) == 0;
    check(ok, "M setup: two plain coins and a dual-family output");
    if (! ok) { BRWalletFree(w); return; }
    BRTransaction *first = BRTransactionNew(), *last = BRTransactionNew(), *both = BRTransactionNew();
    addInputOf(first, d, 0); addInputOf(first, plain1, 0);
    addInputOf(last, plain1, 0); addInputOf(last, d, 0);
    addInputOf(both, plain1, 0); addInputOf(both, plain2, 0);
    if (BRWalletTxInputsOneFamilyOK(w, first) != 0) { printf("FAIL: M: a dual-family first input is accepted\n"); g_fail++; }
    else check(1, "M: a dual-family first input is refused");
    if (BRWalletTxInputsOneFamilyOK(w, last) != 0) { printf("FAIL: M: a dual-family last input is accepted\n"); g_fail++; }
    else check(1, "M: a dual-family last input is refused");
    check(BRWalletTxInputsOneFamilyOK(w, both) == 1, "M guard: two plain coins pass");
    BRTransactionFree(first);
    BRTransactionFree(last);
    BRTransactionFree(both);
    BRWalletFree(w);
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
#ifdef ONE_FAMILY_SIGNER_UNFIXED
    const char *of = ", ONE_FAMILY_SIGNER_UNFIXED";
#else
    const char *of = "";
#endif
    printf("ARM: %s%s\n", ASSET_SIGN_GATE_UNFIXED == 1 ? "ASSET_SIGN_GATE_UNFIXED=1" : "shipped", of);
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    g_ownLen = BRAddressScriptPubKey(g_own, sizeof(g_own), a.s);
    BRWalletFree(w);
    if (g_ownLen != 22) { printf("FAIL setup: own script\n"); return 2; }

    caseA(); caseB(); caseC(); caseD(); caseE(); caseF(); caseG(); caseH();
    caseI(); caseJ(); caseK(); caseL(); caseM();
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: asset_sign_gate_core_kat cases\n", g_fail);
    return g_fail ? 1 : 0;
}
