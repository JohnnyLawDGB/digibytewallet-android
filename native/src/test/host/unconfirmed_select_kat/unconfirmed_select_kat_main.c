// Host KAT: coin selection spends confirmed coins first, then the wallet's own unconfirmed change,
// and never an unconfirmed coin received from someone else.
//
// WHAT IT PROVES.
//   [1] A foreign unconfirmed receive is not spent: beside 1 DGB confirmed, a relayed unconfirmed
//       1,000 DGB "receive" does not fund a 10 DGB send (refused) and is not an input of a 0.5 DGB
//       send; the spendable maximum leaves it out. Once confirmed it is spent normally.
//   [2] Own change stays spendable: after a 1 DGB send (signed, registered, unconfirmed) its
//       unconfirmed change funds the next send, which signs.
//   [3] Confirmed first: with that unconfirmed change and a newer confirmed coin both enough, the
//       send takes the confirmed coin. (A guard: the UTXO order already tends to put confirmed coins
//       first; the selection pass now makes it a rule.)
//   [4] A relayed unconfirmed tx that spends a foreign unconfirmed receive, or that pays out more
//       than it spends, is not "own": its outputs are not selected.
// Recorded red against core dea3e00 (before this change): [1] funds the 10 DGB send from the
// unconfirmed receive and counts it in the maximum, and [4] selects the relayed chain's outputs.
//
// BRWallet.c is #included, so it is NOT on the clang line. Exit 0 = all passed.
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
static uint32_t g_ts = 1784980000;

static void fin(BRTransaction *tx) {
    uint8_t d[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A tx spending (prevHash, prevN) -- someone else's coin unless the caller says otherwise -- that pays
// `amt` to the wallet's next receive address.
static BRTransaction *payFrom(BRWallet *w, UInt256 prevHash, uint32_t prevN, uint64_t amt, uint32_t height) {
    BRAddress a; BRWalletUnusedAddrs(w, &a, 1, 0, 1);
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    BRTransactionAddInput(tx, prevHash, prevN, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : g_ts++;
    return tx;
}
static BRTransaction *receive(BRWallet *w, uint8_t tag, uint64_t amt, uint32_t height) {
    UInt256 h; memset(h.u8, tag, 32);
    return payFrom(w, h, 0, amt, height);
}

static int spentInput(const BRTransaction *s, UInt256 h) {
    for (size_t i = 0; s && i < s->inCount; i++) if (UInt256Eq(s->inputs[i].txHash, h)) return 1;
    return 0;
}

static BRTransaction *build(BRWallet *w, uint64_t amt) {
    BRAddress dst; BRWalletUnusedAddrs(w, &dst, 1, 1, 1);
    return BRWalletCreateTransaction(w, amt, dst.s);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(g_seed, kMn, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    BRTransaction *s;

    printf("[1] a foreign unconfirmed receive is not spent\n");
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
    BRTransaction *real = receive(w, 0x11, 100000000ULL, 700000);
    BRWalletRegisterTransaction(w, real);
    BRTransaction *fake = receive(w, 0x12, 100000000000ULL, TX_UNCONFIRMED);
    BRWalletRegisterTransaction(w, fake);
    ck(BRWalletBalance(w) == 100100000000ULL, "the balance shows both (display is unchanged)");
    s = build(w, 1000000000ULL);
    ck(s == NULL, "a 10 DGB send only the unconfirmed receive could pay is refused");
    if (s) BRTransactionFree(s);
    s = build(w, 50000000ULL);
    ck(s && s->inCount == 1 && spentInput(s, real->txHash), "a 0.5 DGB send takes the confirmed coin only");
    if (s) BRTransactionFree(s);
    ck(BRWalletMaxOutputAmount(w) < 100000000ULL, "the spendable maximum leaves the unconfirmed receive out");
    {
        UInt256 h = fake->txHash;
        BRWalletUpdateTransactions(w, &h, 1, 700001, g_ts++);
        s = build(w, 1000000000ULL);
        ck(s && spentInput(s, fake->txHash), "once confirmed, it is spent normally");
        if (s) BRTransactionFree(s);
    }
    BRWalletFree(w);

    printf("[2] own unconfirmed change stays spendable\n");
    w = BRWalletNew(NULL, 0, g_mpk);
    BRWalletRegisterTransaction(w, receive(w, 0x21, 200000000ULL, 700000));
    s = build(w, 100000000ULL);
    ck(s && BRWalletSignTransaction(w, s, 0, g_seed, sizeof(g_seed)) == 1, "a 1 DGB send signs");
    BRTransaction *send1 = s;
    ck(BRWalletRegisterTransaction(w, send1) == 1, "the send is registered (unconfirmed)");
    s = build(w, 50000000ULL);
    ck(s && spentInput(s, send1->txHash) && BRWalletSignTransaction(w, s, 0, g_seed, sizeof(g_seed)) == 1,
       "a 0.5 DGB send spends the unconfirmed change and signs");
    if (s) BRTransactionFree(s);

    printf("[3] confirmed coins are taken first\n");
    BRTransaction *later = receive(w, 0x31, 100000000ULL, 700010);
    BRWalletRegisterTransaction(w, later);
    s = build(w, 50000000ULL);
    ck(s && s->inCount == 1 && spentInput(s, later->txHash), "the confirmed coin is taken over the unconfirmed change");
    if (s) BRTransactionFree(s);
    BRWalletFree(w);

    printf("[4] relayed chains are not own\n");
    w = BRWalletNew(NULL, 0, g_mpk);
    BRTransaction *real4 = receive(w, 0x41, 100000000ULL, 700000);
    BRWalletRegisterTransaction(w, real4);
    BRTransaction *root = receive(w, 0x42, 100000000000ULL, TX_UNCONFIRMED);
    BRWalletRegisterTransaction(w, root);
    BRTransaction *child = payFrom(w, root->txHash, 0, 90000000000ULL, TX_UNCONFIRMED);
    BRWalletRegisterTransaction(w, child);
    BRTransaction *inflate = payFrom(w, real4->txHash, 0, 50000000000ULL, TX_UNCONFIRMED);   // spends 1, pays 500
    BRWalletRegisterTransaction(w, inflate);
    s = build(w, 1000000000ULL);
    ck(s == NULL || (! spentInput(s, child->txHash) && ! spentInput(s, inflate->txHash) && ! spentInput(s, root->txHash)),
       "neither a child of a foreign receive nor a tx paying out more than it spends is selected");
    if (s) BRTransactionFree(s);
    BRWalletFree(w);

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
