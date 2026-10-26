// Host KAT for BRFeePolicy.h's 4.1.0 rule: how the default and a custom total become the rate a DGB
// send is built at, and when the fee shown was raised above the total typed.
//
// Source of truth: Android 4.1.0 -- core/send/FeeQuote.kt (rateForTotal, raised, FeeRequest.of),
// TransactionBuilder.prepareTransaction (build at the default rate, then a custom total over THAT
// transaction's vsize, at least 1 sat/kB), and FeeQuoteTest.kt, whose cases this carries with the
// same values. The quote itself (a transaction's inputs minus its outputs) is fee_quote_kat's.
//
// Review is driven here as prepareTransaction drives it, against a recording builder shaped like the
// Kotlin test's RecordingSpendNative: built bytes are named by the rate they were built at
// ("tx@<rate>"), each case says which [fee, vsize] each one quotes, and bytes the case gave no quote
// have none (review then stops, as Kotlin's does on an unquoted build). The decisions -- which
// request, which rate, raised or not -- are the header's; the driver only sequences them.
//
// FeeQuoteTest's last case ("the quote is the bridge's fee and size, or nothing") is carried for its
// typed total only (typed_total_rides_with_quote). Its other half -- a JNI long[] that is null, not
// two long, or holds a negative fee or a zero size, is no quote -- is the bridge's array, whose C
// side is the quote's own "none" (prepared_send_quote, fee_quote_kat), not this header's.
//
// Built by run.sh in two arms:
//   -DFEE_POLICY_QUOTE_UNFIXED=1  the comparison arm, the 4.0.89 rule: a total over a typical 141
//                                 vbytes, a custom fee that is not an amount built at the default
//                                 rate, nothing ever "raised". Each case meets run.sh's expectation.
//   -DFEE_POLICY_QUOTE_UNFIXED=0  what ships: every case passes.
// One process runs every case; each prints RESULT <case> pass|fail. "list" names them.

#include "BRFeePolicy.h"
#include "BRFeePolicy.h"   // twice: the include guard holds
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

uint64_t fee_policy_quote_kat_tu2(int64_t totalSats, int64_t vsize);   // run.sh's second TU

static int g_case_failed = 0, g_failed = 0;

static void check(int ok, const char *what)
{
    if (! ok) { printf("FAIL: %s\n", what); g_case_failed = 1; }
}

// ---- the recording builder ------------------------------------------------------------------

typedef struct { uint64_t rate; int64_t fee, vsize; } Quote;   // "tx@<rate>" quotes [fee, vsize]

static const Quote *g_quotes = NULL;
static size_t g_quoteCount = 0;
static char g_events[512];

static void quotes(const Quote *q, size_t n) { g_quotes = q; g_quoteCount = n; g_events[0] = '\0'; }

// createTransaction at rate, then quoteTransaction: 1 with the quote, 0 when the bytes have none.
static int buildAndQuote(uint64_t rate, int64_t *fee, int64_t *vsize)
{
    size_t i, len = strlen(g_events);

    snprintf(g_events + len, sizeof(g_events) - len, "%screate@%" PRIu64, len ? " " : "", rate);
    for (i = 0; i < g_quoteCount; i++) {
        if (g_quotes[i].rate == rate) { *fee = g_quotes[i].fee; *vsize = g_quotes[i].vsize; return 1; }
    }
    return 0;
}

static int built(uint64_t rate)
{
    char want[32];
    const char *p = g_events;
    size_t n;

    snprintf(want, sizeof(want), "create@%" PRIu64, rate);
    n = strlen(want);
    while ((p = strstr(p, want)) != NULL) {
        if ((p == g_events || p[-1] == ' ') && (p[n] == ' ' || p[n] == '\0')) return 1;
        p += n;
    }
    return 0;
}

typedef struct {
    int ready;          // a send was built and quoted
    uint64_t rate;      // the rate the send's bytes were built at
    int64_t fee, vsize; // their quote
    int raised;         // BRFeeQuoteRaised
} Review;

