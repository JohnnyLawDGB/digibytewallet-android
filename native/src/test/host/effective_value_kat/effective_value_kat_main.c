// Host KAT: coin selection does not spend a coin worth less than the fee its own input adds (4.1.3 G3).
//
// A confirmed P2WPKH coin of 2,940 sat costs about 68 vbytes x 100 sat/vbyte = 6,800 sat to spend. Selection took
// coins in wallet order, so an old dust coin someone sent was spent first and the send paid more fee for it than
// it brought in. Selection now takes economic coins first and an uneconomic one only as a last resort.
//
// CASES (one per process, argv[1]):
//   skip      a 2,940-sat coin (oldest), then a 1 DGB coin; send 0.5 DGB. The dust is not an input.
//             (-DEFFECTIVE_VALUE_UNFIXED spends it first: this case fails there)
//   order     BRWalletSelectableUTXOs lists the 1 DGB coin before the dust. (fails there too)
//   fallback  the same wallet sends everything BRWalletMaxOutputAmount offers: built, paying exactly that. The
//             maximum leaves the dust out (spending it would lower what arrives), so the dust is not an input.
//             (-DEFFECTIVE_VALUE_UNFIXED counts the dust into the maximum and spends it: this case fails there)
//   plain     3 x 1 DGB, send 1 DGB: built, pays exactly 1 DGB (control).
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
static UInt256 g_dustTx;

static void fin(BRTransaction *tx) {
    uint8_t d[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

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

// The dust coin (oldest) and then `big` coins of `each`.
static BRWallet *walletWith(uint64_t dust, size_t big, uint64_t each) {
    BRWallet *w0 = BRWalletNew(NULL, 0, g_mpk);
    BRAddress a; BRWalletUnusedAddrs(w0, &a, 1, 0, 1);
    BRWalletFree(w0);
    BRTransaction *txs[8];
    size_t k = 0;
    if (dust) { txs[k] = receive(a.s, 1, dust, 700000); g_dustTx = txs[k]->txHash; k++; }
    for (size_t i = 0; i < big; i++) txs[k++] = receive(a.s, 10 + (uint32_t)i, each, 700001 + (uint32_t)i);
    BRWallet *w = BRWalletNew(txs, k, g_mpk);
    BRWalletSetFeePerKb(w, 100000);   // 100 sat/vbyte, the DigiByte default
    return w;
}

static int spendsDust(const BRTransaction *tx) {
    for (size_t i = 0; tx && i < tx->inCount; i++) if (UInt256Eq(tx->inputs[i].txHash, g_dustTx)) return 1;
    return 0;
}

static int paysExactly(const BRTransaction *tx, uint64_t amt) {
    int hit = 0;
    for (size_t i = 0; tx && i < tx->outCount; i++)
        if (strcmp(tx->outputs[i].address, kDest) == 0) { if (tx->outputs[i].amount == amt) hit++; else return 0; }
    return hit == 1;
}

static int runCase(const char *name) {
    BRWallet *w = NULL;
    BRTransaction *tx = NULL;

    if (strcmp(name, "skip") == 0) {
        printf("[skip] 2,940-sat coin + 1 DGB; send 0.5 DGB\n");
        w = walletWith(2940, 1, 100000000ULL);
        tx = BRWalletCreateTransaction(w, 50000000ULL, kDest);
        printf("  result: %s, %zu input(s)\n", tx ? "built" : "refused", tx ? tx->inCount : 0);
        ck(tx != NULL && paysExactly(tx, 50000000ULL), "built, and pays exactly 0.5 DGB");
        ck(tx != NULL && ! spendsDust(tx), "the dust coin is not an input");
    }
    else if (strcmp(name, "order") == 0) {
        printf("[order] BRWalletSelectableUTXOs on 2,940-sat coin + 1 DGB\n");
        w = walletWith(2940, 1, 100000000ULL);
        BRUTXO u[4];
        size_t n = BRWalletSelectableUTXOs(w, u, 4);
        printf("  listed %zu\n", n);
        ck(n == 2, "both coins are listed (the dust is still selectable as a last resort)");
        ck(n == 2 && UInt256Eq(u[1].hash, g_dustTx), "the dust coin is listed last");
    }
    else if (strcmp(name, "fallback") == 0) {
        printf("[fallback] 2,940-sat coin + 1 DGB; send the maximum\n");
        w = walletWith(2940, 1, 100000000ULL);
        uint64_t max = BRWalletMaxOutputAmount(w);
        tx = max ? BRWalletCreateTransaction(w, max, kDest) : NULL;
        printf("  max %llu, result: %s, %zu input(s)\n", (unsigned long long)max, tx ? "built" : "refused", tx ? tx->inCount : 0);
        ck(tx != NULL && paysExactly(tx, max), "the maximum send is built and pays exactly the maximum");
        ck(tx != NULL && ! spendsDust(tx), "the dust coin is not an input (it would lower what arrives)");
    }
    else if (strcmp(name, "plain") == 0) {
        printf("[plain] 3 x 1 DGB; send 1 DGB\n");
        w = walletWith(0, 3, 100000000ULL);
        tx = BRWalletCreateTransaction(w, 100000000ULL, kDest);
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
    if (argc < 2) { printf("usage: %s skip|order|fallback|plain\n", argv[0]); return 2; }
    return runCase(argv[1]);
}
