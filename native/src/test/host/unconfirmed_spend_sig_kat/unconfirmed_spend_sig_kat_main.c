// Host KAT: an unconfirmed transaction that spends a wallet coin is registered only when it validly
// signs that coin; the wallet's own sends, other holders of the phrase and blocks are unaffected.
//
// WHAT IT PROVES.
//   [1] BRTransactionVerifyInput accepts a real signature and refuses a tampered one, for each type
//       the wallet signs: P2PKH, P2SH-P2WPKH (BIP49), P2WPKH, P2TR key path.
//   [2] A relayed unconfirmed tx with a junk scriptSig that spends the wallet's confirmed coin C is
//       refused and not kept: C stays unspent, the balance is unchanged, and a send spends C and signs.
//   [3] The wallet's own signed sends register at once through the checked path: P2WPKH, legacy
//       P2PKH, P2TR, and a DigiDollar transfer (P2TR token input + P2WPKH fee input).
//   [4] A validly signed spend from another wallet holding the same phrase registers unconfirmed.
//   [5] A tampered copy of a valid spend (an output changed) is refused.
//   [6] A P2TR spend beside an input whose prevout the wallet does not hold cannot be checked (BIP341
//       commits every prevout) and is refused while unconfirmed; the same tx registers once confirmed,
//       and through BRWalletRegisterTransactionTrusted (a block's tx list proven against its header).
//   [7] A junk-signature spend in a block (confirmed, or delivered through the trusted path) registers
//       as before: confirmed transactions are not second-guessed.
//   [8] One selection's own-unconfirmed walk is bounded in total, not per coin: 900 coins of a forged
//       chain (put in through the trusted path) cost at most SELECT_WALK_BUDGET input visits.
// Recorded red against core 887cc82: [2] registers the junk-signature spend, C is spent and the send
// fails or spends the forged change; [5] registers the tampered copy; [8] visits ~900x more.
//
// BRWallet.c is #included (WALLET_KAT_COUNT_WALK and wallet internals), so it is NOT on the clang line.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "BRWallet.c"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static const char *kMn = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kSig[2] = {0x01, 0x51};   // junk scriptSig
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;
static uint32_t g_ts = 1784980000;

