/* asset_sign_gate_kat -- ASSET-SIGN-GATE.
 *
 * THE INVARIANT
 *   The DigiAsset sign path (buildAndSignAssetTransferTx) re-checks natively, before and after signing,
 *   that it spends only wallet or asset coins at the amounts the wallet holds, and pays only the approved
 *   outputs and fee. The check is asset_sign_check (bridge/asset_sign_gate.h), compiled here against the
 *   live core: every input is an output of a transaction the wallet holds, paying a wallet address, at
 *   that output's amount and script, and is a coin the wallet's selection would spend or a held DigiAsset
 *   coin; the recipient is paid by exactly one output, at the approved (marker) amount; every other
 *   output pays the wallet or is the one zero-value data output; the fee is exactly the plan's fee, and
 *   the plan's fee is no more than the fee the confirmation showed.
 *
 * ARMS
 *   default                        the shipped check.
 *   -DASSET_SIGN_GATE_UNFIXED=1    the behaviour before it: nothing checked (every verdict OK).
 *   -DASSET_SIGN_GATE_UNFIXED=2    the first version of the check: the recipient by script alone, any
 *                                  number of data outputs, the fee against the plan's own fee only.
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
 *   [I] RED-THEN-GREEN (both comparison arms). A plan whose fee is one sat above the fee the confirmation
 *       showed: refused (FEE_ABOVE_APPROVED), though the transaction pays exactly the plan's fee.
 *   [J] RED-THEN-GREEN (both). The DGB change put on the recipient's output, which then carries more than
 *       the marker: refused (RECIPIENT_CHANGED), though the outputs and the fee add up.
 *   [K] RED-THEN-GREEN (both). The DGB change paid to the recipient as a second output: refused
 *       (RECIPIENT_CHANGED).
 *   [L] RED-THEN-GREEN (both). A second zero-value data output: refused (NOT_APPROVED).
 *   [M] GUARD, every arm. A send to one of the wallet's own addresses, whose change pays that same
 *       address, passes: the recipient's other outputs are the wallet's change.
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

/* The gate as the bridge calls it for a send whose plan pays `fee` and whose confirmation showed it. */
static AssetSignVerdict gate(BRWallet *w, const BRTransaction *tx, uint64_t fee)
{
    return asset_sign_check(w, tx, kRecip, sizeof(kRecip), ASSET_SATS, fee, fee);
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
    check(asset_sign_check(f.w, tx, kRecip, sizeof(kRecip), ASSET_SATS, FEE_SATS, FEE_SATS + 1) == ASSET_SIGN_OK,
          "A guard: a plan fee under the approved fee passes");
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
    /* the approved fee is generous here: only the plan's fee is off */
    AssetSignVerdict lo = asset_sign_check(f.w, tx, kRecip, sizeof(kRecip), ASSET_SATS, FEE_SATS - 1, 10 * FEE_SATS);
    AssetSignVerdict hi = asset_sign_check(f.w, tx, kRecip, sizeof(kRecip), ASSET_SATS, FEE_SATS + 1, 10 * FEE_SATS);
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

static void caseI(void)
{
    printf("[I] a plan fee above the approved fee\n");
    Fixture f;
    check(fixture(&f), "I setup");
    BRTransaction *tx = assetSend(f.asset, f.plain, PLAIN_SATS, g_own, g_ownLen);
    AssetSignVerdict v = asset_sign_check(f.w, tx, kRecip, sizeof(kRecip), ASSET_SATS, FEE_SATS, FEE_SATS - 1);
    if (v != ASSET_SIGN_FEE_ABOVE_APPROVED) { printf("FAIL: I: a plan paying more than the approved fee is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "I: a plan paying one sat more than the approved fee is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

/* The approved send, but with outputs `outs` (amounts and scripts): the same inputs. */
static BRTransaction *withOutputs(const Fixture *f, const BRTxOutput *outs, size_t n)
{
    BRTransaction *tx = BRTransactionNew();
    addInputOf(tx, f->asset, 0, f->asset->outputs[0].amount);
    addInputOf(tx, f->plain, 0, PLAIN_SATS);
    for (size_t i = 0; i < n; i++) BRTransactionAddOutput(tx, outs[i].amount, outs[i].script, outs[i].scriptLen);
    return tx;
}

#define CHANGE_SATS (PLAIN_SATS - ASSET_SATS - FEE_SATS)

static void caseJ(void)
{
    printf("[J] the DGB change on the recipient's output\n");
    Fixture f;
    check(fixture(&f), "J setup");
    BRTxOutput outs[3] = {
        { "", ASSET_SATS + CHANGE_SATS, (uint8_t *)kRecip, sizeof(kRecip) },
        { "", 0, (uint8_t *)kDAData, sizeof(kDAData) },
        { "", ASSET_SATS, g_own, g_ownLen },
    };
    BRTransaction *tx = withOutputs(&f, outs, 3);
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_RECIPIENT_CHANGED) { printf("FAIL: J: a recipient output above the approved amount is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "J: a recipient output above the approved amount is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseK(void)
{
    printf("[K] a second output to the recipient\n");
    Fixture f;
    check(fixture(&f), "K setup");
    BRTxOutput outs[4] = {
        { "", ASSET_SATS, (uint8_t *)kRecip, sizeof(kRecip) },
        { "", 0, (uint8_t *)kDAData, sizeof(kDAData) },
        { "", ASSET_SATS, g_own, g_ownLen },
        { "", CHANGE_SATS, (uint8_t *)kRecip, sizeof(kRecip) },
    };
    BRTransaction *tx = withOutputs(&f, outs, 4);
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_RECIPIENT_CHANGED) { printf("FAIL: K: a second recipient output is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "K: a second recipient output is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseL(void)
{
    printf("[L] a second data output\n");
    Fixture f;
    check(fixture(&f), "L setup");
    BRTxOutput outs[5] = {
        { "", ASSET_SATS, (uint8_t *)kRecip, sizeof(kRecip) },
        { "", 0, (uint8_t *)kDAData, sizeof(kDAData) },
        { "", 0, (uint8_t *)kDACarrier, sizeof(kDACarrier) },
        { "", ASSET_SATS, g_own, g_ownLen },
        { "", CHANGE_SATS, g_own, g_ownLen },
    };
    BRTransaction *tx = withOutputs(&f, outs, 5);
    AssetSignVerdict v = gate(f.w, tx, FEE_SATS);
    if (v != ASSET_SIGN_NOT_APPROVED) { printf("FAIL: L: a second data output is signed (verdict %d)\n", (int)v); g_fail++; }
    else check(1, "L: a second data output is refused");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

static void caseM(void)
{
    printf("[M] guard: a send to the wallet's own address\n");
    Fixture f;
    check(fixture(&f), "M setup");
    BRTxOutput outs[4] = {
        { "", ASSET_SATS, g_own, g_ownLen },
        { "", 0, (uint8_t *)kDAData, sizeof(kDAData) },
        { "", CHANGE_SATS, g_own, g_ownLen },
        { "", ASSET_SATS, g_own, g_ownLen },
    };
    BRTransaction *tx = withOutputs(&f, outs, 4);
    AssetSignVerdict v = asset_sign_check(f.w, tx, g_own, g_ownLen, ASSET_SATS, FEE_SATS, FEE_SATS);
    check(v == ASSET_SIGN_OK, "M guard: a send to oneself, change to the same address, passes");
    BRTransactionFree(tx);
    BRWalletFree(f.w);
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("ARM: %s\n", ASSET_SIGN_GATE_UNFIXED == 1 ? "comparison (ASSET_SIGN_GATE_UNFIXED=1)"
                        : ASSET_SIGN_GATE_UNFIXED == 2 ? "comparison (ASSET_SIGN_GATE_UNFIXED=2)" : "shipped");
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    g_ownLen = BRAddressScriptPubKey(g_own, sizeof(g_own), a.s);
    BRWalletFree(w);
    if (g_ownLen != 22) { printf("FAIL setup: own script\n"); return 2; }

    caseA(); caseB(); caseC(); caseD(); caseE(); caseF(); caseG(); caseH();
    caseI(); caseJ(); caseK(); caseL(); caseM();
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: asset_sign_gate_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
