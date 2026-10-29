// Host KAT: a record the wallet holds as non-wallet (BRWallet's foreignTx) is moved into the wallet's
// transactions when it is registered again after the address set grew to cover it, with the same checks a
// new tx takes.
//
// CASES
// -----
//   promote_fresh_copy      a freshly parsed copy registered after the set grew: the record becomes the
//                           wallet's, is credited once, and a second registration is a no-op.
//   txadded_wallet_record   txAdded is handed the wallet's own record pointer, not the caller's copy.
//   unproven_route          the Unproven route moves the record and marks it WALLET_SIG_ALWAYS
//                           (BRWallet.c's private 0x80000000u).
//   same_record_promotes    the wallet's own record passed back (as _peerHasTx does) is moved when it spends
//                           no wallet coin.
//   same_record_checked     the wallet's own record passed back through the trusted route is registered as
//                           checked: a record whose inputs do not sign the wallet coin it spends stays
//                           non-wallet, and the balance and the coin's spent state are unchanged (coin
//                           unconfirmed and confirmed; and with a wallet-signed spend of the same coin present).
//   still_foreign_stays     a held record that still does not touch the wallet stays non-wallet (fresh copy and
//                           same pointer).
//   money_range_refused     a held record out of the money range stays as it was. Registration never holds such
//                           a record, so the KAT sets the amount on the held record directly.
//
// ARMS (run.sh): -DWALLET_FOREIGN_PROMOTE_UNFIXED must fail promote_fresh_copy, txadded_wallet_record,
// unproven_route and same_record_promotes; -DWALLET_PROMOTE_SAME_RECORD_UNFIXED must fail same_record_checked.
// Every other case passes in each arm; the shipped build passes all. One case per process:
// `wallet_held_record_promote_kat <case>` prints "RESULT <case> pass|fail". Host-only, not compiled into the
// Android NDK build.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

#define SIG_ALWAYS 0x80000000u   // BRWallet.c's WALLET_SIG_ALWAYS

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kSig[2] = { 0x01, 0x51 };   // not a signature
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk;
static const uint8_t kOther[22] = { 0x00, 0x14, 0x66,0x66,0x66,0x66,0x66,0x66,0x66,0x66,0x66,0x66,
                                    0x66,0x66,0x66,0x66,0x66,0x66,0x66,0x66,0x66,0x66 };   // no wallet key

static int g_added = 0;
static BRTransaction *g_addedTx = NULL;
static void onAdded(void *i, BRTransaction *t) { (void)i; g_added++; g_addedTx = t; }
static void onBal(void *i, uint64_t b) { (void)i; (void)b; }
static void onUpd(void *i, const UInt256 h[], size_t n, uint32_t bh, uint32_t ts) { (void)i; (void)h; (void)n; (void)bh; (void)ts; }
static void onDel(void *i, UInt256 h, int a, int b) { (void)i; (void)h; (void)a; (void)b; }

static BRWallet *newWallet(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
    BRWalletSetCallbacks(w, NULL, onBal, onAdded, onUpd, onDel);
    return w;
}

// a freshly parsed object, as a peer's tx message yields
static BRTransaction *parsed(const BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(n);
    BRTransactionSerialize(tx, d, n);
    BRTransaction *t = BRTransactionParse(d, n);
    free(d);
    return t;
}

static void setHashes(BRTransaction *tx)
{
    BRTransaction *t = parsed(tx);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

static BRTransaction *payTo(const uint8_t *spk, size_t len, uint8_t tag, uint32_t height)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;
    memset(prev.u8, tag, sizeof(prev.u8));
    BRTransactionAddInput(tx, prev, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 100000000ULL, spk, len);
    setHashes(tx);
    tx->blockHeight = height;
    tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : 1784980000;
    return tx;
}

// spends coin:0 with a scriptSig that does not sign it, paying a script no wallet key has
static BRTransaction *unsignedSpend(const BRTransaction *coin)
{
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, coin->txHash, 0, 0, NULL, 0, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 99000000ULL, kOther, sizeof(kOther));
    setHashes(tx);
    tx->blockHeight = TX_UNCONFIRMED;
    return tx;
}

