// Host KAT REGISTER-RESULT: every tx-register entry point tells its caller whether the wallet now holds
// the tx, and the caller that follows that answer releases every object exactly once.
//
// The answer is BRWalletRegisterTransactionOutcome's *outcome (BRWallet.h): KEPT and KEPT_FOREIGN mean
// the wallet holds the very object passed in and owns it; REFUSED and DUPLICATE mean it does not, and
// the caller still owns it. The caller below is the contract-following one: it registers, then releases
// the object iff BRWalletRegisterOutcomeHeld(outcome) is false. It never looks the hash up afterwards.
// Each case ends with BRWalletFree and runs under AddressSanitizer with LeakSanitizer on, so an object
// released twice, used after release, or released by nobody is reported.
//
// CASES (one per process, chosen by argv[1]); the outcome each must report:
//   kept         a wallet receive (confirmed)                                         -> KEPT
//   foreign      an unconfirmed non-wallet tx inside the kept set's bounds; then the
//                same hash relayed again as a fresh object, then a second foreign one  -> KEPT_FOREIGN,
//                                                                                        DUPLICATE, KEPT_FOREIGN
//   bound        the kept set full: one more unconfirmed non-wallet tx                -> REFUSED
//   confirmed    a confirmed non-wallet tx (the wallet keeps none)                    -> REFUSED
//   range        a tx paying the wallet more than MAX_MONEY                           -> REFUSED
//   duplicate    a fresh object of a wallet tx the wallet already holds               -> DUPLICATE
//   same         the wallet's own records passed back (wallet tx and foreign record)  -> KEPT, KEPT_FOREIGN
//   promote      a held foreign record now spending a wallet coin; a fresh object of
//                it registered trusted: the wallet moves its OWN record in            -> DUPLICATE
//   held-refused the wallet's own foreign record passed back when it now spends a wallet
//                coin without a valid signature (refused, but still the wallet's)     -> KEPT_FOREIGN
//   sig-refused  a fresh unconfirmed spend of a wallet coin without a valid signature
//                (checked), and the same spend confirmed but unproven                  -> REFUSED, REFUSED
//   unproven-bound  registerRawTransaction's shape (unproven, forced unconfirmed): a
//                non-wallet tx inside the kept set's bounds, then one past them        -> KEPT_FOREIGN, REFUSED
//   legacy       the three int forms still return what they always have (control)
//
// Comparison arm (-DREGISTER_RESULT_UNFIXED): the earlier shape, where the only answer was the int
// return value, so "held" was read from it. Every case in which the wallet keeps a non-wallet record
// (foreign, bound, same, promote, held-refused, unproven-bound) then releases an object the wallet
// still holds (AddressSanitizer report); duplicate leaves a copy with no owner (LeakSanitizer report);
// kept, confirmed, range, sig-refused and legacy hold in both arms (the int answers 0 and the caller
// owns tx: the earlier shape got those right). sig-refused guards the fixed arm against reporting a
// signature-refused object as held (a leak); run.sh shows it red on such a mutation.
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

static const char *name(BRWalletRegisterOutcome o) {
    switch (o) {
        case BR_WALLET_REGISTER_REFUSED: return "REFUSED";
        case BR_WALLET_REGISTER_KEPT: return "KEPT";
        case BR_WALLET_REGISTER_KEPT_FOREIGN: return "KEPT_FOREIGN";
        case BR_WALLET_REGISTER_DUPLICATE: return "DUPLICATE";
    }
    return "?";
}

static void fin(BRTransaction *tx) {
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(n);
    n = BRTransactionSerialize(tx, d, n);
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(d);
}

// A tx spending prev:index (prev = a tag-derived hash when prevHash is NULL), one output of `amount`
// paying `addr`.
static BRTransaction *spend(const UInt256 *prevHash, uint32_t tag, const char *addr, uint64_t amount, uint32_t height) {
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *tx = BRTransactionNew();
    uint8_t in[25] = {0x76,0xa9,0x14}; in[23] = 0x88; in[24] = 0xac;
    UInt256 h;
    if (prevHash) h = *prevHash; else { memset(h.u8, 0x6b, 32); UInt32SetLE(h.u8, tag); }
    BRTransactionAddInput(tx, h, 0, 0, in, sizeof(in), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, l);
    fin(tx);
    tx->blockHeight = height; tx->timestamp = (height == TX_UNCONFIRMED) ? 0 : 1700000000;
    return tx;
}