// TransactionBuilder.prepareTransaction, the fee half. total is the request's total (CustomTotal).
static Review review(BRFeeRequest request, int64_t total)
{
    Review r = { 0, 0, 0, 0, 0 };
    int custom = (request == BRFeeRequestCustomTotal);

    if (request == BRFeeRequestNone) return r;                        // nothing is quoted
    if (! buildAndQuote(DEFAULT_FEE_PER_KB, &r.fee, &r.vsize)) return r;   // at the default rate first
    r.rate = DEFAULT_FEE_PER_KB;
    if (custom) {
        r.rate = BRFeeRateForCustomTotal(total, r.vsize);             // over THIS transaction's size
        if (! buildAndQuote(r.rate, &r.fee, &r.vsize)) return r;
    }
    r.ready = 1;
    r.raised = BRFeeQuoteRaised(custom, total, r.fee);
    return r;
}

static const char *why(char *buf, size_t n, const char *what, const Review *r)
{
    snprintf(buf, n, "%s [ready %d, rate %" PRIu64 ", fee %" PRId64 ", vsize %" PRId64 ", raised %d; built: %s]",
             what, r->ready, r->rate, r->fee, r->vsize, r->raised, g_events);
    return buf;
}

// ---- cases ------------------------------------------------------------------------------------

// FeeQuoteTest: a six-input transaction is quoted at the fee it pays, not at a typical 141 bytes.
static void six_input_default_quoted_as_built(void)
{
    // Six P2WPKH coins gathered into one send: 750 vB, 75,000 sat at 100 sat/vB.
    static const Quote q[] = { { 100000, 75000, 750 } };
    char b[400];
    Review r;

    quotes(q, 1);
    r = review(BRFeeRequestDefault, 0);
    check(r.ready && r.fee == 75000 && r.vsize == 750,
          why(b, sizeof(b), "a default-fee send is the transaction built at the default rate, quoted as built", &r));
    check(r.rate == 100000 && built(100000), why(b, sizeof(b), "it is built at DEFAULT_FEE_PER_KB, 100,000 sat/kB", &r));
    check(! r.raised, why(b, sizeof(b), "a default-rate fee is never 'raised'", &r));
}

// FeeQuoteTest: a custom total below the floor is shown raised to the fee the transaction pays.
static void custom_below_floor_shown_raised(void)
{
    // The transaction is 208 vB. Any rate under 100 sat/vB builds at the floor: 23,200 sat.
    static const Quote q[] = { { 100000, 23200, 208 }, { 1000ULL * 1000 / 208, 23200, 208 } };
    char b[400];
    Review r;

    quotes(q, 2);
    r = review(BRFeeRequestCustomTotal, 1000);
    check(r.ready && r.fee == 23200,
          why(b, sizeof(b), "a custom fee below the floor is shown as the fee signed (23,200), not as typed", &r));
    check(r.raised, why(b, sizeof(b), "a fee the builder raised is reported raised", &r));
    check(built(4807) && r.rate == 4807,
          why(b, sizeof(b), "the custom total is a rate over this transaction's 208 vB: 1,000 x 1000 / 208 = 4,807", &r));
}

// FeeQuoteTest: a custom total the transaction pays exactly is not shown raised.
static void custom_paid_exactly_not_raised(void)
{
    static const Quote q[] = { { 100000, 23200, 208 }, { 50000ULL * 1000 / 208, 50000, 208 } };
    char b[400];
    Review r;

    quotes(q, 2);
    r = review(BRFeeRequestCustomTotal, 50000);
    check(r.ready && r.fee == 50000 && r.rate == 240384, why(b, sizeof(b), "built at 240,384 sat/kB, paying 50,000", &r));
    check(! r.raised, why(b, sizeof(b), "a total the transaction pays exactly is not 'raised'", &r));
}

// FeeQuoteTest: a custom total becomes a rate over the transaction's own size.
static void custom_rate_over_own_size(void)
{
    char b[200];

    snprintf(b, sizeof(b), "50,000 over 208 vB is 240,384 sat/kB, not 354,609 over a typical 141 (got %" PRIu64 ")",
             BRFeeRateForTotal(50000, 208));
    check(BRFeeRateForTotal(50000, 208) == 240384, b);
    check(BRFeeRateForTotal(20800, 208) == 100000, "20,800 over 208 vB is 100,000 sat/kB");
    check(BRFeeRateForTotal(50000, 0) == 0, "no rate is derived from a size of zero");
    check(BRFeeRateForTotal(INT64_MAX, 1) == (uint64_t)INT64_MAX, "an absurd total saturates instead of wrapping");
}

