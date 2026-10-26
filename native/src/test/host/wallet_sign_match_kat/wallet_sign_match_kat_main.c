// Host KAT: the wallet credits only outputs it can sign, and coin selection never picks one it cannot.
//
// WHAT IT PROVES.
//   [1] Not credited. A P2PKH-shaped scriptPubKey extended to eight elements, paying the wallet's
//       own legacy address hash, and a pay-to-pubkey output to the wallet's own key: neither has an
//       address (BRAddressFromScriptPubKey), so neither enters the balance or the UTXO set.
//   [2] Real funds still spend. With both of those registered beside a real 5 DGB receive, a 1 DGB
//       send builds, uses only the real coin, and signs.
//   [3] Still credited. P2PKH, P2WPKH and P2TR receives to wallet addresses are credited, and a
//       send spending all three signs.
//   [4] Defence in depth. An unsignable output forced into the UTXO set (as a crediting bug would
//       put it there) is skipped by coin selection: the send builds from the real coin and signs,
//       and a send that could only be paid with the unsignable coin is refused instead of built.
// Recorded red against core 91000fd: [1] credits 3 DGB for each script and [2] fails to sign.
//
// BRWallet.c is #included (for [4], which writes the UTXO set directly), so it is NOT on the clang
// line. Exit 0 = all passed.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "BRWallet.c"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRKey.h"

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static const char *kMn = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kSig[2] = {0x01, 0x51};   // placeholder scriptSig: "signed" by presence only
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;

