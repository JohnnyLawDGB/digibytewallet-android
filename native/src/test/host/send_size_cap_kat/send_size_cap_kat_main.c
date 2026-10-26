// Host KAT: coin selection never changes a caller's output. When the coins it would need do not
// fit TX_MAX_SIZE, the build is refused (NULL), whatever the outputs ask for.
//
// WHAT IT PROVES (one case per process, chosen by argv[1], so one arm's fault cannot hide another):
//   small   3,000 confirmed 55,000-sat coins, then one 10 DGB coin; send 2 DGB. The coins that fit
//           the size cap do not cover it. Shipped: refused. Comparison arm: a transaction comes back
//           whose single output pays less than the 2 DGB asked for.
//   tiny    3,000 confirmed 5,000-sat coins, then one 10 DGB coin; send 2 DGB. The coins that fit
//           do not even cover the fee. Shipped: refused. Comparison arm: the build retries with no
//           outputs at all and then reads the output before the first one (a sanitizer report).
//   fits    the same 55,000-sat wallet sends 0.5 DGB, which the coins inside the cap cover: the
//           build succeeds in both arms and pays exactly 0.5 DGB to the destination (control).
//   plain   a wallet with three coins sends 1 DGB: built, pays exactly 1 DGB (control).
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
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk;
static const char *kDest = "dgb1q0wrdczqmntgws8mcjsuzwalzd3fe5sm3qvvlrp";

static void fin(BRTransaction *tx) {
    uint8_t d[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A confirmed tx from someone else's coin (tag, n) paying `amt` to `addr`.
static BRTransaction *receive(const char *addr, uint32_t tag, uint64_t amt, uint32_t height) {
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    UInt256 h; memset(h.u8, 0x5a, 32); UInt32SetLE(h.u8, tag);
    BRTransactionAddInput(tx, h, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = 1700000000 + height;
    return tx;
}

// A wallet holding `n` coins of `each` sat (oldest first), then one coin of `last` sat (0: none).
static BRWallet *walletWith(size_t n, uint64_t each, uint64_t last) {
    BRWallet *w0 = BRWalletNew(NULL, 0, g_mpk);
    BRAddress a; BRWalletUnusedAddrs(w0, &a, 1, 0, 1);
    BRWalletFree(w0);
    BRTransaction **txs = calloc(n + 1, sizeof(*txs));
    size_t k = 0;
    for (size_t i = 0; i < n; i++) txs[k++] = receive(a.s, (uint32_t)i + 1, each, 700000 + (uint32_t)i);
    if (last) txs[k++] = receive(a.s, 0x00ffffff, last, 700000 + (uint32_t)n);
    BRWallet *w = BRWalletNew(txs, k, g_mpk);
    free(txs);
    return w;
}

// True when tx has an output paying exactly `amt` to kDest, and no other output pays kDest.
static int paysExactly(const BRTransaction *tx, uint64_t amt) {
    int hit = 0, other = 0;
    for (size_t i = 0; tx && i < tx->outCount; i++) {
        if (strcmp(tx->outputs[i].address, kDest) != 0) continue;
        if (tx->outputs[i].amount == amt) hit++; else other++;
    }
    return hit == 1 && other == 0;
}

static void describe(const BRTransaction *tx) {
    if (! tx) { printf("  result: refused (NULL)\n"); return; }
    printf("  result: %zu inputs, %zu outputs, vsize %zu\n", tx->inCount, tx->outCount, BRTransactionVSize(tx));
    for (size_t i = 0; i < tx->outCount; i++)
        printf("    output[%zu] %llu sat%s\n", i, (unsigned long long)tx->outputs[i].amount,
               strcmp(tx->outputs[i].address, kDest) == 0 ? " (destination)" : "");
}

static int runCase(const char *name) {
    BRWallet *w = NULL;
    BRTransaction *tx = NULL;

    if (strcmp(name, "small") == 0) {
        printf("[small] 3,000 x 55,000 sat + 10 DGB; send 2 DGB\n");
        w = walletWith(3000, 55000ULL, 1000000000ULL);
        tx = BRWalletCreateTransaction(w, 200000000ULL, kDest);
        describe(tx);
        ck(tx == NULL || paysExactly(tx, 200000000ULL), "no transaction pays the destination anything but 2 DGB");
        ck(tx == NULL, "the build is refused (the coins that fit the size cap do not cover it)");
    }
    else if (strcmp(name, "tiny") == 0) {
        printf("[tiny] 3,000 x 5,000 sat + 10 DGB; send 2 DGB\n");
        w = walletWith(3000, 5000ULL, 1000000000ULL);
        tx = BRWalletCreateTransaction(w, 200000000ULL, kDest);
        describe(tx);
        ck(tx == NULL || paysExactly(tx, 200000000ULL), "no transaction pays the destination anything but 2 DGB");
        ck(tx == NULL, "the build is refused");
    }
    else if (strcmp(name, "fits") == 0) {
        printf("[fits] 3,000 x 55,000 sat + 10 DGB; send 0.5 DGB\n");
        w = walletWith(3000, 55000ULL, 1000000000ULL);
        tx = BRWalletCreateTransaction(w, 50000000ULL, kDest);
        describe(tx);
        ck(tx != NULL && paysExactly(tx, 50000000ULL), "built, and pays exactly 0.5 DGB");
        ck(tx != NULL && BRTransactionVSize(tx) <= TX_MAX_SIZE, "inside the size cap");
    }
    else if (strcmp(name, "plain") == 0) {
        printf("[plain] 3 x 1 DGB; send 1 DGB\n");
        w = walletWith(3, 100000000ULL, 0);
        tx = BRWalletCreateTransaction(w, 100000000ULL, kDest);
        describe(tx);
        ck(tx != NULL && paysExactly(tx, 100000000ULL), "built, and pays exactly 1 DGB");
    }
    else { printf("unknown case %s\n", name); return 2; }

    if (tx) BRTransactionFree(tx);
    BRWalletFree(w);
    printf(g_fail == 0 ? "CASE PASS %s\n" : "CASE FAIL %s\n", name);
    return g_fail ? 1 : 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(g_seed, kMn, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    if (argc < 2) { printf("usage: %s small|tiny|fits|plain\n", argv[0]); return 2; }
    return runCase(argv[1]);
}
