// Host KAT: DigiDollar transfer coin selection tries another coin before it refuses, and every
// transfer the BASE builder accepts is reproduced BYTE-FOR-BYTE.
//
// WHAT IT PROVES.
//   (1) DIFFERENTIAL (the safety rule). For a generated corpus of several thousand
//       (holding-set x amount) cases, wherever the base builder builds a transaction the current
//       builder builds a BYTE-IDENTICAL one. New behaviour exists ONLY where the base returned
//       NULL. The base builder is BRWalletCreateDigiDollarTransfer_base, a mechanical copy of the
//       function at the base core commit (see base_builder.c), linked beside the current one in
//       this same translation unit.
//   (2) THE RETRY (the new behaviour). Holdings whose smallest-first prefix lands the change in a
//       refused band now build from another coin:
//         * $10.50 + $20.00 held, send $10.00 -> builds from the $20.00 coin;
//         * $1.50  + $3.50  held, send $1.25  -> builds.
//       On the unmodified tree the current builder == the base builder, so these BUILD assertions
//       FAIL there (both return NULL): that is the recorded red.
//   (3) THE REMAINING REFUSALS (guards). An exact match builds with no change; a sub-minimum
//       change with no alternative coin is still refused; an over-cap change is refused; a send
//       larger than the DigiDollar balance is refused; and the answer does not depend on the order
//       the coins were presented in.
//   (4) COMPLETENESS. Over a small grid of holding sets (every single, every pair, and triples from
//       a coarser subset) the KAT enumerates EVERY subset of the holding and works out, independently
//       of the builder, whether any of them covers the amount with an acceptable change (none, or an
//       amount that is itself a valid output). The builder must build
//       whenever one does and refuse whenever none does. The grid deliberately mixes coins around
//       the $1.00 output minimum, at and around the $100,000 per-output cap, and above the cap -- a
//       holding above the cap is ordinary state (a coin can be minted larger than a single transfer
//       output may be), and it must not be able to stand between the wallet and a selection that
//       does not need it. This arm holds for every holding whose coins are each at least the $1.00
//       output minimum, which is what a DigiDollar output amount always is.
//
// The current builder is reached by #include-ing the live BRWallet.c below (so its file-static
// helpers, struct and macros are visible to base_builder.c too); BRWallet.c is therefore NOT on
// the compiler line in run.sh. Same include-the-.c pattern as digidollar_send_kat.
//
// Exit code 0 = all checks passed; 1 = a check failed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRWallet.c"       // the CURRENT builder + struct/statics/macros (NOT on the clang line)
#include "base_builder.c"   // BRWalletCreateDigiDollarTransfer_base (the base function, renamed)

#include "BRDigiDollar.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

static int g_fail = 0;
static void ck(int cond, const char *what) {
    printf(cond ? "  ok   %s\n" : "  FAIL %s\n", what);
    if (!cond) g_fail++;
}

// canonical all-zeros mnemonic (its m/86' and m/84' trees are KAT-pinned elsewhere)
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = {0};

// The golden testnet TD recipient key (also used by digidollar_send_kat).
static const uint8_t kRecip[32] = {
    0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
    0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };

static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;

// deterministic PRNG so the corpus is reproducible
static uint64_t g_rng = 0x9e3779b97f4a7c15ULL;
static uint64_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return g_rng; }
static uint64_t rndRange(uint64_t lo, uint64_t hi) { return lo + rnd() % (hi - lo + 1); }

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A DD token credit: vout0 is our zero-value P2TR token (scriptPubKey spk), the OP_RETURN carries
// a single amount `cents` (a "DD" type-2 push). prevHash is made unique from `tag`.
static BRTransaction *ddCreditTx(const uint8_t *spk, size_t spkLen, int64_t cents, uint8_t tag) {
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    UInt256 ph; memset(ph.u8, tag, 32);
    BRTransactionAddInput(tx, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);                 // vout0: DD token (ours)
    uint8_t orr[32]; size_t ol = 0;
    orr[ol++]=0x6a; orr[ol++]=0x02; orr[ol++]=0x44; orr[ol++]=0x44; orr[ol++]=0x01; orr[ol++]=0x02;
    uint8_t enc[9]; size_t el = BRDigiDollarWriteScriptNum(cents, enc);
    orr[ol++] = (uint8_t)el; memcpy(orr + ol, enc, el); ol += el;
    BRTransactionAddOutput(tx, 0, orr, ol);                     // vout1: OP_RETURN
    tx->blockHeight = 700000;                                   // confirmed
    finalizeTxHash(tx);
    return tx;
}