// registers a caller-owned object and releases it as _peerRelayedTx does when the wallet did not take it
static int relay(BRWallet *w, BRTransaction *t, int route)   // route: 0 trusted, 1 checked, 2 unproven
{
    UInt256 h = t->txHash;
    int r = route == 0 ? BRWalletRegisterTransactionTrusted(w, t)
          : route == 1 ? BRWalletRegisterTransaction(w, t) : BRWalletRegisterTransactionUnproven(w, t);
    if (BRWalletTransactionForHash(w, h) != t) BRTransactionFree(t);
    return r;
}

// _peerHasTx: the wallet's own record handed back through the trusted route
static int handBack(BRWallet *w, UInt256 h)
{
    BRTransaction *rec = BRWalletTransactionForHash(w, h);
    return rec ? BRWalletRegisterTransactionTrusted(w, rec) : -1;
}

static int isWalletTx(BRWallet *w, UInt256 h)
{
    size_t n = BRWalletTransactions(w, NULL, 0), r = 0;
    BRTransaction **txs = calloc(n + 1, sizeof(*txs));
    n = BRWalletTransactions(w, txs, n);
    for (size_t i = 0; i < n; i++) if (UInt256Eq(txs[i]->txHash, h)) r = 1;
    free(txs);
    return (int)r;
}

static int isHeld(BRWallet *w, UInt256 h) { return BRWalletTransactionForHash(w, h) != NULL; }

// a payment to external segwit index 150, beyond a fresh wallet's window, held as non-wallet
static UInt256 holdFarPayment(BRWallet *w, uint8_t tag)
{
    BRAddress far[151];
    BRWallet *probe = BRWalletNew(NULL, 0, g_mpk);
    BRWalletUnusedAddrs(probe, far, 151, 0, 1);
    BRWalletFree(probe);
    uint8_t spk[64];
    size_t len = BRAddressScriptPubKey(spk, sizeof(spk), far[150].s);
    BRTransaction *p = payTo(spk, len, tag, TX_UNCONFIRMED);
    UInt256 h = p->txHash;
    check(! BRWalletContainsAddress(w, far[150].s), "setup: index 150 is beyond the fresh wallet's window");
    check(relay(w, p, 1) == 0, "setup: the payment is not the wallet's when it arrives");
    check(isHeld(w, h) && ! isWalletTx(w, h) && BRWalletBalance(w) == 0, "setup: it is held as non-wallet, not counted");
    BRWalletUnusedAddrs(w, NULL, 200, 0, 1);   // the address set grows to cover index 150
    check(BRWalletContainsAddress(w, far[150].s), "setup: the set grew to cover index 150");
    return h;
}

static size_t walletScript(BRWallet *w, uint8_t spk[64])
{
    BRAddress a;
    BRWalletUnusedAddrs(w, &a, 1, 0, 1);
    return BRAddressScriptPubKey(spk, 64, a.s);
}

// ---- cases -------------------------------------------------------------------------------------------------------
static int case_promote_fresh_copy(void)
{
    BRWallet *w = newWallet();
    UInt256 h = holdFarPayment(w, 0x31);

    g_added = 0;
    int r = relay(w, parsed(BRWalletTransactionForHash(w, h)), 1);
    check(r == 1, "CRUX: registering a fresh copy after the set grew returns 1");
    check(isWalletTx(w, h), "CRUX: the held record is now the wallet's");
    check(BRWalletBalance(w) == 100000000ULL, "CRUX: the payment is credited");
    check(g_added == 1, "txAdded fires once");

    g_added = 0;
    r = relay(w, parsed(BRWalletTransactionForHash(w, h)), 1);
    check(r == 1 && g_added == 0 && BRWalletTransactions(w, NULL, 0) == 1 && BRWalletBalance(w) == 100000000ULL,
          "registering it again is a no-op");
    r = handBack(w, h);
    check(r == 1 && g_added == 0 && BRWalletBalance(w) == 100000000ULL, "handing back the promoted record is a no-op");

    BRWalletRemoveTransaction(w, h);
    check(! isHeld(w, h) && BRWalletBalance(w) == 0, "the promoted record is removed cleanly");
    BRWalletFree(w);
    return g_fail == 0;
}