// An independent object with the same content (what a peer relaying the hash again hands over).
static BRTransaction *again(const BRTransaction *tx) {
    BRTransaction *c = BRTransactionCopy(tx);
    c->blockHeight = tx->blockHeight; c->timestamp = tx->timestamp;
    return c;
}

// The contract-following caller: register, then release the object iff the answer says the wallet
// does not hold it. Returns the outcome.
static BRWalletRegisterOutcome reg(BRWallet *w, BRTransaction *tx, BRWalletRegisterCheck check, int *legacy) {
    BRWalletRegisterOutcome o = (BRWalletRegisterOutcome)-1;
    int r = BRWalletRegisterTransactionOutcome(w, tx, check, &o);
    if (legacy) *legacy = r;
    printf("  registered: r=%d outcome=%s\n", r, name(o));
    if (! BRWalletRegisterOutcomeHeld(o)) BRTransactionFree(tx);
    return o;
}

static int expectOutcome(BRWalletRegisterOutcome got, BRWalletRegisterOutcome want, const char *what) {
    char buf[200];
    snprintf(buf, sizeof(buf), "%s: outcome %s", what, name(want));
    ck(got == want, buf);
    return got == want;
}

// Walks every record the wallet holds (reads each), so a released one is reported here.
static size_t touchAll(BRWallet *w) {
    size_t n = 0, b = 0;
    pthread_mutex_lock(&w->lock);
    for (BRTransaction *t = BRSetIterate(w->allTx, NULL); t; t = BRSetIterate(w->allTx, t)) { n++; b += BRTransactionSize(t); }
    pthread_mutex_unlock(&w->lock);
    printf("  wallet holds %zu records (%zu bytes)\n", n, b);
    return n;
}