// Build a funded wallet: DD coins `cents[0..n)` registered in the order given by `order`
// (order==NULL means natural 0..n-1), plus one confirmed 5 DGB fee UTXO. Coin i is tagged
// 0x10+i so a built transfer's chosen DD input can be traced back to a coin.
static BRWallet *mkWallet(const int64_t *cents, size_t n, const size_t *order) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    for (size_t k = 0; k < n; k++) {
        size_t i = order ? order[k] : k;
        BRWalletRegisterTransaction(w, ddCreditTx(spk, spkLen, cents[i], (uint8_t)(0x10 + i)));
    }
    // one 5 DGB fee UTXO
    BRAddress da = BRWalletReceiveAddress(w, 1);
    uint8_t dspk[64]; size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    BRTransaction *dgb = BRTransactionNew();
    dgb->version = 1;
    UInt256 dh; memset(dh.u8, 0xD0, 32);
    BRTransactionAddInput(dgb, dh, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dgb, 500000000, dspk, dspkLen);
    dgb->blockHeight = 700000;
    finalizeTxHash(dgb);
    BRWalletRegisterTransaction(w, dgb);
    return w;
}

// Serialize a transfer built by base (useBase!=0) or the current builder. Returns a malloc'd
// buffer (caller frees) and sets *len, or NULL if the builder returned NULL. Does not register
// the tx, so wallet state is identical between two successive calls.
static uint8_t *buildSer(BRWallet *w, uint64_t cents, int useBase, size_t *len) {
    BRTransaction *tx = useBase ? BRWalletCreateDigiDollarTransfer_base(w, kRecip, cents)
                               : BRWalletCreateDigiDollarTransfer(w, kRecip, cents);
    if (!tx) return NULL;
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(n ? n : 1);
    *len = BRTransactionSerialize(tx, buf, n);
    BRTransactionFree(tx);
    return buf;
}

// Build a current transfer and report its shape: the number of DD inputs (34-byte OP_1 P2TR
// prevouts) it spends and the DD change cents it records (0 if there is no change push). Returns 1
// on a built tx (out-params set), 0 if the builder returned NULL. The coin a single-input retry
// selected is identified by that change: from a set {a,b} sending `cents`, one input with change c
// can only be the coin of value cents+c.
static int builtShape(BRWallet *w, uint64_t cents, int *ddInputs, int64_t *ddChange) {
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, cents);
    if (!tx) return 0;
    int c = 0;
    for (size_t i = 0; i < tx->inCount; i++)
        if (tx->inputs[i].scriptLen == 34 && tx->inputs[i].script[0] == 0x51) c++;
    int64_t a[8]; int na = BRDigiDollarDecodeAmounts(tx, a, 8);
    *ddInputs = c;
    *ddChange = (na == 2) ? a[1] : 0;   // amounts are [sent, change]; one amount means no change
    BRTransactionFree(tx);
    return 1;
}

// ---- the completeness arm ---------------------------------------------------------------------
// The two questions below are answered INDEPENDENTLY of the builder, by enumerating every subset of
// the holding. anySubsetAcceptable: does any subset cover `cents` with an acceptable change (none, or
// an amount that is itself a valid output)?  subsetWithTotal: is `total` the value of some subset of
// exactly `count` coins -- the check that the DigiDollar value a built transfer moves is value the
// wallet actually held, and that it spent as many coins as it accounted for.
static int anySubsetAcceptable(const int64_t *c, size_t n, uint64_t cents) {
    for (uint32_t mask = 1; mask < (1u << n); mask++) {
        uint64_t s = 0;
        for (size_t i = 0; i < n; i++) if (mask & (1u << i)) s += (uint64_t)c[i];
        if (s < cents) continue;
        uint64_t ch = s - cents;
        if (ch == 0 || (ch >= 100 && ch <= 10000000)) return 1;
    }
    return 0;
}