static int case_txadded_wallet_record(void)
{
    BRWallet *w = newWallet();
    UInt256 h = holdFarPayment(w, 0x32);
    BRTransaction *rec = BRWalletTransactionForHash(w, h), *copy = parsed(rec);

    g_added = 0; g_addedTx = NULL;
    int r = BRWalletRegisterTransaction(w, copy);
    check(r == 1 && g_added == 1, "the fresh copy promotes the record and txAdded fires");
    check(g_addedTx == rec && g_addedTx != copy, "CRUX: txAdded is handed the wallet's record, not the caller's copy");
    check(BRWalletTransactionForHash(w, h) == rec, "the wallet keeps its own record");
    BRTransactionFree(copy);   // the caller still owns its copy
    BRWalletFree(w);
    return g_fail == 0;
}

static int case_unproven_route(void)
{
    BRWallet *w = newWallet();
    UInt256 h = holdFarPayment(w, 0x33);
    BRTransaction *rec = BRWalletTransactionForHash(w, h);

    check(! (rec->walletSigCheck & SIG_ALWAYS), "setup: the held record is not marked to be checked at any height");
    int r = relay(w, parsed(rec), 2);
    check(r == 1 && isWalletTx(w, h) && BRWalletBalance(w) == 100000000ULL, "CRUX: the Unproven route promotes and credits");
    check((BRWalletTransactionForHash(w, h)->walletSigCheck & SIG_ALWAYS) != 0,
          "CRUX: the promoted record is marked to be checked at any height (WALLET_SIG_ALWAYS)");
    BRWalletFree(w);
    return g_fail == 0;
}

static int case_same_record_promotes(void)
{
    BRWallet *w = newWallet();
    UInt256 h = holdFarPayment(w, 0x34);

    g_added = 0;
    int r = handBack(w, h);
    check(r == 1 && isWalletTx(w, h) && BRWalletBalance(w) == 100000000ULL && g_added == 1,
          "CRUX: the wallet's own record handed back is promoted when it spends no wallet coin");
    BRWalletFree(w);
    return g_fail == 0;
}

static int case_same_record_checked(void)
{
    uint8_t spk[64];

    for (int confirmed = 0; confirmed < 2; confirmed++) {
        BRWallet *w = newWallet();
        size_t len = walletScript(w, spk);
        BRTransaction *coin = payTo(spk, len, (uint8_t)(0x41 + confirmed), confirmed ? 700000 : TX_UNCONFIRMED);
        BRTransaction *spend = unsignedSpend(coin);
        UInt256 ch = coin->txHash, sh = spend->txHash;

        relay(w, spend, 1);   // the spend arrives before the coin is known: held as non-wallet
        check(isHeld(w, sh) && ! isWalletTx(w, sh), "setup: the spend is held as non-wallet");
        relay(w, coin, confirmed ? 0 : 1);
        uint64_t before = BRWalletBalance(w);
        check(before == 100000000ULL && ! BRWalletOutpointSpent(w, ch, 0), "setup: the coin is credited and unspent");

        relay(w, parsed(BRWalletTransactionForHash(w, sh)), 1);
        check(! isWalletTx(w, sh) && ! BRWalletOutpointSpent(w, ch, 0), "a checked fresh copy of the spend stays non-wallet");

        g_added = 0;
        int r = handBack(w, sh);
        printf("NOTE: coin %s: hand-back r=%d balance %llu -> %llu spent=%d\n", confirmed ? "confirmed" : "unconfirmed",
               r, (unsigned long long)before, (unsigned long long)BRWalletBalance(w), BRWalletOutpointSpent(w, ch, 0));
        check(! isWalletTx(w, sh) && isHeld(w, sh), "CRUX: it stays held as non-wallet");
        check(BRWalletBalance(w) == before && ! BRWalletOutpointSpent(w, ch, 0) && g_added == 0,
              "CRUX: the balance and the coin's spent state are unchanged");
        // and after the address set changes again
        BRWalletUnusedAddrs(w, NULL, 10, 0, 1);
        check(BRWalletBalance(w) == before && ! BRWalletOutpointSpent(w, ch, 0), "and stay unchanged afterwards");
        BRWalletFree(w);
    }

    // with a wallet-signed spend of the same coin present
    {
        BRWallet *w = newWallet();
        size_t len = walletScript(w, spk);
        BRTransaction *coin = payTo(spk, len, 0x43, 700000);
        BRTransaction *spend = unsignedSpend(coin);
        UInt256 sh = spend->txHash;

        relay(w, spend, 1);
        relay(w, coin, 1);
        BRAddress d;
        BRWalletUnusedAddrs(w, &d, 1, 1, 1);
        BRTransaction *s = BRWalletCreateTransaction(w, 50000000ULL, d.s);
        int ok = s && BRWalletSignTransaction(w, s, 0, g_seed, sizeof(g_seed)) == 1;
        if (ok) { s->timestamp = 1784990000; ok = BRWalletRegisterTransaction(w, s) == 1; }
        check(ok, "setup: the wallet's own signed spend of the coin is registered");
        uint64_t before = BRWalletBalance(w);

        handBack(w, sh);
        check(! isWalletTx(w, sh), "CRUX: the held unsigned spend handed back stays non-wallet");
        check(ok && BRWalletTransactionIsValid(w, s) && BRWalletBalance(w) == before,
              "CRUX: the wallet's signed spend stays valid and the balance is unchanged");
        BRWalletFree(w);
    }
    return g_fail == 0;
}

