/* one_family_kat — ONE-FAMILY: an output belongs to at most one token family.
 *
 * THE INVARIANT
 *   A DigiDollar output is never an asset holding (BRWalletOutpointAssetState), never a plain coin or a
 *   fee coin (coin selection), and never an input the asset-send signer signs (one_family_gate.h); it is
 *   spent only by the DigiDollar builder. A transaction marked both DigiDollar and DigiAsset is held:
 *   credited to neither balance, and none of its outputs is offered to any builder, the DD one included.
 *
 * ARMS
 *   default                                    the shipped rule.
 *   -DONE_FAMILY_UNFIXED -DONE_FAMILY_SIGNER_UNFIXED   no cross-family exclusion anywhere (the old code).
 *
 * CASES
 *   [A] RED-THEN-GREEN. A DigiDollar transfer whose "DD" OP_RETURN is not nulldata (an OP_NOP in it) and
 *       which also carries a lone DigiAsset carrier naming the DD output: the carrier rule answers
 *       CARRIER for it, so it is live under DA-014-REF. Checks: asset state of the DD output is not HELD;
 *       no DigiDollar credit; its DGB-valued output is no plain coin and is not selectable; a plain send
 *       spends nothing of it; the DD builder spends nothing of it; the signer refuses its outputs.
 *   [B] RED-THEN-GREEN. The same with two nulldata outputs (carrier verdict UNDECIDED): the DD output is
 *       not HELD for the asset layer and carries no DigiDollar credit.
 *   [C] RED-THEN-GREEN on one check, GUARD on the rest. An ordinary DigiDollar receive: credited, spent by
 *       the DD builder (both arms); its asset state is OTHER_FAMILY and the signer refuses it (green only).
 *   [D] GUARD, both arms. An ordinary DigiAsset receive: HELD, and the signer accepts it.
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
#include "BRDigiAsset.h"
#include "BRNetwork.h"
#include "one_family_gate.h"

static int g_fail = 0;
static void check(int c, const char *what)
{
    printf("%s: %s\n", c ? "PASS" : "FAIL", what);
    if (! c) g_fail++;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = { 0 };
static BRMasterPubKey g_mpk84, g_mpk86;
static uint8_t g_tr[64], g_wpkh[64];
static size_t g_trLen, g_wpkhLen;
static uint32_t g_H;
static uint8_t g_fill = 0x20;

static void finalize(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *b = malloc(n);
    n = BRTransactionSerialize(tx, b, n);
    BRTransaction *t = BRTransactionParse(b, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(b);
}

static BRWallet *newWallet(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    return w;
}

static BRTransaction *baseTx(uint32_t version)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;
    memset(prev.u8, g_fill++, 32);   /* a foreign prevout: nothing of the wallet is spent */
    tx->version = version;
    BRTransactionAddInput(tx, prev, 0, 0, g_wpkh, g_wpkhLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    return tx;
}

static void registerConfirmed(BRWallet *w, BRTransaction *tx)
{
    tx->blockHeight = g_H;
    finalize(tx);
    BRWalletRegisterTransaction(w, tx);
}

#define DD_VERSION   (((uint32_t)DD_TYPE_TRANSFER << 24) | DD_VERSION_MARKER)
#define DD_CENTS     5000                                              /* $50.00 */
static const uint8_t kDDOpReturn[]       = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x88, 0x13 };    /* "DD" 2 [5000] */
static const uint8_t kDDOpReturnNonNull[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x88, 0x13, 0x61 }; /* ... OP_NOP */
static const uint8_t kDACarrier[]        = { 0x6a, 0x06, 0x44, 0x41, 0x01, 0x15, 0x00, 0x0a };          /* names output 0 */

static int spendsTx(const BRTransaction *spend, UInt256 h)
{
    for (size_t i = 0; spend && i < spend->inCount; i++) if (UInt256Eq(spend->inputs[i].txHash, h)) return 1;
    return 0;
}

