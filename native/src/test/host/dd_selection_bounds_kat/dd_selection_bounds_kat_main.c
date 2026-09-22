// Host KAT: the DigiDollar transfer builder's per-call working sets are never charged to the
// caller's stack, and each one is released exactly once.
//
// THE INVARIANT THIS PINS. The builder needs a working set per call whose size comes from a count it
// reads out of the wallet -- how many DigiDollar coins it holds, and how many plain DGB coins are
// available to pay the fee. Those counts are wallet state, not arguments of the call, so the rule
// this KAT locks in is: the size of a working set is taken from the heap with a multiply checked for
// wrap, it is never charged to the caller's stack, and it is released exactly once on whatever path
// the call returns by. A call therefore answers -- a transaction, or a clean NULL -- on a thread
// whose stack is a fixed, modest size, whatever those counts are.
//
// WHAT THE ARMS COMPARE. Each arm runs the builder on a pthread created with a fixed stack size
// (pthread_attr_setstacksize), holding the same single $100.00 DigiDollar coin and a different
// number of plain DGB coins: none, one, and two much larger counts. Each arm asserts the outcome it
// must have -- the first two cannot reach the 0.1 DGB fee floor and must return a clean NULL, the
// two larger ones must return a transaction -- so no arm passes on the mere absence of a sanitizer
// report. AddressSanitizer and LeakSanitizer are enabled for every arm (detect_leaks=1) and a report
// from either fails the run. Single-arm KAT: the invariant is a property of the shipped function, so
// there is no -D seam.
//
// Exit code 0 = every arm returned the outcome it must and no sanitizer reported anything.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>

#include "BRWallet.h"
#include "BRDigiDollar.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRTransaction.h"

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = {0};
static const uint8_t kRecip[32] = {
    0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
    0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };

static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;

static void finalizeTxHash(BRTransaction *tx) {
    size_t need = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(need ? need : 1);
    size_t len = BRTransactionSerialize(tx, data, need);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

// A DD token credit of `cents` (single-amount "DD" OP_RETURN), so the transfer gate is passed.
static BRTransaction *ddCreditTx(const uint8_t *spk, size_t spkLen, int64_t cents) {
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    UInt256 ph; memset(ph.u8, 0x11, 32);
    BRTransactionAddInput(tx, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);
    uint8_t orr[32]; size_t ol = 0;
    orr[ol++]=0x6a; orr[ol++]=0x02; orr[ol++]=0x44; orr[ol++]=0x44; orr[ol++]=0x01; orr[ol++]=0x02;
    uint8_t enc[9]; size_t el = BRDigiDollarWriteScriptNum(cents, enc);
    orr[ol++] = (uint8_t)el; memcpy(orr + ol, enc, el); ol += el;
    BRTransactionAddOutput(tx, 0, orr, ol);
    tx->blockHeight = 700000;
    finalizeTxHash(tx);
    return tx;
}

// Build a wallet holding one DD coin (10000c) and exactly `nPlain` plain DGB coins. The plain coins
// are carried as `nPlain` outputs of one confirmed transaction paying our P2WPKH address, so the
// wallet's plain-coin count (which sizes the fee work array) is `nPlain` at low setup cost.
static BRWallet *mkWallet(size_t nPlain) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);

    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    BRWalletRegisterTransaction(w, ddCreditTx(spk, spkLen, 10000));

    if (nPlain > 0) {
        BRAddress da = BRWalletReceiveAddress(w, 1);
        uint8_t dspk[64]; size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
        BRTransaction *tx = BRTransactionNew();
        tx->version = 1;
        UInt256 ph; memset(ph.u8, 0x22, 32);
        BRTransactionAddInput(tx, ph, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
        for (size_t i = 0; i < nPlain; i++) BRTransactionAddOutput(tx, 20000, dspk, dspkLen);
        tx->blockHeight = 700000;
        // A unique, stable txHash without serialising a 50,000-output tx on the setup stack.
        memset(tx->txHash.u8, 0x77, 32);
        BRWalletRegisterTransaction(w, tx);
    }
    return w;
}

typedef struct { BRWallet *w; uint64_t cents; BRTransaction *result; } job_t;
static void *worker(void *p) { job_t *j = p; j->result = BRWalletCreateDigiDollarTransfer(j->w, kRecip, j->cents); return NULL; }

// Run the builder on a pthread whose stack is 1 MB. Returns 0 on a clean run (result in *out).
static int runOn1MB(BRWallet *w, uint64_t cents, BRTransaction **out) {
    pthread_attr_t attr; pthread_attr_init(&attr);
    if (pthread_attr_setstacksize(&attr, 1024 * 1024) != 0) { pthread_attr_destroy(&attr); return -1; }
    job_t j = { w, cents, NULL };
    pthread_t th;
    if (pthread_create(&th, &attr, worker, &j) != 0) { pthread_attr_destroy(&attr); return -1; }
    pthread_join(th, NULL);
    pthread_attr_destroy(&attr);
    *out = j.result;
    return 0;
}

int main(void) {
    printf("dd_selection_bounds_kat\n");
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));

    // nPlain, and the outcome that count must produce: one 20,000-sat plain coin cannot reach the
    // 0.1 DGB fee floor, so the first two arms are a clean NULL; the larger two cover the fee.
    const size_t counts[] = { 0, 1, 5000, 50000 };
    const int wantTx[]    = { 0, 0,    1,     1 };
    int fail = 0;
    for (size_t ci = 0; ci < sizeof(counts) / sizeof(counts[0]); ci++) {
        size_t n = counts[ci];
        BRWallet *w = mkWallet(n);
        if (BRWalletDigiDollarBalance(w) != 10000) {
            printf("  FAIL setup: DD balance wrong for nPlain=%zu\n", n); fail = 1; BRWalletFree(w); continue;
        }
        BRTransaction *tx = NULL;
        int rc = runOn1MB(w, 5000, &tx);   // send $50.00 of the $100.00 held
        if (rc != 0) { printf("  FAIL nPlain=%zu: could not start the bounded-stack thread\n", n); fail = 1; }
        else if ((tx != NULL) != (wantTx[ci] != 0)) {
            printf("  FAIL nPlain=%6zu on a bounded stack -> %s, expected %s\n", n,
                   tx ? "transaction" : "clean NULL", wantTx[ci] ? "transaction" : "clean NULL");
            fail = 1;
        }
        else printf("  ok   nPlain=%6zu on a bounded stack -> %s (as required)\n", n,
                    tx ? "transaction" : "clean NULL");
        if (tx) BRTransactionFree(tx);
        BRWalletFree(w);
    }

    printf(fail ? "\n%d FAIL\n" : "\nALL PASS (every count returned the outcome it must)\n", fail);
    return fail ? 1 : 0;
}
