// Host KAT: the wallet's amounts stay within DigiByte's money range.
//
// WHAT IT PROVES.
//   [1] MAX_MONEY is DigiByte's cap, 21,000,000,000 DGB (2.1e18 sat), and an output above the old
//       21,000,000 DGB value is still credited.
//   [2] An output above MAX_MONEY, outputs that together pass it, and an output value that would
//       wrap the balance are each refused at registration: not registered, not valid, the balance
//       unchanged. An output of exactly MAX_MONEY is accepted (the boundary).
//   [3] Loading saved transactions skips an out-of-range one the same way.
//   [4] Sums saturate: nine in-range MAX_MONEY receives (together past 2^64) leave the balance and
//       the spendable maximum at their ceiling, never wrapped to a small number.
// Recorded red against core 91000fd: [1] fails on the constant and [2] wraps the balance to 12,345 sat.
//
// BRWallet.c is #included (for the UTXO count), so it is NOT on the clang line. Exit 0 = all passed.
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
static BRMasterPubKey g_mpk;

static void fin(BRTransaction *tx) {
    uint8_t d[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A transaction paying `n` outputs of `amts` to the wallet's next receive address.
static BRTransaction *pay(BRWallet *w, const uint64_t *amts, size_t n, uint8_t tag, uint32_t height) {
    BRAddress a; BRWalletUnusedAddrs(w, &a, 1, 0, 1);
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    BRTransactionAddInput(tx, prev, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    for (size_t i = 0; i < n; i++) BRTransactionAddOutput(tx, amts[i], spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : 1784980000;
    return tx;
}

// Register; free the tx when the wallet did not take it. Returns the register result.
static int reg(BRWallet *w, BRTransaction *tx) {
    UInt256 h = tx->txHash;
    int r = BRWalletRegisterTransaction(w, tx);
    if (BRWalletTransactionForHash(w, h) != tx) BRTransactionFree(tx);
    return r;
}

// A refused transaction: not registered, not valid, balance and UTXO set untouched.
static void refused(BRWallet *w, const uint64_t *amts, size_t n, uint8_t tag, const char *what) {
    uint64_t before = BRWalletBalance(w); size_t utxos = array_count(w->utxos);
    BRTransaction *tx = pay(w, amts, n, tag, TX_UNCONFIRMED);
    int valid = BRWalletTransactionIsValid(w, tx);
    UInt256 h = tx->txHash;
    int r = BRWalletRegisterTransaction(w, tx);
    int held = BRWalletTransactionForHash(w, h) != NULL && w->transactions[array_count(w->transactions) - 1] == tx;
    char m[200];
    snprintf(m, sizeof(m), "%s: refused (registered=%d, valid=%d), balance %llu unchanged", what, r, valid,
             (unsigned long long)BRWalletBalance(w));
    ck(r == 0 && valid == 0 && !held && BRWalletBalance(w) == before && array_count(w->utxos) == utxos, m);
    ck(BRWalletTransactionForHash(w, h) == NULL, "  ... and is not kept (not even as an unconfirmed non-wallet tx)");
    if (BRWalletTransactionForHash(w, h) != tx) BRTransactionFree(tx);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMn, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    printf("[1] the money cap\n");
    ck(MAX_MONEY == 21000000000LL * 100000000LL, "MAX_MONEY is 21,000,000,000 DGB");
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
    uint64_t five = 500000000ULL;
    reg(w, pay(w, &five, 1, 0x11, 700000));
    ck(BRWalletBalance(w) == five, "a real 5 DGB receive is credited");
    uint64_t big = 21000000ULL * 100000000ULL + 1;                         // above the old cap
    ck(reg(w, pay(w, &big, 1, 0x12, 700001)) == 1 && BRWalletBalance(w) == five + big,
       "an output above 21,000,000 DGB (and in range) is credited");

    printf("[2] out-of-range transactions are refused\n");
    uint64_t base = BRWalletBalance(w);
    uint64_t over = (uint64_t)MAX_MONEY + 1;
    refused(w, &over, 1, 0x21, "one output of MAX_MONEY + 1");
    uint64_t pair[2] = { (uint64_t)MAX_MONEY, 1 };
    refused(w, pair, 2, 0x22, "two outputs together past MAX_MONEY");
    uint64_t wrap = (uint64_t)0 - five + 12345ULL;
    refused(w, &wrap, 1, 0x23, "an output that would wrap the balance");
    uint64_t wrap2[2] = { (uint64_t)MAX_MONEY, UINT64_MAX - (uint64_t)MAX_MONEY + 2 };
    refused(w, wrap2, 2, 0x24, "outputs whose sum wraps past 2^64");
    ck(BRWalletBalance(w) == base, "the balance is what it was");
    uint64_t edge = (uint64_t)MAX_MONEY;
    ck(reg(w, pay(w, &edge, 1, 0x25, 700002)) == 1 && BRWalletBalance(w) == base + edge,
       "one output of exactly MAX_MONEY is accepted");
    BRWalletFree(w);

    printf("[3] loading skips an out-of-range transaction\n");
    {
        BRWallet *tmp = BRWalletNew(NULL, 0, g_mpk);          // only to derive the same addresses
        BRTransaction *txs[2] = { pay(tmp, &five, 1, 0x31, 700000), pay(tmp, &wrap, 1, 0x32, 700001) };
        BRWalletFree(tmp);
        BRWallet *lw = BRWalletNew(txs, 2, g_mpk);
        ck(BRWalletBalance(lw) == five, "the loaded balance is the real 5 DGB");
        ck(BRWalletTransactionForHash(lw, txs[1]->txHash) == NULL, "the out-of-range transaction is not loaded");
        BRWalletFree(lw);
        BRTransactionFree(txs[1]);
    }

    printf("[4] wallet sums saturate\n");
    {
        BRWallet *sw = BRWalletNew(NULL, 0, g_mpk);
        uint64_t prev = 0; int monotone = 1;
        for (int i = 0; i < 9; i++) {
            reg(sw, pay(sw, &edge, 1, (uint8_t)(0x40 + i), 700000 + i));
            if (BRWalletBalance(sw) < prev) monotone = 0;
            prev = BRWalletBalance(sw);
        }
        ck(monotone, "the balance never decreases as in-range receives are added");
        ck(BRWalletBalance(sw) == UINT64_MAX, "nine MAX_MONEY receives leave the balance at its ceiling, not wrapped");
        ck(BRWalletMaxOutputAmount(sw) > 8 * (uint64_t)MAX_MONEY, "the spendable maximum does not wrap either");
        BRWalletFree(sw);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