static int runCase(const char *c) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk);
    BRAddress a; BRWalletUnusedAddrs(w, &a, 1, 0, 1);
    int legacy = -1;

    if (strcmp(c, "kept") == 0) {
        printf("[kept] a wallet receive\n");
        expectOutcome(reg(w, spend(NULL, 1, a.s, 5000000, 700000), BR_WALLET_REGISTER_CHECKED, &legacy),
                      BR_WALLET_REGISTER_KEPT, "a wallet receive");
        ck(legacy == 1, "legacy answer 1");
        ck(BRWalletBalance(w) == 5000000, "credited");
    }
    else if (strcmp(c, "foreign") == 0) {
        printf("[foreign] an unconfirmed non-wallet tx; the same hash again; a second one\n");
        BRTransaction *f = spend(NULL, 2, kOther, 100000, TX_UNCONFIRMED);
        BRTransaction *f2 = again(f);
        expectOutcome(reg(w, f, BR_WALLET_REGISTER_CHECKED, &legacy), BR_WALLET_REGISTER_KEPT_FOREIGN, "foreign kept");
        ck(legacy == 0, "legacy answer 0 (the same as for a refusal: why the outcome exists)");
        touchAll(w);
        expectOutcome(reg(w, f2, BR_WALLET_REGISTER_CHECKED, NULL), BR_WALLET_REGISTER_DUPLICATE, "the same hash again");
        expectOutcome(reg(w, spend(NULL, 3, kOther, 100000, TX_UNCONFIRMED), BR_WALLET_REGISTER_CHECKED, NULL),
                      BR_WALLET_REGISTER_KEPT_FOREIGN, "a second foreign tx");
        ck(touchAll(w) == 2, "both foreign records are held");
    }
    else if (strcmp(c, "bound") == 0) {
        printf("[bound] the kept set full, then one more\n");
        for (uint32_t i = 0; i < WALLET_FOREIGN_TX_MAX_COUNT; i++)
            reg(w, spend(NULL, 100 + i, kOther, 100000, TX_UNCONFIRMED), BR_WALLET_REGISTER_CHECKED, NULL);
        expectOutcome(reg(w, spend(NULL, 99, kOther, 100000, TX_UNCONFIRMED), BR_WALLET_REGISTER_CHECKED, &legacy),
                      BR_WALLET_REGISTER_REFUSED, "one past the bound");
        ck(legacy == 0, "legacy answer 0");
    }
    else if (strcmp(c, "confirmed") == 0) {
        printf("[confirmed] a confirmed non-wallet tx\n");
        expectOutcome(reg(w, spend(NULL, 4, kOther, 100000, 700000), BR_WALLET_REGISTER_TRUSTED, &legacy),
                      BR_WALLET_REGISTER_REFUSED, "a confirmed non-wallet tx");
        ck(touchAll(w) == 0, "nothing held");
    }
    else if (strcmp(c, "range") == 0) {
        printf("[range] a tx paying the wallet more than MAX_MONEY\n");
        expectOutcome(reg(w, spend(NULL, 5, a.s, MAX_MONEY + 1, TX_UNCONFIRMED), BR_WALLET_REGISTER_UNPROVEN, &legacy),
                      BR_WALLET_REGISTER_REFUSED, "out of the money range");
        ck(legacy == 0, "legacy answer 0");
        ck(BRWalletBalance(w) == 0, "not credited");
    }
    else if (strcmp(c, "duplicate") == 0) {
        printf("[duplicate] a fresh object of a wallet tx already held\n");
        BRTransaction *t = spend(NULL, 6, a.s, 5000000, 700000), *t2 = again(t);
        expectOutcome(reg(w, t, BR_WALLET_REGISTER_TRUSTED, NULL), BR_WALLET_REGISTER_KEPT, "first object");
        expectOutcome(reg(w, t2, BR_WALLET_REGISTER_TRUSTED, &legacy), BR_WALLET_REGISTER_DUPLICATE, "second object");
        ck(legacy == 1, "legacy answer 1 (the same as for a tx the wallet took)");
        ck(BRWalletBalance(w) == 5000000, "credited once");
    }
    else if (strcmp(c, "same") == 0) {
        printf("[same] the wallet's own records passed back\n");
        BRTransaction *t = spend(NULL, 7, a.s, 5000000, 700000), *f = spend(NULL, 8, kOther, 100000, TX_UNCONFIRMED);
        reg(w, t, BR_WALLET_REGISTER_TRUSTED, NULL);
        reg(w, f, BR_WALLET_REGISTER_CHECKED, NULL);
        expectOutcome(reg(w, BRWalletTransactionForHash(w, t->txHash), BR_WALLET_REGISTER_TRUSTED, NULL),
                      BR_WALLET_REGISTER_KEPT, "the wallet's own wallet-tx record");
        expectOutcome(reg(w, BRWalletTransactionForHash(w, f->txHash), BR_WALLET_REGISTER_CHECKED, NULL),
                      BR_WALLET_REGISTER_KEPT_FOREIGN, "the wallet's own foreign record");
        ck(touchAll(w) == 2, "both still held");
    }
    else if (strcmp(c, "promote") == 0 || strcmp(c, "held-refused") == 0) {
        int promote = strcmp(c, "promote") == 0;
        printf(promote ? "[promote] a held foreign record now spends a wallet coin; a fresh object of it, trusted\n"
                       : "[held-refused] the wallet's own foreign record, now spending a wallet coin unsigned, passed back\n");
        BRTransaction *p = spend(NULL, 9, a.s, 5000000, 700000);   // the wallet's coin, registered later
        UInt256 ph = p->txHash;
        BRTransaction *f = spend(&ph, 0, kOther, 4000000, TX_UNCONFIRMED), *f2 = again(f);
        UInt256 fh = f->txHash;
        expectOutcome(reg(w, f, BR_WALLET_REGISTER_CHECKED, NULL), BR_WALLET_REGISTER_KEPT_FOREIGN, "spend of an unknown coin, kept as foreign");
        expectOutcome(reg(w, p, BR_WALLET_REGISTER_TRUSTED, NULL), BR_WALLET_REGISTER_KEPT, "the coin it spends, now the wallet's");
        if (promote) {
            expectOutcome(reg(w, f2, BR_WALLET_REGISTER_TRUSTED, &legacy), BR_WALLET_REGISTER_DUPLICATE,
                          "a fresh object: the wallet moves its own record in, not this one");
            ck(legacy == 1, "legacy answer 1");
            ck(BRWalletBalance(w) == 0, "the wallet's record now spends its coin");
        }
        else {
            BRTransactionFree(f2);
            expectOutcome(reg(w, BRWalletTransactionForHash(w, fh), BR_WALLET_REGISTER_TRUSTED, &legacy),
                          BR_WALLET_REGISTER_KEPT_FOREIGN, "refused, and still the wallet's own record");
            ck(legacy == 0, "legacy answer 0 (the same as for a refusal of the caller's object)");
            ck(BRWalletBalance(w) == 5000000, "the unsigned spend is not counted");
        }
        ck(touchAll(w) == 2, "the coin and the spend are held, once each");
    }
    else if (strcmp(c, "sig-refused") == 0) {
        printf("[sig-refused] a fresh spend of a wallet coin without a valid signature\n");
        BRTransaction *p = spend(NULL, 14, a.s, 5000000, 700000);
        UInt256 ph = p->txHash;
        expectOutcome(reg(w, p, BR_WALLET_REGISTER_TRUSTED, NULL), BR_WALLET_REGISTER_KEPT, "the wallet's coin");
        expectOutcome(reg(w, spend(&ph, 0, kOther, 4000000, TX_UNCONFIRMED), BR_WALLET_REGISTER_CHECKED, &legacy),
                      BR_WALLET_REGISTER_REFUSED, "an unconfirmed spend of it, junk scriptSig, checked");
        ck(legacy == 0, "legacy answer 0");
        BRTransaction *u = spend(&ph, 0, kOther, 3000000, 700001);   // a different spend: a different hash
        expectOutcome(reg(w, u, BR_WALLET_REGISTER_UNPROVEN, &legacy),
                      BR_WALLET_REGISTER_REFUSED, "a confirmed-but-unproven spend of it, junk scriptSig");
        ck(legacy == 0, "legacy answer 0");
        ck(BRWalletBalance(w) == 5000000, "the coin is not spent by either");
        ck(touchAll(w) == 1, "only the coin is held");
    }
    else if (strcmp(c, "unproven-bound") == 0) {
        printf("[unproven-bound] registerRawTransaction's shape: unproven, forced unconfirmed, non-wallet\n");
        for (uint32_t i = 0; i < WALLET_FOREIGN_TX_MAX_COUNT; i++) {
            BRTransaction *f = spend(NULL, 300 + i, kOther, 100000, 700000);
            f->blockHeight = TX_UNCONFIRMED; f->timestamp = 0;   // as registerRawTransaction does
            if (! expectOutcome(reg(w, f, BR_WALLET_REGISTER_UNPROVEN, i == 0 ? &legacy : NULL),
                                BR_WALLET_REGISTER_KEPT_FOREIGN, "a non-wallet tx inside the bounds")) break;
            if (i == 0) ck(legacy == 0, "legacy answer 0 (the same as for a refusal)");
        }
        BRTransaction *x = spend(NULL, 299, kOther, 100000, 700000);
        x->blockHeight = TX_UNCONFIRMED; x->timestamp = 0;
        expectOutcome(reg(w, x, BR_WALLET_REGISTER_UNPROVEN, &legacy), BR_WALLET_REGISTER_REFUSED, "one past the bounds");
        ck(legacy == 0, "legacy answer 0");
        ck(touchAll(w) == WALLET_FOREIGN_TX_MAX_COUNT, "the kept set, and nothing past it");
    }
    else if (strcmp(c, "legacy") == 0) {
        printf("[legacy] the int forms keep their answers\n");
        BRTransaction *t = spend(NULL, 10, a.s, 5000000, 700000), *t2 = again(t);
        BRTransaction *f = spend(NULL, 11, kOther, 100000, TX_UNCONFIRMED);
        BRTransaction *x = spend(NULL, 12, kOther, 100000, 700000);
        BRTransaction *u = spend(NULL, 13, a.s, 7000, TX_UNCONFIRMED);
        ck(BRWalletRegisterTransactionTrusted(w, t) == 1, "Trusted: wallet tx -> 1");
        ck(BRWalletRegisterTransaction(w, t2) == 1, "Checked: a duplicate -> 1");
        BRTransactionFree(t2);
        ck(BRWalletRegisterTransaction(w, f) == 0, "Checked: kept foreign -> 0");
        ck(BRWalletRegisterTransactionUnproven(w, x) == 0, "Unproven: confirmed non-wallet -> 0");
        BRTransactionFree(x);
        ck(BRWalletRegisterTransactionUnproven(w, u) == 1, "Unproven: wallet receive -> 1");
    }
    else { printf("unknown case %s\n", c); return 2; }

    BRWalletFree(w);
    printf(g_fail == 0 ? "CASE PASS %s\n" : "CASE FAIL %s\n", c);
    return g_fail ? 1 : 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(g_seed, kMn, NULL);
    g_mpk = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    if (argc < 2) { printf("usage: %s <case>\n", argv[0]); return 2; }
    return runCase(argv[1]);
}