static int subsetWithTotal(const int64_t *c, size_t n, uint64_t total, int count) {
    for (uint32_t mask = 1; mask < (1u << n); mask++) {
        uint64_t s = 0; int k = 0;
        for (size_t i = 0; i < n; i++) if (mask & (1u << i)) { s += (uint64_t)c[i]; k++; }
        if (s == total && k == count) return 1;
    }
    return 0;
}

// One holding, every amount: the enumeration decides, the builder must agree.
static long P_cases, P_exists, P_miss, P_wrong, P_unsound;

static void probe(const int64_t *c, size_t n, const uint64_t *A, size_t NA) {
    BRWallet *w = mkWallet(c, n, NULL);
    for (size_t ai = 0; ai < NA; ai++) {
        int want = anySubsetAcceptable(c, n, A[ai]);
        BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, A[ai]);
        P_cases++;
        if (want) P_exists++;
        if (want && ! tx) {
            P_miss++;
            if (P_miss < 4) printf("  MISS    n=%zu coins=%lld/%lld/%lld amt=%llu\n", n,
                                   (long long)c[0], (long long)(n > 1 ? c[1] : 0),
                                   (long long)(n > 2 ? c[2] : 0), (unsigned long long)A[ai]);
        } else if (! want && tx) {
            P_wrong++;
            if (P_wrong < 4) printf("  NO-SEL  n=%zu coins=%lld/%lld/%lld amt=%llu\n", n,
                                    (long long)c[0], (long long)(n > 1 ? c[1] : 0),
                                    (long long)(n > 2 ? c[2] : 0), (unsigned long long)A[ai]);
        } else if (tx) {
            int ddi = 0;
            for (size_t x = 0; x < tx->inCount; x++)
                if (tx->inputs[x].scriptLen == 34 && tx->inputs[x].script[0] == 0x51) ddi++;
            int64_t amt[8]; int na = BRDigiDollarDecodeAmounts(tx, amt, 8);
            int64_t chg = (na == 2) ? amt[1] : 0;
            if (! ((na == 1 || na == 2) && amt[0] == (int64_t)A[ai] &&
                   (chg == 0 || (chg >= 100 && chg <= 10000000)) &&
                   subsetWithTotal(c, n, A[ai] + (uint64_t)chg, ddi))) {
                P_unsound++;
                if (P_unsound < 4) printf("  UNSOUND n=%zu coins=%lld/%lld/%lld amt=%llu na=%d "
                                          "chg=%lld ddIn=%d\n", n, (long long)c[0],
                                          (long long)(n > 1 ? c[1] : 0), (long long)(n > 2 ? c[2] : 0),
                                          (unsigned long long)A[ai], na, (long long)chg, ddi);
            }
        }
        if (tx) BRTransactionFree(tx);
    }
    BRWalletFree(w);
}

