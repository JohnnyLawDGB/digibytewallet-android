// Host KAT: the non-wallet unconfirmed transactions the wallet keeps are bounded, and released with it.
//
// A peer can relay unconfirmed transactions that are not the wallet's; the wallet keeps such a record
// in allTx for the validity and pending checks of its own unconfirmed transactions. The kept set is
// bounded by count (WALLET_FOREIGN_TX_MAX_COUNT) and by serialized bytes (WALLET_FOREIGN_TX_MAX_BYTES);
// one past either bound is not kept, and its caller, which still owns it, releases it (as every
// caller does: it frees what BRWalletTransactionForHash does not return as the wallet's record).
//
// CASES (one per process, chosen by argv[1]):
//   count    5,000 small non-wallet txs relayed: at most WALLET_FOREIGN_TX_MAX_COUNT are kept.
//   bytes    100 non-wallet txs of about 51 KB each: the kept ones total at most
//            WALLET_FOREIGN_TX_MAX_BYTES serialized bytes.
//   own      with the kept set full, the wallet's own receive still registers and is credited
//            (control: holds in both arms).
//   release  10 kept; one confirmed (the wallet drops it), one removed by hash; the wallet is then
//            freed. Run with LeakSanitizer: every kept record is released exactly once.
// Comparison arm (-DWALLET_FOREIGN_TX_BOUND_UNFIXED): count and bytes keep everything; release
// leaks the kept records (a LeakSanitizer report); own passes.
//
// BRWallet.c is #included, so it is NOT on the clang line. Exit 0 = the case held.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "BRWallet.c"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static const char *kMn = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kSig[2] = {0x01, 0x51};
static const char *kOther = "dgb1q0wrdczqmntgws8mcjsuzwalzd3fe5sm3qvvlrp";
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk;

static void fin(BRTransaction *tx) {
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(n);
    n = BRTransactionSerialize(tx, d, n);
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(d);
}

// A tx spending someone else's coin (tag) with `outs` outputs paying `addr`.
static BRTransaction *payTo(const char *addr, uint32_t tag, size_t outs, uint64_t each, uint32_t height) {
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    UInt256 h; memset(h.u8, 0x6b, 32); UInt32SetLE(h.u8, tag);
    BRTransactionAddInput(tx, h, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    for (size_t i = 0; i < outs; i++) BRTransactionAddOutput(tx, each, spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : 1700000000;
    return tx;
}

// What every caller does: register, then release the object unless the wallet now holds it.
static int relay(BRWallet *w, BRTransaction *tx) {
    UInt256 h = tx->txHash;
    int r = BRWalletRegisterTransaction(w, tx);
    if (BRWalletTransactionForHash(w, h) != tx) BRTransactionFree(tx);
    return r;
}

// Kept non-wallet records, counted independently of the wallet's own bookkeeping.
static size_t keptCount(BRWallet *w, size_t *bytes) {
    size_t n = 0, b = 0;
    pthread_mutex_lock(&w->lock);
    for (BRTransaction *t = BRSetIterate(w->allTx, NULL); t; t = BRSetIterate(w->allTx, t)) {
        int own = 0;
        for (size_t i = 0; i < array_count(w->transactions); i++) if (w->transactions[i] == t) { own = 1; break; }
        if (own) continue;
        n++; b += BRTransactionSize(t);
    }
    pthread_mutex_unlock(&w->lock);
    if (bytes) *bytes = b;
    return n;
}

static int runCase(const char *name) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
    size_t n, bytes;
    char what[200];

    if (strcmp(name, "count") == 0) {
        printf("[count] 5,000 small non-wallet unconfirmed txs\n");
        for (uint32_t i = 0; i < 5000; i++) relay(w, payTo(kOther, i + 1, 1, 100000, TX_UNCONFIRMED));
        n = keptCount(w, &bytes);
        printf("  kept: %zu records, %zu bytes\n", n, bytes);
        snprintf(what, sizeof(what), "at most %d are kept", WALLET_FOREIGN_TX_MAX_COUNT);
        ck(n <= WALLET_FOREIGN_TX_MAX_COUNT, what);
        ck(n > 0, "some are kept (the pending/validity checks still have their records)");
    }
    else if (strcmp(name, "bytes") == 0) {
        printf("[bytes] 100 non-wallet unconfirmed txs of ~51 KB\n");
        for (uint32_t i = 0; i < 100; i++) relay(w, payTo(kOther, i + 1, 1500, 100000, TX_UNCONFIRMED));
        n = keptCount(w, &bytes);
        printf("  kept: %zu records, %zu bytes\n", n, bytes);
        snprintf(what, sizeof(what), "the kept records total at most %d serialized bytes", WALLET_FOREIGN_TX_MAX_BYTES);
        ck(bytes <= WALLET_FOREIGN_TX_MAX_BYTES, what);
    }
    else if (strcmp(name, "own") == 0) {
        printf("[own] the kept set full, then the wallet's own receive\n");
        for (uint32_t i = 0; i < WALLET_FOREIGN_TX_MAX_COUNT + 10; i++)
            relay(w, payTo(kOther, i + 1, 1, 100000, TX_UNCONFIRMED));
        BRAddress a; BRWalletUnusedAddrs(w, &a, 1, 0, 1);
        int r = relay(w, payTo(a.s, 0x00ffffff, 1, 123456789, 700000));
        ck(r == 1, "the wallet's own receive registers");
        ck(BRWalletBalance(w) == 123456789, "and is credited");
    }
    else if (strcmp(name, "release") == 0) {
        printf("[release] 10 kept; one confirmed, one removed; the wallet freed\n");
        UInt256 h[10];
        for (uint32_t i = 0; i < 10; i++) {
            BRTransaction *t = payTo(kOther, i + 1, 1, 100000, TX_UNCONFIRMED);
            h[i] = t->txHash;
            relay(w, t);
        }
        n = keptCount(w, NULL);
        printf("  kept: %zu\n", n);
        ck(n == 10, "all 10 are kept (inside both bounds)");
        BRWalletUpdateTransactions(w, &h[0], 1, 700000, 1700000000);
        BRWalletRemoveTransaction(w, h[1]);
        n = keptCount(w, NULL);
        printf("  kept after one confirmed and one removed: %zu\n", n);
        ck(n == 8, "the confirmed one and the removed one are gone");
    }
    else { printf("unknown case %s\n", name); return 2; }

    BRWalletFree(w);
    printf(g_fail == 0 ? "CASE PASS %s\n" : "CASE FAIL %s\n", name);
    return g_fail ? 1 : 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(g_seed, kMn, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    if (argc < 2) { printf("usage: %s count|bytes|own|release\n", argv[0]); return 2; }
    return runCase(argv[1]);
}