static void fin(BRTransaction *tx) {
    uint8_t d[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

static BRTransaction *payScript(const uint8_t *spk, size_t spkLen, uint64_t amt, uint8_t tag) {
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    BRTransactionAddInput(tx, prev, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, spkLen);
    fin(tx);
    tx->blockHeight = 700000; tx->timestamp = 1784980000;
    return tx;
}

static BRTransaction *payAddr(const char *addr, uint64_t amt, uint8_t tag) {
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    return payScript(spk, l, amt, tag);
}

static BRWallet *mkWallet(void) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    return w;
}

// The 8-element script and the pay-to-pubkey script, both naming the wallet's first legacy key.
static size_t script8(BRWallet *w, uint8_t out[28]) {
    BRAddress leg; BRWalletUnusedAddrs(w, &leg, 1, 0, 0);
    size_t l = BRAddressScriptPubKey(out, 25, leg.s);
    out[l] = 0x61; out[l + 1] = 0x61; out[l + 2] = 0x51;                     // OP_NOP OP_NOP OP_1
    return l + 3;
}
static size_t scriptP2PK(BRWallet *w, uint8_t out[35]) {
    uint8_t pub[33]; size_t pl = BRBIP32PubKey(pub, sizeof(pub), g_mpk84, SEQUENCE_EXTERNAL_CHAIN, 0);
    BRKey k; BRKeySetPubKey(&k, pub, pl); BRAddress ka; BRKeyAddress(&k, ka.s, sizeof(ka));
    BRAddress leg; BRWalletUnusedAddrs(w, &leg, 1, 0, 0);
    ck(strcmp(ka.s, leg.s) == 0, "the pay-to-pubkey key is the wallet's first legacy key");
    out[0] = 33; memcpy(&out[1], pub, 33); out[34] = OP_CHECKSIG;
    return 35;
}

static int sendSigns(BRWallet *w, uint64_t amt, size_t *inCount) {
    BRAddress dst; BRWalletUnusedAddrs(w, &dst, 1, 1, 1);
    BRTransaction *s = BRWalletCreateTransaction(w, amt, dst.s);
    if (!s) { *inCount = 0; return -1; }
    *inCount = s->inCount;
    int r = BRWalletSignTransaction(w, s, 0, g_seed, sizeof(g_seed));
    BRTransactionFree(s);
    return r;
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(g_seed, kMn, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));
    size_t ins;

    printf("[1] scripts the wallet cannot sign are not credited\n");
    {
        BRWallet *w = mkWallet();
        uint8_t s8[28], pk[35]; size_t l8 = script8(w, s8), lpk = scriptP2PK(w, pk);
        char a[91] = {0};
        ck(BRAddressFromScriptPubKey(a, sizeof(a), s8, l8) == 0, "the 8-element script has no address");
        ck(BRAddressFromScriptPubKey(a, sizeof(a), pk, lpk) == 0, "the pay-to-pubkey script has no address");
        BRTransaction *t8 = payScript(s8, l8, 300000000ULL, 0x44), *tpk = payScript(pk, lpk, 300000000ULL, 0x45);
        BRWalletRegisterTransaction(w, t8);
        BRWalletRegisterTransaction(w, tpk);
        ck(BRWalletBalance(w) == 0, "neither enters the balance");
        ck(array_count(w->utxos) == 0, "neither enters the UTXO set");
        if (!BRWalletTransactionForHash(w, t8->txHash)) BRTransactionFree(t8);
        if (!BRWalletTransactionForHash(w, tpk->txHash)) BRTransactionFree(tpk);

        printf("[2] beside them, a real receive still spends\n");
        BRAddress r0; BRWalletUnusedAddrs(w, &r0, 1, 0, 1);
        BRWalletRegisterTransaction(w, payAddr(r0.s, 500000000ULL, 0x55));
        ck(BRWalletBalance(w) == 500000000ULL, "the balance is the real 5 DGB");
        ck(sendSigns(w, 100000000ULL, &ins) == 1 && ins == 1, "a 1 DGB send builds from the real coin and signs");
        BRWalletFree(w);
    }

    printf("[3] standard receives are still credited and signed\n");
    {
        BRWallet *w = mkWallet();
        BRAddress leg, wpkh, tr;
        BRWalletUnusedAddrs(w, &leg, 1, 0, 0);
        BRWalletUnusedAddrs(w, &wpkh, 1, 0, 1);
        BRWalletUnusedAddrs(w, &tr, 1, 0, 2);
        BRWalletRegisterTransaction(w, payAddr(leg.s, 100000000ULL, 0x61));
        BRWalletRegisterTransaction(w, payAddr(wpkh.s, 100000000ULL, 0x62));
        BRWalletRegisterTransaction(w, payAddr(tr.s, 100000000ULL, 0x63));
        ck(BRWalletBalance(w) == 300000000ULL, "P2PKH, P2WPKH and P2TR receives are credited");
        ck(sendSigns(w, 290000000ULL, &ins) == 1 && ins == 3, "a send spending all three signs");
        BRWalletFree(w);
    }

    printf("[4] coin selection skips an output it cannot sign\n");
    {
        BRWallet *w = mkWallet();
        uint8_t s8[28]; size_t l8 = script8(w, s8);
        BRTransaction *bad = payScript(s8, l8, 2000000000ULL, 0x71);           // 20 DGB, unsignable
        BRAddress r0; BRWalletUnusedAddrs(w, &r0, 1, 0, 1);
        BRWalletRegisterTransaction(w, payAddr(r0.s, 500000000ULL, 0x72));
        // Force the unsignable output in, ahead of the real coin, as a crediting bug would.
        pthread_mutex_lock(&w->lock);
        BRSetAdd(w->allTx, bad);
        array_insert(w->utxos, 0, ((BRUTXO) { bad->txHash, 0 }));
        w->balance += 2000000000ULL;
        pthread_mutex_unlock(&w->lock);
        ck(sendSigns(w, 100000000ULL, &ins) == 1 && ins == 1, "a 1 DGB send skips it, builds from the real coin and signs");
        ck(sendSigns(w, 1000000000ULL, &ins) == -1, "a 10 DGB send it alone could pay is refused, not built");
        pthread_mutex_lock(&w->lock);
        array_rm(w->utxos, 0); BRSetRemove(w->allTx, bad); w->balance -= 2000000000ULL;
        pthread_mutex_unlock(&w->lock);
        BRTransactionFree(bad);
        BRWalletFree(w);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