static void completeness(void) {
    // Coins around the $1.00 output minimum, at and around the $100,000 per-output cap, and above the
    // cap. Singles and pairs use the whole grid; triples use the starred subset, so the arm stays a
    // small grid (a much wider run of the same arm is kept out of the suite, in evidence).
    static const int64_t V[]  = { 100, 350, 1050, 5000000, 9999900, 9999902, 9999960,
                                  10000000, 10000050, 10000100, 12000000, 20000000 };
    static const size_t  S3[] = {   0,   1,    4,       5,       7,       8,      10 };   /* starred */
    static const uint64_t A[] = { 100, 199, 1000, 9999900, 9999902, 10000000 };
    const size_t NV = sizeof(V) / sizeof(V[0]), NS = sizeof(S3) / sizeof(S3[0]),
                 NA = sizeof(A) / sizeof(A[0]);
    int64_t c[3];

    for (size_t i = 0; i < NV; i++) { c[0] = V[i]; probe(c, 1, A, NA); }
    for (size_t i = 0; i < NV; i++) for (size_t j = i; j < NV; j++) {
        c[0] = V[i]; c[1] = V[j]; probe(c, 2, A, NA);
    }
    for (size_t i = 0; i < NS; i++) for (size_t j = i; j < NS; j++) for (size_t k = j; k < NS; k++) {
        c[0] = V[S3[i]]; c[1] = V[S3[j]]; c[2] = V[S3[k]]; probe(c, 3, A, NA);
    }

    printf("  completeness: %ld cases | a selection exists in %ld | refused anyway %ld | "
           "built with none %ld | built unsoundly %ld\n",
           P_cases, P_exists, P_miss, P_wrong, P_unsound);
    ck(P_miss == 0, "wherever ANY subset of the holding is an acceptable selection, a transfer builds");
    ck(P_wrong == 0, "wherever NO subset is an acceptable selection, the builder refuses");
    ck(P_unsound == 0, "every built transfer moves the value of a subset of the holding, change in band");
}

// ---- the differential corpus ------------------------------------------------------------------
static void corpus(void) {
    long cases = 0, baseBuilds = 0, identical = 0, newOnly = 0, bothNull = 0;
    const int NWALLETS = 100, NAMOUNTS = 48;
    for (int wi = 0; wi < NWALLETS; wi++) {
        size_t n = (size_t)rndRange(1, 6);
        int64_t cents[6];
        int64_t total = 0;
        for (size_t i = 0; i < n; i++) {
            // mix of small coins (dust/band-triggering) and occasional large ones (near cap)
            int64_t v = (rnd() % 5 == 0) ? (int64_t)rndRange(9000000, 15000000)
                                         : (int64_t)rndRange(100, 60000);
            cents[i] = v; total += v;
        }
        BRWallet *w = mkWallet(cents, n, NULL);
        for (int ai = 0; ai < NAMOUNTS; ai++) {
            uint64_t hi = (uint64_t)(total < 10000000 ? total : 10000000);
            if (hi < 100) break;
            uint64_t amt = rndRange(100, hi);
            size_t lb = 0, ln = 0;
            uint8_t *bb = buildSer(w, amt, 1, &lb);
            uint8_t *nn = buildSer(w, amt, 0, &ln);
            cases++;
            if (bb) {
                baseBuilds++;
                // THE RULE: where the base builds, the current builder must build the same bytes.
                if (nn && ln == lb && memcmp(bb, nn, lb) == 0) identical++;
                else { g_fail++; if (g_fail < 6) printf("  DIFF wi=%d amt=%llu base_len=%zu new_%s\n",
                                                        wi, (unsigned long long)amt, lb,
                                                        nn ? "differs" : "NULL"); }
            } else if (nn) {
                newOnly++;   // improvement: builds where the base refused (allowed, not compared)
            } else {
                bothNull++;
            }
            free(bb); free(nn);
        }
        BRWalletFree(w);
    }
    printf("  corpus: %ld cases | base built %ld (all byte-identical: %s) | new-only %ld | both-null %ld\n",
           cases, baseBuilds, (identical == baseBuilds) ? "yes" : "NO", newOnly, bothNull);
    ck(identical == baseBuilds && baseBuilds > 0,
       "every transfer the base builds is reproduced byte-for-byte (differential)");
}