/* An asset-send-shaped tx spending (h, n), as buildAndSignAssetTransferTx assembles it before signing. */
static int signerAccepts(BRWallet *w, UInt256 h, uint32_t n)
{
    BRTransaction *t = BRTransactionNew();
    BRTransactionAddInput(t, h, n, 0, g_tr, g_trLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    BRTransactionAddOutput(t, 700, g_wpkh, g_wpkhLen);
    int ok = tx_inputs_one_family_ok(w, t);
    BRTransactionFree(t);
    return ok;
}

static const uint8_t kRecip[32] = { 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e,
                                    0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e,
                                    0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e };

/* A plain 10 DGB coin to the wallet, so the builders have fee coins. */
static BRTransaction *fundPlain(BRWallet *w)
{
    BRTransaction *f = baseTx(1);
    BRTransactionAddOutput(f, 1000000000ULL, g_wpkh, g_wpkhLen);
    registerConfirmed(w, f);
    return f;
}

static void caseA(void)
{
    printf("[A] DigiDollar transfer + lone DigiAsset carrier (carrier verdict CARRIER)\n");
    BRWallet *w = newWallet();
    BRTransaction *f = fundPlain(w);
    BRTransaction *d = baseTx(DD_VERSION);
    BRTransactionAddOutput(d, 0, g_tr, g_trLen);                               /* 0: DD token, ours */
    BRTransactionAddOutput(d, 0, kDDOpReturnNonNull, sizeof(kDDOpReturnNonNull)); /* 1: "DD" OP_RETURN */
    BRTransactionAddOutput(d, 0, kDACarrier, sizeof(kDACarrier));               /* 2: the DA carrier */
    BRTransactionAddOutput(d, 100000000ULL, g_wpkh, g_wpkhLen);               /* 3: 1 DGB, ours */
    size_t carrier = 99;
    int setup = BRTxAssetCarrier(d, &carrier) == DA_TX_CARRIER && carrier == 2 &&
                BRDigiDollarOutputAmount(d, 0) == DD_CENTS;
    check(setup, "A setup: the carrier rule answers CARRIER:2 and output 0 is bound to $50.00");
    registerConfirmed(w, d);
    UInt256 h = d->txHash;

    if (BRWalletOutpointAssetState(w, h, 0) == 1) { printf("FAIL: A: the DD output reads HELD for the asset layer\n"); g_fail++; }
    else check(BRWalletOutpointAssetState(w, h, 0) == -3, "A: the DD output is OTHER_FAMILY (-3) for the asset layer");
    if (BRWalletDigiDollarBalance(w) != 0) { printf("FAIL: A: the dual-family tx was credited as DigiDollar\n"); g_fail++; }
    else check(BRWalletDigiDollarUTXOs(w, NULL, 0) == 0, "A: no DigiDollar credit, no DD coin");
    if (BRWalletBalance(w) != 1000000000ULL) { printf("FAIL: A: the dual-family tx's DGB output counts as a plain coin\n"); g_fail++; }
    else check(1, "A: the dual-family tx's DGB output is no plain coin");
    BRUTXO sel[8];
    size_t ns = BRWalletSelectableUTXOs(w, sel, 8), fromDual = 0;
    for (size_t i = 0; i < ns; i++) if (UInt256Eq(sel[i].hash, h)) fromDual++;
    if (fromDual) { printf("FAIL: A: coin selection offers an output of the dual-family tx\n"); g_fail++; }
    else check(ns == 1, "A: coin selection offers only the plain funding coin");
    BRAddress dest = BRWalletReceiveAddress(w, 1);
    BRTransaction *plain = BRWalletCreateTransaction(w, 1050000000ULL, dest.s);   /* more than the plain coin */
    if (plain && spendsTx(plain, h)) { printf("FAIL: A: a plain send spends the dual-family tx\n"); g_fail++; }
    else check(plain == NULL, "A: a plain send cannot reach the dual-family tx's coin");
    if (plain) BRTransactionFree(plain);
    BRTransaction *ddsend = BRWalletCreateDigiDollarTransfer(w, kRecip, DD_CENTS);
    if (ddsend && spendsTx(ddsend, h)) { printf("FAIL: A: the DD builder spends the dual-family tx's DD output\n"); g_fail++; }
    else check(ddsend == NULL, "A: the DD builder has no DD coin to spend");
    if (ddsend) BRTransactionFree(ddsend);
    if (signerAccepts(w, h, 0) || signerAccepts(w, h, 3)) { printf("FAIL: A: the asset signer accepts an output of the dual-family tx\n"); g_fail++; }
    else check(1, "A: the asset signer refuses every output of the dual-family tx");
    (void)f;
    BRWalletFree(w);
}

static void caseB(void)
{
    printf("[B] DigiDollar transfer + second nulldata carrier (carrier verdict UNDECIDED)\n");
    BRWallet *w = newWallet();
    BRTransaction *d = baseTx(DD_VERSION);
    BRTransactionAddOutput(d, 0, g_tr, g_trLen);
    BRTransactionAddOutput(d, 0, kDDOpReturn, sizeof(kDDOpReturn));
    BRTransactionAddOutput(d, 0, kDACarrier, sizeof(kDACarrier));
    check(BRTxAssetCarrier(d, NULL) == DA_TX_UNDECIDED && BRDigiDollarOutputAmount(d, 0) == DD_CENTS,
          "B setup: UNDECIDED carrier, output 0 bound to $50.00");
    registerConfirmed(w, d);
    if (BRWalletOutpointAssetState(w, d->txHash, 0) == 1) { printf("FAIL: B: the DD output reads HELD for the asset layer\n"); g_fail++; }
    else check(1, "B: the DD output is not HELD for the asset layer");
    if (BRWalletDigiDollarBalance(w) != 0) { printf("FAIL: B: the dual-family tx was credited as DigiDollar\n"); g_fail++; }
    else check(1, "B: no DigiDollar credit");
    BRWalletFree(w);
}

static void caseC(void)
{
    printf("[C] an ordinary DigiDollar receive\n");
    BRWallet *w = newWallet();
    fundPlain(w);
    BRTransaction *d = baseTx(DD_VERSION);
    BRTransactionAddOutput(d, 0, g_tr, g_trLen);
    BRTransactionAddOutput(d, 0, kDDOpReturn, sizeof(kDDOpReturn));
    registerConfirmed(w, d);
    check(BRWalletDigiDollarBalance(w) == DD_CENTS && BRWalletDigiDollarUTXOs(w, NULL, 0) == 1,
          "C guard: $50.00 credited as one DD coin");
    BRTransaction *ddsend = BRWalletCreateDigiDollarTransfer(w, kRecip, DD_CENTS);
    check(ddsend != NULL && spendsTx(ddsend, d->txHash), "C guard: the DD builder spends it");
    if (ddsend) BRTransactionFree(ddsend);
    if (BRWalletOutpointAssetState(w, d->txHash, 0) == 1) { printf("FAIL: C: an ordinary DD output reads HELD for the asset layer\n"); g_fail++; }
    else check(BRWalletOutpointAssetState(w, d->txHash, 0) == -3, "C: the DD output is OTHER_FAMILY (-3)");
    if (signerAccepts(w, d->txHash, 0)) { printf("FAIL: C: the asset signer accepts a DD output\n"); g_fail++; }
    else check(1, "C: the asset signer refuses a DD output");
    BRWalletFree(w);
}

static void caseD(void)
{
    printf("[D] an ordinary DigiAsset receive\n");
    BRWallet *w = newWallet();
    BRTransaction *a = baseTx(1);
    BRTransactionAddOutput(a, 700, g_wpkh, g_wpkhLen);                       /* 0: the asset, ours */
    BRTransactionAddOutput(a, 0, kDACarrier, sizeof(kDACarrier));
    registerConfirmed(w, a);
    check(BRWalletOutpointAssetState(w, a->txHash, 0) == 1, "D guard: the asset output is HELD");
    check(signerAccepts(w, a->txHash, 0), "D guard: the asset signer accepts it");
    check(BRWalletOutpointIsOneFamilyHeld(w, a->txHash, 0) == 0, "D guard: it is not held under ONE-FAMILY");
    BRWalletFree(w);
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRWallet *w = newWallet();
    BRAddress ta = BRWalletReceiveAddress(w, 2), sa = BRWalletReceiveAddress(w, 1);
    g_trLen = BRAddressScriptPubKey(g_tr, sizeof(g_tr), ta.s);
    g_wpkhLen = BRAddressScriptPubKey(g_wpkh, sizeof(g_wpkh), sa.s);
    BRWalletFree(w);
    g_H = BRNetworkDigiDollarActivationHeight() + 1000;
    if (g_trLen != 34 || g_wpkhLen != 22) { printf("FAIL setup: own scripts\n"); return 2; }

    caseA();
    caseB();
    caseC();
    caseD();
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: one_family_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
