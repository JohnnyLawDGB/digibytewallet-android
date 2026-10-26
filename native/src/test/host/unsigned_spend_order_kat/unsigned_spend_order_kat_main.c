// Host KAT: an unsigned spend of a wallet coin never counts, whatever order or route it arrives by, and
// checking a peer's transaction cannot exhaust a peer thread's stack.
//
// WHAT IT PROVES.
//   [1] Arrival order. A junk-signature unconfirmed spend T of coin C registered BEFORE C is known
//       (so nothing marks its input the wallet's yet) does not spend C once C arrives: C stays unspent,
//       the balance is whole, and a send spends C and not T's output.
//   [2] Address growth. The same when C pays a wallet address beyond the derived window: C becomes the
//       wallet's only after the window grows past it.
//   [3] Load from storage. A saved junk-signature unconfirmed spend of a saved coin does not spend it
//       in the loaded wallet.
//   [4] Stack. An unconfirmed tx with one wallet-owned input (P2PKH, then P2WPKH) and 60,000 other
//       inputs is checked on a thread with a 1 MiB stack (Android's default) and refused, ASan-clean.
//   [5] Conflicting spends. A junk-signature spend F of C, registered before C, and the wallet's own
//       validly signed send S of C: S is the spend of C, F is invalid, in either order.
//   [6] Validity walk. BRWalletTransactionIsValid on a receive whose unconfirmed ancestry is a
//       30,000-long relayed chain answers on a 1 MiB stack (bounded walk) instead of recursing through it.
// Uses only the wallet's public API, so the same main builds against an earlier core:
//   UNSIGNED_SPEND_CORE_DIR=<core checkout> run.sh  -- recorded red against core ee48b70: [1] and [3]
//   mark C spent, [2] marks C spent, and [4] overflows the stack in BRTransactionVerifyInput
//   (and against 051e504, [6] overflows the stack in BRWalletTransactionIsValid).
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static const char *kMn = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kSig[2] = {0x01, 0x51};
static BRMasterPubKey g_mpk;

static void fin(BRTransaction *tx) {
    size_t n = BRTransactionSerialize(tx, NULL, 0); uint8_t *d = malloc(n);
    BRTransactionSerialize(tx, d, n); BRTransaction *t = BRTransactionParse(d, n); free(d);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}