static int case_still_foreign_stays(void)
{
    BRWallet *w = newWallet();
    BRTransaction *p = payTo(kOther, sizeof(kOther), 0x51, TX_UNCONFIRMED);
    UInt256 h = p->txHash;

    check(relay(w, p, 1) == 0 && isHeld(w, h) && ! isWalletTx(w, h), "setup: a tx that does not touch the wallet is held");
    BRWalletUnusedAddrs(w, NULL, 200, 0, 1);

    g_added = 0;
    relay(w, parsed(BRWalletTransactionForHash(w, h)), 0);
    check(! isWalletTx(w, h) && isHeld(w, h), "CRUX: a trusted fresh copy leaves it non-wallet");
    relay(w, parsed(BRWalletTransactionForHash(w, h)), 2);
    check(! isWalletTx(w, h) && isHeld(w, h), "CRUX: an Unproven fresh copy leaves it non-wallet");
    handBack(w, h);
    check(! isWalletTx(w, h) && isHeld(w, h), "CRUX: handing back the record leaves it non-wallet");
    check(BRWalletBalance(w) == 0 && BRWalletTransactions(w, NULL, 0) == 0 && g_added == 0, "nothing is counted");
    BRWalletFree(w);
    return g_fail == 0;
}

static int case_money_range_refused(void)
{
    BRWallet *w = newWallet();
    UInt256 h = holdFarPayment(w, 0x61);
    BRTransaction *rec = BRWalletTransactionForHash(w, h), *copy = parsed(rec);

    rec->outputs[0].amount = MAX_MONEY + 1;   // see the header: set directly on the held record
    g_added = 0;
    BRWalletRegisterTransactionTrusted(w, copy);
    check(! isWalletTx(w, h) && isHeld(w, h) && BRWalletTransactionForHash(w, h) == rec,
          "CRUX: it stays held as non-wallet, as it was");
    check(BRWalletBalance(w) == 0 && g_added == 0, "CRUX: nothing is credited");
    BRTransactionFree(copy);
    handBack(w, h);
    check(! isWalletTx(w, h) && BRWalletBalance(w) == 0 && g_added == 0, "handing it back leaves it non-wallet too");
    rec->outputs[0].amount = 100000000ULL;
    BRWalletFree(w);
    return g_fail == 0;
}

int main(int argc, char *argv[])
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    const char *cases[] = { "promote_fresh_copy", "txadded_wallet_record", "unproven_route", "same_record_promotes",
                            "same_record_checked", "still_foreign_stays", "money_range_refused" };
    int (*fns[])(void) = { case_promote_fresh_copy, case_txadded_wallet_record, case_unproven_route,
                           case_same_record_promotes, case_same_record_checked, case_still_foreign_stays,
                           case_money_range_refused };
    const size_t n = sizeof(cases)/sizeof(cases[0]);

    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) { for (size_t i = 0; i < n; i++) printf("%s\n", cases[i]); return 0; }
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], cases[i]) != 0) continue;
        int ok = fns[i]();
        printf("RESULT %s %s\n", cases[i], ok ? "pass" : "fail");
        return ok ? 0 : 1;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