static BRTransaction *wire(const BRTransaction *tx) {   // what a peer would hand over
    uint8_t d[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    return BRTransactionParse(d, n);
}
static void fin(BRTransaction *tx) {
    BRTransaction *t = wire(tx);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}
static BRWallet *mkWallet(void) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    return w;
}
// A confirmed (or not) receive of `amt` to the wallet address of `type` (0 legacy, 1 P2WPKH, 2 P2TR),
// funded by a foreign input. Deterministic in (tag, type), so two wallets on one phrase build the same tx.
static BRTransaction *receive(BRWallet *w, int type, uint64_t amt, uint8_t tag, uint32_t height) {
    BRAddress a; BRWalletUnusedAddrs(w, &a, 1, 0, type);
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    BRTransactionAddInput(tx, prev, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : 1784980000;
    return tx;
}
// A tx spending (h, n) with a junk scriptSig, paying `amt` back to the wallet.
static BRTransaction *junkSpend(BRWallet *w, UInt256 h, uint32_t n, uint64_t amt, uint32_t height) {
    BRAddress a; BRWalletUnusedAddrs(w, &a, 1, 1, 1);
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    BRTransactionAddInput(tx, h, n, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : g_ts++;
    return tx;
}
// Build + sign a send of amt in w, and return it as a peer would deliver it (unconfirmed).
static BRTransaction *signedSend(BRWallet *w, uint64_t amt) {
    BRAddress d; BRWalletUnusedAddrs(w, &d, 1, 1, 1);
    BRTransaction *s = BRWalletCreateTransaction(w, amt, d.s);
    if (!s) return NULL;
    int ok = BRWalletSignTransaction(w, s, 0, g_seed, sizeof(g_seed));
    BRTransaction *t = ok ? wire(s) : NULL;
    BRTransactionFree(s);
    return t;
}
// Register through the checked path; free when not taken. Returns 1 when the wallet holds it.
static int regChecked(BRWallet *w, BRTransaction *tx) {
    UInt256 h = tx->txHash;
    BRWalletRegisterTransaction(w, tx);
    int held = BRWalletTransactionForHash(w, h) == tx;
    if (!held) BRTransactionFree(tx);
    return held;
}
// Attach each input's prevout (script, amount) from `prevs`, as BRTransactionVerifyInput expects.
static void attach(BRTransaction *tx, BRTransaction *const prevs[], size_t n) {
    for (size_t i = 0; i < tx->inCount; i++)
        for (size_t k = 0; k < n; k++)
            if (UInt256Eq(prevs[k]->txHash, tx->inputs[i].txHash)) {
                BRTxOutput *o = &prevs[k]->outputs[tx->inputs[i].index];
                BRTxInputSetScript(&tx->inputs[i], o->script, o->scriptLen);
                tx->inputs[i].amount = o->amount;
            }
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(g_seed, kMn, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));

    printf("[1] BRTransactionVerifyInput, per script type\n");
    {
        BRKey key; BRBIP32PrivKey(&key, g_seed, sizeof(g_seed), SEQUENCE_EXTERNAL_CHAIN, 7);
        uint8_t pk[33]; BRKeyPubKey(&key, pk, sizeof(pk));
        UInt160 h = BRKeyHash160(&key);
        uint8_t p2pkh[25] = {OP_DUP, OP_HASH160, 20}; memcpy(&p2pkh[3], h.u8, 20); p2pkh[23] = OP_EQUALVERIFY; p2pkh[24] = OP_CHECKSIG;
        uint8_t p2wpkh[22] = {OP_0, 20}; memcpy(&p2wpkh[2], h.u8, 20);
        uint8_t redeem[22] = {OP_0, 20}; memcpy(&redeem[2], h.u8, 20);
        UInt160 rh; BRHash160(&rh, redeem, sizeof(redeem));
        uint8_t p2sh[23] = {OP_HASH160, 20}; memcpy(&p2sh[2], rh.u8, 20); p2sh[22] = OP_EQUAL;
        uint8_t p2tr[34] = {OP_1, 32}; BRKeyTaprootOutputKey(&key, &p2tr[2]);
        struct { const char *name; const uint8_t *s; size_t l; } types[] = {
            { "P2PKH", p2pkh, sizeof(p2pkh) }, { "P2SH-P2WPKH", p2sh, sizeof(p2sh) },
            { "P2WPKH", p2wpkh, sizeof(p2wpkh) }, { "P2TR", p2tr, sizeof(p2tr) } };
        for (size_t t = 0; t < 4; t++) {
            UInt256 prev; memset(prev.u8, (uint8_t)(0x90 + t), 32);
            BRTransaction *tx = BRTransactionNew();
            BRTransactionAddInput(tx, prev, 1, 50000000ULL, types[t].s, types[t].l, NULL, 0, NULL, 0, TXIN_SEQUENCE);
            BRTransactionAddOutput(tx, 40000000ULL, p2wpkh, sizeof(p2wpkh));
            int signedOk = BRTransactionSign(tx, 0, &key, 1);
            BRTransaction *w1 = wire(tx);
            char m[128];
            if (w1) { w1->inputs[0].amount = 50000000ULL; BRTxInputSetScript(&w1->inputs[0], types[t].s, types[t].l); }
            snprintf(m, sizeof(m), "%s: a real signature verifies", types[t].name);
            ck(signedOk && w1 && BRTransactionVerifyInput(w1, 0) == 1, m);
            if (w1) {
                w1->outputs[0].amount -= 1000;                      // tamper: an output changes
                snprintf(m, sizeof(m), "%s: the signature does not cover a changed output", types[t].name);
                ck(BRTransactionVerifyInput(w1, 0) == 0, m);
                w1->outputs[0].amount += 1000;
                w1->inputs[0].amount = 50000001ULL;                 // tamper: the prevout amount (segwit commits it)
                if (t > 0) {
                    snprintf(m, sizeof(m), "%s: the signature does not cover another amount", types[t].name);
                    ck(BRTransactionVerifyInput(w1, 0) == 0, m);
                }
                BRTransactionFree(w1);
            }
            BRTransactionFree(tx);
        }
        BRKeyClean(&key);
    }

    printf("[2] a junk-signature spend of a wallet coin is refused while unconfirmed\n");
    BRWallet *w = mkWallet();
    BRTransaction *C = receive(w, 1, 100000000ULL, 0x11, 700000);
    BRWalletRegisterTransaction(w, C);
    BRTransaction *T = junkSpend(w, C->txHash, 0, 99000000ULL, TX_UNCONFIRMED);
    ck(regChecked(w, T) == 0, "the junk-signature spend is refused and not kept");
    ck(BRWalletOutpointSpent(w, C->txHash, 0) == 0 && BRWalletBalance(w) == 100000000ULL, "C stays unspent; balance unchanged");
    {
        BRTransaction *s = signedSend(w, 50000000ULL);
        ck(s && s->inCount == 1 && UInt256Eq(s->inputs[0].txHash, C->txHash), "a send spends C and signs");
        printf("[3] the wallet's own sends register through the checked path\n");
        ck(s && regChecked(w, s) == 1, "own P2WPKH send registers unconfirmed");
    }
    BRWalletFree(w);
    {
        BRWallet *w2 = mkWallet();
        BRWalletRegisterTransaction(w2, receive(w2, 0, 100000000ULL, 0x21, 700000));
        BRTransaction *s = signedSend(w2, 50000000ULL);
        ck(s && regChecked(w2, s) == 1, "own legacy P2PKH send registers unconfirmed");
        BRWalletFree(w2);
        w2 = mkWallet();
        BRWalletRegisterTransaction(w2, receive(w2, 2, 100000000ULL, 0x22, 700000));
        BRWalletRegisterTransaction(w2, receive(w2, 1, 100000000ULL, 0x23, 700000));
        s = signedSend(w2, 150000000ULL);
        ck(s && s->inCount == 2 && regChecked(w2, s) == 1, "own P2TR + P2WPKH send registers unconfirmed");
        BRWalletFree(w2);
    }
    {
        BRWallet *wd = mkWallet();
        BRAddress ta = BRWalletReceiveAddress(wd, 2);
        uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
        BRTransaction *dd = BRTransactionNew();
        dd->version = 0x02000770;
        UInt256 ph; memset(ph.u8, 0x31, 32);
        BRTransactionAddInput(dd, ph, 0, 0, spk, spkLen, kSig, 0, kSig, 0, 0xffffffff);
        BRTransactionAddOutput(dd, 0, spk, spkLen);
        uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x10,0x27};
        BRTransactionAddOutput(dd, 0, orr, sizeof(orr));
        fin(dd); dd->blockHeight = 23700000; dd->timestamp = 1784980000;
        BRWalletRegisterTransaction(wd, dd);
        BRWalletRegisterTransaction(wd, receive(wd, 1, 100000000ULL, 0x32, 23700000));
        uint8_t rk[32]; memset(rk, 0x5a, 32); rk[0] = 0x02;
        BRTransaction *t = BRWalletCreateDigiDollarTransfer(wd, ta.s[0] ? (const uint8_t *)&spk[2] : rk, 4000);
        int ok = t && BRWalletSignTransaction(wd, t, 0, g_seed, sizeof(g_seed)) == 1;
        BRTransaction *wt = ok ? wire(t) : NULL;
        ck(wt && regChecked(wd, wt) == 1, "own DigiDollar transfer (P2TR token + P2WPKH fee) registers unconfirmed");
        if (t) BRTransactionFree(t);
        BRWalletFree(wd);
    }

    printf("[4] another wallet on the same phrase\n");
    {
        BRWallet *a = mkWallet(), *b = mkWallet();
        BRWalletRegisterTransaction(a, receive(a, 1, 100000000ULL, 0x41, 700000));
        BRWalletRegisterTransaction(b, receive(b, 1, 100000000ULL, 0x41, 700000));
        BRTransaction *s = signedSend(b, 30000000ULL);
        BRTransaction *s2 = s ? wire(s) : NULL;
        ck(s && regChecked(a, s) == 1, "its validly signed spend registers unconfirmed");
        printf("[5] a tampered copy of a valid spend\n");
        BRWallet *c = mkWallet();
        BRWalletRegisterTransaction(c, receive(c, 1, 100000000ULL, 0x41, 700000));
        if (s2) { s2->outputs[0].amount += 1000000; fin(s2); }
        ck(s2 && regChecked(c, s2) == 0, "the tampered copy is refused");
        BRWalletFree(a); BRWalletFree(b); BRWalletFree(c);
    }

    printf("[6] a P2TR spend beside an unknown prevout\n");
    {
        BRWallet *wt = mkWallet();
        BRTransaction *R = receive(wt, 2, 100000000ULL, 0x51, 700000);
        BRWalletRegisterTransaction(wt, R);
        UInt256 foreign; memset(foreign.u8, 0x52, 32);
        uint8_t fspk[22] = {OP_0, 20}; memset(&fspk[2], 0x77, 20);
        BRAddress d; BRWalletUnusedAddrs(wt, &d, 1, 1, 1);
        uint8_t dspk[64]; size_t dl = BRAddressScriptPubKey(dspk, sizeof(dspk), d.s);
        BRTransaction *u = BRTransactionNew();
        BRTransactionAddInput(u, R->txHash, 0, 100000000ULL, R->outputs[0].script, R->outputs[0].scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddInput(u, foreign, 0, 5000000ULL, fspk, sizeof(fspk), NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(u, 104000000ULL, dspk, dl);
        BRWalletSignTransaction(wt, u, 0, g_seed, sizeof(g_seed));          // signs the P2TR input only
        BRTxInputSetSignature(&u->inputs[1], kSig, 0);                       // the other party's part, here empty
        BRTxInputSetWitness(&u->inputs[1], kSig, 0);
        BRTransaction *x = wire(u), *y = wire(u), *z = wire(u);
        ck(x && regChecked(wt, x) == 0, "refused while unconfirmed (the P2TR signature cannot be checked)");
        if (y) { y->blockHeight = 700001; y->timestamp = g_ts++; }
        ck(y && regChecked(wt, y) == 1, "registered once confirmed");
        BRWalletFree(wt);
        wt = mkWallet();
        BRWalletRegisterTransaction(wt, receive(wt, 2, 100000000ULL, 0x51, 700000));
        ck(z && BRWalletRegisterTransactionTrusted(wt, z) == 1, "registered through the trusted (block) path");
        BRWalletFree(wt);
        BRTransactionFree(u);
    }

    printf("[7] junk signatures in a block are not second-guessed\n");
    {
        BRWallet *wb = mkWallet();
        BRTransaction *C2 = receive(wb, 1, 100000000ULL, 0x61, 700000);
        BRWalletRegisterTransaction(wb, C2);
        ck(regChecked(wb, junkSpend(wb, C2->txHash, 0, 99000000ULL, 700002)) == 1, "a confirmed junk-signature spend registers");
        BRWalletFree(wb);
        wb = mkWallet();
        C2 = receive(wb, 1, 100000000ULL, 0x61, 700000);
        BRWalletRegisterTransaction(wb, C2);
        ck(BRWalletRegisterTransactionTrusted(wb, junkSpend(wb, C2->txHash, 0, 99000000ULL, TX_UNCONFIRMED)) == 1,
           "and so does one delivered through the trusted path");
        BRWalletFree(wb);
    }

    printf("[8] one selection's own-unconfirmed walk is bounded in total\n");
    {
        BRWallet *wf = mkWallet();
        BRTransaction *R = receive(wf, 1, 10000000000ULL, 0x71, 700000);
        BRWalletRegisterTransaction(wf, R);
        // T1 spends R into 900 outputs; T2 spends 899 of them into 900 outputs: a fan-out chain
        BRTransaction *T1 = BRTransactionNew();
        uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
        BRTransactionAddInput(T1, R->txHash, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
        for (int i = 0; i < 900; i++) {
            BRAddress a; BRWalletUnusedAddrs(wf, &a, 1, 0, 1);
            uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
            BRTransactionAddOutput(T1, 10000000ULL, spk, l);
            BRWalletUnusedAddrs(wf, NULL, SEQUENCE_GAP_LIMIT_EXTERNAL + i + 1, 0, 1);
        }
        fin(T1); T1->blockHeight = TX_UNCONFIRMED;
        ck(BRWalletRegisterTransactionTrusted(wf, T1) == 1, "setup: T1 registered (trusted path)");
        BRTransaction *T2 = BRTransactionNew();
        for (uint32_t i = 0; i < 899; i++)
            BRTransactionAddInput(T2, T1->txHash, i, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
        for (int i = 0; i < 900; i++) {
            BRAddress a; BRWalletUnusedAddrs(wf, &a, 1, 0, 1);
            uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
            BRTransactionAddOutput(T2, 9000000ULL, spk, l);
        }
        fin(T2); T2->blockHeight = TX_UNCONFIRMED;
        ck(BRWalletRegisterTransactionTrusted(wf, T2) == 1, "setup: T2 registered (trusted path)");
        _walletKatWalkVisits = 0;
        (void)BRWalletMaxOutputAmount(wf);
        char m[160];
        snprintf(m, sizeof(m), "BRWalletMaxOutputAmount visited %lu inputs (bound %d)", _walletKatWalkVisits, SELECT_WALK_BUDGET);
        ck(_walletKatWalkVisits <= SELECT_WALK_BUDGET, m);
        BRWalletFree(wf);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