// FeeQuoteTest: a custom fee that is not a positive amount quotes nothing.
static void custom_not_positive_quotes_nothing(void)
{
    static const char *const typed[] = { "0", "0.00000000", "", "abc", "-1" };
    static const Quote q[] = { { 100000, 23200, 208 } };
    char b[200];
    int64_t total;
    size_t i;
    BRFeeRequest req;
    Review r;

    for (i = 0; i < sizeof(typed) / sizeof(typed[0]); i++) {
        total = -7;
        req = BRFeeRequestFor(1, typed[i], strlen(typed[i]), &total);
        snprintf(b, sizeof(b), "a custom fee of \"%s\" is no fee: request %d (want %d, none), total %" PRId64,
                 typed[i], (int)req, (int)BRFeeRequestNone, total);
        check(req == BRFeeRequestNone && total == 0, b);
        quotes(q, 1);
        r = review(req, total);
        snprintf(b, sizeof(b), "\"%s\": review quotes nothing and builds nothing (built: %s)", typed[i], g_events);
        check(! r.ready && g_events[0] == '\0', b);
    }
    req = BRFeeRequestFor(1, "0.0005", 6, &total);
    check(req == BRFeeRequestCustomTotal && total == 50000, "\"0.0005\" is a custom total of 50,000 sat");
    req = BRFeeRequestFor(0, "abc", 3, &total);
    check(req == BRFeeRequestDefault && total == 0, "with no custom fee the field's text is not read: the default rate");
}

// FeeQuoteTest: the quote is the bridge's fee and size, or nothing -- its typed-total half (see top).
static void typed_total_rides_with_quote(void)
{
    // FeeQuote(75_000, 750): default; FeeQuote(23_200, 208, requestedSats = 1_000): custom.
    check(! BRFeeQuoteRaised(0, 0, 75000), "a default quote of 75,000 sat is not raised");
    check(BRFeeQuoteRaised(1, 1000, 23200), "a quote of 23,200 sat for a typed 1,000 is raised");
}

// TransactionBuilder: a rate of 0 would fall back to the wallet's own rate; 1 sat/kB builds at the floor.
static void custom_rate_never_zero(void)
{
    char b[200];

    check(BRFeeRateForTotal(1, 2000) == 0 && BRFeeRateForTotal(1, 1001) == 0 && BRFeeRateForTotal(1, 1000) == 1,
          "1 sat over 2,000 or 1,001 vB rounds down to 0 sat/kB; over 1,000 vB it is 1");
    snprintf(b, sizeof(b), "a custom total whose rate rounds to 0 builds at 1 sat/kB, never 0 (got %" PRIu64 ", %" PRIu64 ")",
             BRFeeRateForCustomTotal(1, 2000), BRFeeRateForCustomTotal(1, 1001));
    check(BRFeeRateForCustomTotal(1, 2000) == 1 && BRFeeRateForCustomTotal(1, 1001) == 1, b);
    check(BRFeeRateForTotal(0, 208) == 0 && BRFeeRateForTotal(-5, 208) == 0 && BRFeeRateForTotal(50000, -1) == 0,
          "no rate for a total or a size that is not positive");
    check(BRFeeRateForCustomTotal(50000, 0) == 1 && BRFeeRateForCustomTotal(0, 208) == 1,
          "even unreachable inputs never yield 0 (Kotlin: rateForTotal(..).coerceAtLeast(1))");
}