int main(void) {
    printf("dd_coin_selection_kat\n");
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));

    // ---- (2) the retry: builds from another coin where the base refused --------------------
    {   // $10.50 + $20.00, send $10.00 -> builds from the $20.00 coin
        int64_t c[2] = { 1050, 2000 };            // coin0 tag 0x10 ($10.50), coin1 tag 0x11 ($20.00)
        BRWallet *w = mkWallet(c, 2, NULL);
        size_t lb = 0; uint8_t *bb = buildSer(w, 1000, 1, &lb);
        ck(bb == NULL, "base refuses $10.50+$20.00 send $10.00 (smallest-first change is $0.50)");
        int ddi = 0; int64_t chg = -1; int built = builtShape(w, 1000, &ddi, &chg);
        ck(built && ddi == 1 && chg == 1000,
           "current builder builds it from the $20.00 coin (one DD input, $10.00 change)");
        free(bb); BRWalletFree(w);
    }
    {   // $1.50 + $3.50, send $1.25 -> builds
        int64_t c[2] = { 150, 350 };
        BRWallet *w = mkWallet(c, 2, NULL);
        size_t lb = 0; uint8_t *bb = buildSer(w, 125, 1, &lb);
        ck(bb == NULL, "base refuses $1.50+$3.50 send $1.25 (smallest-first change is $0.25)");
        int ddi = 0; int64_t chg = -1; int built = builtShape(w, 125, &ddi, &chg);
        ck(built && ddi == 1 && chg == 225,
           "current builder builds it from the $3.50 coin (one DD input, $2.25 change)");
        free(bb); BRWalletFree(w);
    }
    {   // the same send with an above-cap coin ALSO held: a coin larger than a single transfer output
        // may be cannot stand between the wallet and a selection that does not need it.
        int64_t c[3] = { 150, 350, 12000000 };     // $1.50 + $3.50 + $120,000
        BRWallet *w = mkWallet(c, 3, NULL);
        int ddi = 0; int64_t chg = -1; int built = builtShape(w, 125, &ddi, &chg);
        ck(built && ddi == 1 && chg == 225,
           "$1.50+$3.50 send $1.25 still builds from the $3.50 coin when a $120,000 coin is held too");
        BRWalletFree(w);
    }
    {   // an above-cap coin that IS the only selection: change lands exactly on the per-output cap
        int64_t c[2] = { 350, 12000000 };          // $3.50 + $120,000, send $20,000
        BRWallet *w = mkWallet(c, 2, NULL);
        int ddi = 0; int64_t chg = -1; int built = builtShape(w, 2000000, &ddi, &chg);
        ck(built && ddi == 1 && chg == 10000000,
           "$3.50+$120,000 send $20,000 builds from the $120,000 coin ($100,000 change, at the cap)");
        BRWalletFree(w);
    }
    {   // a selection only a PAIR of near-cap coins can make: neither coin alone is acceptable and
        // the largest-first prefix over all three is not either.
        int64_t c[3] = { 9999902, 9999902, 9999999 };   // $99,999.02 x2 + $99,999.99
        BRWallet *w = mkWallet(c, 3, NULL);
        int ddi = 0; int64_t chg = -1; int built = builtShape(w, 9999900, &ddi, &chg);
        ck(built && ddi == 2 && chg == 9999904,
           "send $99,999.00 builds from the two $99,999.02 coins ($99,999.04 change)");
        BRWalletFree(w);
    }

    // ---- (3a) exact match: base builds, current builds byte-identically, no DD change -------
    {
        int64_t c[1] = { 4000 };
        BRWallet *w = mkWallet(c, 1, NULL);
        size_t lb = 0, ln = 0;
        uint8_t *bb = buildSer(w, 4000, 1, &lb);
        uint8_t *nn = buildSer(w, 4000, 0, &ln);
        ck(bb && nn && lb == ln && memcmp(bb, nn, lb) == 0,
           "exact-match transfer builds and is byte-identical to the base");
        BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);
        int64_t amt[8]; int na = tx ? BRDigiDollarDecodeAmounts(tx, amt, 8) : -1;
        ck(na == 1 && amt[0] == 4000, "exact match: one OP_RETURN amount, no change output");
        if (tx) BRTransactionFree(tx);
        free(bb); free(nn); BRWalletFree(w);
    }

    // ---- (3b) sub-minimum change with no alternative is still refused -----------------------
    {
        int64_t c[1] = { 150 };                    // only coin; send 125 -> change 25 (dust), no alt
        BRWallet *w = mkWallet(c, 1, NULL);
        ck(BRWalletCreateDigiDollarTransfer(w, kRecip, 125) == NULL,
           "sub-$1 change with no alternative coin is refused (NULL)");
        BRWalletFree(w);
    }

    // ---- (3b2) a send larger than the DigiDollar balance is refused -------------------------
    {
        int64_t c[1] = { 100 };                    // $1.00 held; send $50.00 -- nothing covers it
        BRWallet *w = mkWallet(c, 1, NULL);
        ck(BRWalletCreateDigiDollarTransfer(w, kRecip, 5000) == NULL,
           "a send larger than the DigiDollar balance is refused (NULL)");
        BRWalletFree(w);
    }

    // ---- (3c) over-cap change with no alternative is refused --------------------------------
    {
        int64_t c[1] = { 15000000 };               // $150,000 coin; send $40,000 -> change $110,000
        BRWallet *w = mkWallet(c, 1, NULL);
        ck(BRWalletCreateDigiDollarTransfer(w, kRecip, 4000000) == NULL,
           "over-cap change with no alternative coin is refused (NULL)");
        BRWalletFree(w);
    }

    // ---- (3d) the same answer for any input order ------------------------------------------
    {   // a base-success set (distinct values -> order-independent) checked over all 6 orders
        int64_t c[3] = { 500, 1050, 2000 };
        size_t perms[6][3] = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        uint8_t *ref = NULL; size_t rl = 0; int same = 1;
        for (int p = 0; p < 6; p++) {
            BRWallet *w = mkWallet(c, 3, perms[p]);
            size_t l = 0; uint8_t *b = buildSer(w, 1000, 0, &l);
            if (!b) same = 0;
            else if (!ref) { ref = b; rl = l; }
            else { if (l != rl || memcmp(b, ref, l) != 0) same = 0; free(b); }
            BRWalletFree(w);
        }
        ck(same, "a base-success transfer is identical for any input order");
        free(ref);
    }
    {   // a RETRY set (distinct values) checked over all 6 orders
        int64_t c[3] = { 1050, 2000, 4000 };       // send 1000: smallest-first change $0.50 -> retry
        size_t perms[6][3] = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        uint8_t *ref = NULL; size_t rl = 0; int same = 1, built = 1;
        for (int p = 0; p < 6; p++) {
            BRWallet *w = mkWallet(c, 3, perms[p]);
            size_t l = 0; uint8_t *b = buildSer(w, 1000, 0, &l);
            if (!b) built = 0;
            else if (!ref) { ref = b; rl = l; }
            else { if (l != rl || memcmp(b, ref, l) != 0) same = 0; free(b); }
            BRWalletFree(w);
        }
        ck(built && same, "a retry transfer builds and is identical for any input order");
        free(ref);
    }

    // ---- (3e) many equal coins -------------------------------------------------------------
    {   // six $5.00 coins, send $10.00 -> two coins, change 0 (base already builds; new identical)
        int64_t c[6] = { 500, 500, 500, 500, 500, 500 };
        BRWallet *w = mkWallet(c, 6, NULL);
        size_t lb = 0, ln = 0;
        uint8_t *bb = buildSer(w, 1000, 1, &lb);
        uint8_t *nn = buildSer(w, 1000, 0, &ln);
        ck(bb && nn && lb == ln && memcmp(bb, nn, lb) == 0,
           "many equal coins: exact multiple builds byte-identically to the base");
        BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 1000);
        int ddi = 0;
        if (tx) for (size_t i = 0; i < tx->inCount; i++)
            if (tx->inputs[i].scriptLen == 34 && tx->inputs[i].script[0] == 0x51) ddi++;
        ck(tx && ddi == 2, "many equal coins: two DD inputs cover $10.00 with no change");
        if (tx) BRTransactionFree(tx);
        free(bb); free(nn); BRWalletFree(w);
    }

    // ---- (4) completeness: subset enumeration decides, the builder must agree ---------------
    completeness();

    // ---- (1) the differential corpus -------------------------------------------------------
    corpus();

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