static size_t spkOf(BRWallet *w, int internal, int type, uint8_t spk[64]) {
    BRAddress a; BRWalletUnusedAddrs(w, &a, 1, internal, type);
    return BRAddressScriptPubKey(spk, 64, a.s);
}
// C: a confirmed 1 DGB receive to spk (a foreign input).
static BRTransaction *coin(const uint8_t *spk, size_t l, uint8_t tag) {
    BRTransaction *C = BRTransactionNew(); UInt256 p; memset(p.u8, tag, 32);
    BRTransactionAddInput(C, p, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(C, 100000000ULL, spk, l); fin(C);
    C->blockHeight = 700000; C->timestamp = 1784980000;
    return C;
}
// T: an unconfirmed junk-signature spend of C:0 paying 0.99 DGB to spk.
static BRTransaction *junk(BRTransaction *C, const uint8_t *spk, size_t l) {
    BRTransaction *T = BRTransactionNew();
    BRTransactionAddInput(T, C->txHash, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(T, 99000000ULL, spk, l); fin(T);
    T->blockHeight = TX_UNCONFIRMED;
    return T;
}
static void whole(BRWallet *w, BRTransaction *C, BRTransaction *T, const char *label) {
    char m[200];
    snprintf(m, sizeof(m), "%s: C is unspent and the balance is whole", label);
    ck(BRWalletOutpointSpent(w, C->txHash, 0) == 0 && BRWalletBalance(w) == 100000000ULL, m);
    BRAddress d; BRWalletUnusedAddrs(w, &d, 1, 1, 1);
    BRTransaction *s = BRWalletCreateTransaction(w, 50000000ULL, d.s);
    snprintf(m, sizeof(m), "%s: a send spends C, not the unsigned spend's output", label);
    ck(s && s->inCount == 1 && UInt256Eq(s->inputs[0].txHash, C->txHash) && ! UInt256Eq(s->inputs[0].txHash, T->txHash), m);
    if (s) BRTransactionFree(s);
}

static BRWallet *g_w; static BRTransaction *g_T; static int g_r = -1;
static void *regThread(void *a) { (void)a; g_r = BRWalletRegisterTransaction(g_w, g_T); return NULL; }
static void *validThread(void *a) { (void)a; g_r = BRWalletTransactionIsValid(g_w, g_T); return NULL; }
static void onSmallStack(void *(*fn)(void *)) {
    pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setstacksize(&at, 1 << 20);
    pthread_t th; g_r = -1;
    pthread_create(&th, &at, fn, NULL); pthread_join(th, NULL);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMn, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    uint8_t spk[64], spk2[64];

    printf("[1] the unsigned spend arrives before the coin\n");
    {
        BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
        size_t l = spkOf(w, 0, 1, spk), l2 = spkOf(w, 1, 1, spk2);
        BRTransaction *C = coin(spk, l, 0x11), *T = junk(C, spk2, l2);
        BRWalletRegisterTransaction(w, T);
        BRWalletRegisterTransaction(w, C);
        whole(w, C, T, "after C arrives");
        BRWalletFree(w);
    }

    printf("[2] the coin's address becomes the wallet's later\n");
    {
        BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
        size_t l2 = spkOf(w, 1, 1, spk2);
        // the external P2WPKH address 150 places in: beyond the window a fresh wallet derives
        BRAddress far[151]; BRWallet *probe = BRWalletNew(NULL, 0, g_mpk);
        BRWalletUnusedAddrs(probe, far, 151, 0, 1);
        uint8_t fspk[64]; size_t fl = BRAddressScriptPubKey(fspk, sizeof(fspk), far[150].s);
        BRWalletFree(probe);
        ck(! BRWalletContainsAddress(w, far[150].s), "setup: the address is outside the wallet's window");
        BRTransaction *C = coin(fspk, fl, 0x21), *T = junk(C, spk2, l2);
        BRWalletRegisterTransaction(w, C);                       // not yet the wallet's: kept as history only
        BRWalletRegisterTransaction(w, T);
        BRWalletUnusedAddrs(w, NULL, 200, 0, 1);                 // the window grows past it
        BRTransaction *C2 = coin(fspk, fl, 0x21);                // C delivered again (e.g. by a block)
        if (BRWalletRegisterTransaction(w, C2) == 0 || BRWalletTransactionForHash(w, C2->txHash) != C2) BRTransactionFree(C2);
        ck(BRWalletContainsAddress(w, far[150].s), "setup: the window now holds the address");
        ck(BRWalletOutpointSpent(w, C->txHash, 0) == 0, "C is not spent by the unsigned spend");
        BRWalletFree(w);
    }

    printf("[3] loaded from storage\n");
    {
        BRWallet *tmp = BRWalletNew(NULL, 0, g_mpk);
        size_t l = spkOf(tmp, 0, 1, spk), l2 = spkOf(tmp, 1, 1, spk2);
        BRWalletFree(tmp);
        BRTransaction *C = coin(spk, l, 0x31), *T = junk(C, spk2, l2);
        BRTransaction *saved[2] = { C, T };
        BRWallet *w = BRWalletNew(saved, 2, g_mpk);
        whole(w, C, T, "after loading");
        BRWalletFree(w);
    }

    printf("[4] a 60,000-input tx checked on a 1 MiB stack\n");
    for (int type = 0; type <= 1; type++) {
        g_w = BRWalletNew(NULL, 0, g_mpk);
        size_t l = spkOf(g_w, 0, type, spk);
        BRTransaction *F = BRTransactionNew(); UInt256 p; memset(p.u8, 0x41, 32);
        BRTransactionAddInput(F, p, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
        BRTransactionAddOutput(F, 100000000ULL, spk, l); fin(F); F->blockHeight = TX_UNCONFIRMED;
        BRWalletRegisterTransaction(g_w, F);
        g_T = BRTransactionNew();
        uint8_t sig[1 + 72 + 1 + 33]; memset(sig, 0x30, sizeof(sig)); sig[0] = 72; sig[73] = 33;
        if (type == 0) BRTransactionAddInput(g_T, F->txHash, 0, 0, NULL, 0, sig, sizeof(sig), kSig, 0, 0xffffffff);
        else           BRTransactionAddInput(g_T, F->txHash, 0, 0, NULL, 0, kSig, 0, sig, sizeof(sig), 0xffffffff);
        for (uint32_t i = 1; i < 60000; i++) {
            UInt256 q; memset(q.u8, 0x42, 32); q.u32[0] = i;
            BRTransactionAddInput(g_T, q, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
        }
        BRTransactionAddOutput(g_T, 1000, spk, l); fin(g_T); g_T->blockHeight = TX_UNCONFIRMED;
        onSmallStack(regThread);
        ck(g_r == 0, type == 0 ? "P2PKH input: refused, the stack intact" : "P2WPKH input: refused, the stack intact");
        if (BRWalletTransactionForHash(g_w, g_T->txHash) != g_T) BRTransactionFree(g_T);
        BRWalletFree(g_w);
    }

    printf("[5] a forged and a signed spend of the same coin\n");
    for (int order = 0; order < 2; order++) {
        BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
        size_t l = spkOf(w, 0, 1, spk), l2 = spkOf(w, 1, 1, spk2);
        BRTransaction *C = coin(spk, l, 0x51), *F = junk(C, spk2, l2);
        if (order == 0) BRWalletRegisterTransaction(w, F);           // forged first, before C
        BRWalletRegisterTransaction(w, C);
        BRAddress d; BRWalletUnusedAddrs(w, &d, 1, 1, 1);
        BRTransaction *S = BRWalletCreateTransaction(w, 50000000ULL, d.s);
        int signedOk = S && BRWalletSignTransaction(w, S, 0, seed, sizeof(seed)) == 1;
        if (signedOk) S->timestamp = 1784990000;
        int regS = signedOk && BRWalletRegisterTransaction(w, S) == 1;
        if (order == 1) BRWalletRegisterTransaction(w, F);           // forged after the signed send
        ck(regS && BRWalletTransactionIsValid(w, S) && ! BRWalletTransactionIsValid(w, F),
           order == 0 ? "forged first: the signed send is valid, the forged one is not"
                      : "signed first: the signed send is valid, the forged one is not");
        ck(BRWalletBalance(w) == 100000000ULL - BRWalletAmountSentByTx(w, S) + BRWalletAmountReceivedFromTx(w, S),
           "  ... and the balance is C less what the signed send spends");
        BRWalletFree(w);
    }

    printf("[6] the validity walk over a long relayed chain\n");
    {
        g_w = BRWalletNew(NULL, 0, g_mpk);
        uint8_t other[22] = {0x00, 0x14}; memset(&other[2], 0x66, 20);
        UInt256 prev; memset(prev.u8, 0x61, 32);
        for (int i = 0; i < 30000; i++) {                             // a foreign unconfirmed chain
            BRTransaction *t = BRTransactionNew();
            BRTransactionAddInput(t, prev, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
            BRTransactionAddOutput(t, 100000000ULL, other, sizeof(other)); fin(t);
            t->blockHeight = TX_UNCONFIRMED;
            prev = t->txHash;
            if (BRWalletRegisterTransaction(g_w, t) == 0 && BRWalletTransactionForHash(g_w, t->txHash) != t) BRTransactionFree(t);
        }
        size_t l = spkOf(g_w, 0, 1, spk);
        g_T = BRTransactionNew();                                     // a receive at its tip
        BRTransactionAddInput(g_T, prev, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
        BRTransactionAddOutput(g_T, 90000000ULL, spk, l); fin(g_T); g_T->blockHeight = TX_UNCONFIRMED;
        BRWalletRegisterTransaction(g_w, g_T);
        onSmallStack(validThread);
        ck(g_r == 0 || g_r == 1, "BRWalletTransactionIsValid answers on a 1 MiB stack");
        BRWalletFree(g_w);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