// FeeQuote.rateForTotal is BigInteger floor(total x 1000 / vsize): exact at every size, saturating
// only above Long.MAX_VALUE. Values from Python's integer arithmetic.
static void rate_exact_then_saturated(void)
{
    static const struct { int64_t t, v; uint64_t want; } k[] = {
        { 1000000000000000LL, 208, 4807692307692307ULL },              // 10M DGB over 208 vB
        { INT64_MAX, 1000, 9223372036854775807ULL },                   // exact, at the top
        { INT64_MAX, 1001, 9214157878975800006ULL },                   // exact, just below it
        { INT64_MAX, 999, 9223372036854775807ULL },                    // above Long.MAX_VALUE: saturated
        { INT64_MAX, INT64_MAX, 1000 },
        { INT64_MAX - 1, INT64_MAX, 999 },
        { INT64_MAX, INT64_MAX - 1, 1000 },
        { INT64_MAX, 4611686018427387904LL, 1999 },                    // over 2^62 vB: the wide path
        { 123456789012345678LL, 98765432109876543LL, 1249 },
        { 23200, 208, 111538 },
        { 75000, 750, 100000 },
        // Each row below kills a mutant of BRFeeRateForTotal / _BRFeeMulDiv1000 that every row
        // above survives (checked against a scratch-patched header).
        { 36, 750, 48 },                                               // the remainder lands EXACTLY on vsize after
        { 8, 100, 80 },                                                //   `rem += r`: `rem >= v` there, not `>`
        { 4611686018427387950LL, 500, 9223372036854775807ULL },        // exact 9223372036854775900, q = INT64_MAX/1000:
                                                                       //   only the final clamp saturates it
        { 4611686018427387904LL, 1, 9223372036854775807ULL },          // 2^62 over 1 vB: q x 1000 wraps to 0 without
                                                                       //   the first clamp
        { 9223372036854775000LL, 1000, 9223372036854775000ULL },       // q = INT64_MAX/1000 exactly: exact, not
                                                                       //   saturated (`q >`, never `q >=`)
    };
    char b[200];
    size_t i;
    uint64_t got;

    for (i = 0; i < sizeof(k) / sizeof(k[0]); i++) {
        got = BRFeeRateForTotal(k[i].t, k[i].v);
        snprintf(b, sizeof(b), "%" PRId64 " over %" PRId64 " vB: %" PRIu64 " sat/kB (want %" PRIu64 ")",
                 k[i].t, k[i].v, got, k[i].want);
        check(got == k[i].want, b);
    }
    check(fee_policy_quote_kat_tu2(50000, 208) == 240384, "a second translation unit links and agrees");
}

// FeeQuote.raised: requestedSats != null && feeSats > requestedSats. Strictly more; a custom total only.
static void raised_strictly_more(void)
{
    check(BRFeeQuoteRaised(1, 50000, 50001), "one satoshi over the total typed is raised");
    check(! BRFeeQuoteRaised(1, 50000, 50000), "the total typed, exactly, is not raised");
    check(! BRFeeQuoteRaised(1, 50000, 49999), "less than the total typed is not raised");
    check(! BRFeeQuoteRaised(0, 1000, 23200), "a default-fee send is never raised, whatever total the field last held");
    check(BRFeeQuoteRaised(1, INT64_MAX - 1, INT64_MAX) && ! BRFeeQuoteRaised(1, INT64_MAX, INT64_MAX),
          "at the top of the range, too");
}

// FeeQuote.DEFAULT_FEE_PER_KB = 100_000 is the core's DEFAULT_FEE_PER_KB, and the default request
// is the default whatever the field holds. Holds in both arms.
static void default_rate_is_kotlins(void)
{
    int64_t total = -7;

    check(DEFAULT_FEE_PER_KB == 100000, "DEFAULT_FEE_PER_KB is 100,000 sat/kB, FeeQuote.DEFAULT_FEE_PER_KB");
    check(BRFeeRequestFor(0, "0.0005", 6, &total) == BRFeeRequestDefault && total == 0,
          "with no custom fee, the request is the default and carries no total");
    check(BRFeeRequestFor(0, NULL, 0, NULL) == BRFeeRequestDefault, "and needs no text");
    check(BRFeeRequestNone == 0, "the zero request builds nothing");
}

// ---- driver -----------------------------------------------------------------------------------

static const struct { const char *name; void (*run)(void); } CASES[] = {
    { "six_input_default_quoted_as_built", six_input_default_quoted_as_built },
    { "custom_below_floor_shown_raised", custom_below_floor_shown_raised },
    { "custom_paid_exactly_not_raised", custom_paid_exactly_not_raised },
    { "custom_rate_over_own_size", custom_rate_over_own_size },
    { "custom_not_positive_quotes_nothing", custom_not_positive_quotes_nothing },
    { "typed_total_rides_with_quote", typed_total_rides_with_quote },
    { "custom_rate_never_zero", custom_rate_never_zero },
    { "rate_exact_then_saturated", rate_exact_then_saturated },
    { "raised_strictly_more", raised_strictly_more },
    { "default_rate_is_kotlins", default_rate_is_kotlins },
};

int main(int argc, char **argv)
{
    size_t i;

    if (argc > 1 && strcmp(argv[1], "list") == 0) {
        for (i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) {
        g_case_failed = 0;
        CASES[i].run();
        printf("RESULT %s %s\n", CASES[i].name, g_case_failed ? "fail" : "pass");
        g_failed |= g_case_failed;
    }
    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
