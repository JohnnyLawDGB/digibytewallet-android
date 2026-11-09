// Host KAT for BRFiatAmount.h: dollar text <-> cents, and cents <-> satoshis at a market rate.
// Carries Android's UsdCentsTest.kt case for case with its values (the Kotlin UsdCents is the
// reference these were written against), plus vectors computed independently of the C under
// test, in Python, from repr(float) and fractions.Fraction (exact rationals; round() on a
// Fraction is HALF_EVEN):
//
//   rate(x)            = Decimal(repr(x)), or Decimal('%.1e' % x) when repr has one digit
//                        (JDK 19+ Double.toString prints at least two)
//   cents_for_sats(s,x)= round(Fraction(s) * Fraction(rate(x)) / 10**6)      refused if > 2^63-1
//   sats_for_cents(c,x)= round(Fraction(c) * 10**6 / Fraction(rate(x)))      refused if > 2^63-1
//   naive (RED shape)  = int(s / 1e8 * x * 100 + 0.5), int(c / 100 / x * 1e8 + 0.5)
//
// Built by run.sh:
//   -DFIAT_VIA_DOUBLE_UNFIXED  the naive double conversion: every RED case fails
//   (default)                  the exact conversion: every case passes
// The file is also compiled a second time with -DFIAT_KAT_SECOND_TU and linked in, to prove the
// header links from more than one translation unit.

#include "BRFiatAmount.h"
#include "BRFiatAmount.h"   // twice: the include guard holds

#ifdef FIAT_KAT_SECOND_TU
size_t fiat_kat_second_tu(char *out, size_t outLen);
size_t fiat_kat_second_tu(char *out, size_t outLen) { return BRUsdCentsFormat(12345, out, outLen); }
#else

#include <locale.h>
#include <math.h>
#include <string.h>

size_t fiat_kat_second_tu(char *out, size_t outLen);

#define I64MAX INT64_C(9223372036854775807)

static int g_failed = 0, g_caseFailed = 0;
static const char *g_case = "";

static void begin(const char *name) { g_case = name; g_caseFailed = 0; }
static void end_case(void)
{
    if (g_caseFailed) g_failed = 1;
    printf("RESULT %s %s\n", g_case, g_caseFailed ? "fail" : "pass");
}
static void fail(const char *fmt, const char *a, long long b, long long c)
{
    if (! g_caseFailed) { printf("FAIL: %s: ", g_case); printf(fmt, a, b, c); printf("\n"); }
    g_caseFailed = 1;
}

// UsdCents.parse(text) == cents, or null when ok == 0
static void parse(const char *text, int ok, int64_t cents)
{
    int64_t got = -1;
    int r = BRUsdCentsParse(text, strlen(text), &got);
    if (r != ok || (ok && got != cents))
        fail("parse(\"%s\") -> %lld (ok=%lld)", text, (long long)got, (long long)r);
}
static void format(int64_t cents, const char *want)
{
    char out[BR_FIAT_TEXT_BUFSIZE];
    size_t n = BRUsdCentsFormat(cents, out, sizeof(out));
    if (n == 0 || strcmp(out, want) != 0 || n != strlen(want))
        fail("format -> \"%s\" for %lld (len %lld)", n ? out : "", (long long)cents, (long long)n);
}
static void preview(const char *dgb, double price, const char *want)
{
    char out[BR_FIAT_TEXT_BUFSIZE];
    size_t n = BRFiatPreviewForDgb(dgb, strlen(dgb), price, out, sizeof(out));
    if (strcmp(out, want) != 0 || n != strlen(want)) {
        if (! g_caseFailed) printf("FAIL: %s: previewForDgb(\"%s\", %.17g) -> \"%s\", want \"%s\"\n", g_case, dgb, price, out, want);
        g_caseFailed = 1;
    }
}
static void dgbtext(const char *usd, double price, const char *want)
{
    char out[BR_FIAT_TEXT_BUFSIZE];
    size_t n = BRFiatDgbTextFor(usd, strlen(usd), price, out, sizeof(out));
    if (strcmp(out, want) != 0 || n != strlen(want)) {
        if (! g_caseFailed) printf("FAIL: %s: dgbTextFor(\"%s\", %.17g) -> \"%s\", want \"%s\"\n", g_case, usd, price, out, want);
        g_caseFailed = 1;
    }
}
static void rate(double x, int ok, uint64_t digits, int32_t exp)
{
    BRFiatRate r = { 0, 0 };
    int got = BRFiatRateFromDouble(x, &r);
    if (got != ok || (ok && (r.digits != digits || r.exp != exp))) {
        if (! g_caseFailed) printf("FAIL: %s: rate(%.17g) -> ok=%d %llue%d, want ok=%d %llue%d\n", g_case, x, got,
                                   (unsigned long long)r.digits, (int)r.exp, ok, (unsigned long long)digits, (int)exp);
        g_caseFailed = 1;
    }
}
static BRFiatRate R(double x)
{
    BRFiatRate r = { 0, 0 };
    if (! BRFiatRateFromDouble(x, &r)) { printf("FAIL: %s: no rate for %.17g\n", g_case, x); g_caseFailed = 1; }
    return r;
}
// cents_for_sats(sats, x) == cents, refused when ok == 0
static void c4s(int64_t sats, double x, int ok, int64_t cents)
{
    int64_t got = -1;
    int r = BRFiatCentsForSats(sats, R(x), &got);
    if (r != ok || (ok && got != cents)) {
        if (! g_caseFailed) printf("FAIL: %s: centsForSats(%lld, %.17g) -> ok=%d %lld, want ok=%d %lld\n", g_case,
                                   (long long)sats, x, r, (long long)got, ok, (long long)cents);
        g_caseFailed = 1;
    }
}
static void s4c(int64_t cents, double x, int ok, int64_t sats)
{
    int64_t got = -1;
    int r = BRFiatSatsForCents(cents, R(x), &got);
    if (r != ok || (ok && got != sats)) {
        if (! g_caseFailed) printf("FAIL: %s: satsForCents(%lld, %.17g) -> ok=%d %lld, want ok=%d %lld\n", g_case,
                                   (long long)cents, x, r, (long long)got, ok, (long long)sats);
        g_caseFailed = 1;
    }
}

int main(void)
{
    // ==== GUARD: UsdCentsTest.kt's parse and format cases, and the rate. Independent of how ====
    // ==== the conversion is done, so they hold in both arms.                                 ====

    begin("whole_cents_convert_exactly");
    parse("40", 1, 4000); parse("40.00", 1, 4000); parse("40.50", 1, 4050); parse("40.5", 1, 4050);
    parse("0.09", 1, 9); parse(".5", 1, 50); parse("1.00", 1, 100); parse("100000.00", 1, 10000000);
    parse("  0.29 ", 1, 29); parse("0", 1, 0);
    parse("5.", 1, 500); parse("\t7\n", 1, 700);                       // BigDecimal("5.") is 5
    end_case();

    begin("more_than_two_typed_decimals_refused");
    parse("1.005", 0, 0); parse("1.0000", 0, 0); parse("0.001", 0, 0); parse("2.675", 0, 0);
    parse("40.500", 0, 0);
    end_case();

    begin("separators_read_the_dgb_way");
    parse("1,50", 1, 150); parse("0,1", 1, 10); parse("1,234.50", 1, 123450);
    parse("1,234,567.5", 1, 123456750);
    parse("1,234", 0, 0); parse("1,2,3", 0, 0); parse("1,234,567", 0, 0);
    parse(",1.5", 1, 150); parse("1.5,", 1, 150);                        // Kotlin drops every comma
    parse(",.", 0, 0); parse(",", 0, 0);
    end_case();

    begin("exponents_signs_nonfinite_refused");
    parse("1e5", 0, 0); parse("1E2", 0, 0); parse("NaN", 0, 0); parse("Infinity", 0, 0);
    parse("+1", 0, 0); parse("-1", 0, 0); parse("-0", 0, 0); parse("1.5f", 0, 0); parse("0x10", 0, 0);
    end_case();

    begin("empty_and_nonnumeric_refused");
    parse("", 0, 0); parse("   ", 0, 0); parse(".", 0, 0); parse("abc", 0, 0); parse("1.2.3", 0, 0);
    parse("1 000", 0, 0); parse("$5", 0, 0);
    parse("\xc2\xa0" "5", 0, 0);       // NBSP: Kotlin trims it; refused here (ASCII only, deliberately)
    parse("\xd9\xa5", 0, 0);           // ARABIC-INDIC FIVE: Kotlin's isDigit takes it; refused here
    {
        int64_t c = 7;
        if (BRUsdCentsParse(NULL, 0, &c) || BRUsdCentsParse("1", 1, NULL)) fail("NULL accepted%s%lld%lld", "", 0, 0);
        if (! BRUsdCentsParse("12.34xyz", 5, &c) || c != 1234) fail("len not honoured%s%lld%lld", "", c, 0);
    }
    end_case();

    begin("out_of_range_refused_exact_ceiling_accepted");
    parse("92233720368547758.07", 1, I64MAX); parse("92233720368547758.08", 0, 0);
    parse("99999999999999999999", 0, 0);
    {
        static char big[100001];
        memset(big, '9', 100000); big[100000] = '\0';
        parse(big, 0, 0);
    }
    // the 32-character bound is on the text once grouping commas are dropped
    parse("00000000000000000000000000000001", 1, 100);      // 32 characters
    parse("000000000000000000000000000000001", 0, 0);       // 33
    parse("00,000,000,000,000,000,000,000,000,000.01", 1, 1);      // 41 typed, 32 once commas go
    parse("000,000,000,000,000,000,000,000,000,000.01", 0, 0);     // 33 once commas go
    parse("0000000000000000000000000000000,1", 0, 0);      // 33: a lone comma is the point, counted
    end_case();

    begin("format_plain_two_decimals");
    format(5000, "50.00"); format(9, "0.09"); format(0, "0.00"); format(123456, "1234.56");
    format(10000000, "100000.00");
    format(I64MAX, "92233720368547758.07");
    format(-5, "-0.05"); format(-123456, "-1234.56");                       // BigDecimal.toPlainString
    format(INT64_MIN, "-92233720368547758.08");
    {
        char small[5];
        if (BRUsdCentsFormat(5000, small, sizeof(small)) != 0) fail("short buffer written%s%lld%lld", "", 0, 0);
        if (BRUsdCentsFormat(500, small, sizeof(small)) != 4 || strcmp(small, "5.00")) fail("exact buffer%s%lld%lld", "", 0, 0);
    }
    end_case();

    begin("every_cent_value_survives_the_round_trip");
    {
        char out[BR_FIAT_TEXT_BUFSIZE];
        int64_t c, back;
        static const int64_t more[] = { 10000000, 123456789012LL, I64MAX };
        for (c = 0; c <= 200000; c++) {
            BRUsdCentsFormat(c, out, sizeof(out));
            if (! BRUsdCentsParse(out, strlen(out), &back) || back != c) { fail("%s did not come back as %lld%lld", out, c, 0); break; }
        }
        for (int i = 0; i < 3; i++) {
            BRUsdCentsFormat(more[i], out, sizeof(out));
            if (! BRUsdCentsParse(out, strlen(out), &back) || back != more[i]) fail("%s did not come back as %lld%lld", out, more[i], 0);
        }
    }
    end_case();

    // The rate is the decimal BigDecimal.valueOf(double) reads: Python's repr(float), except one
    // digit becomes two (Double.toString(Double.MIN_VALUE) is "4.9E-324").
    begin("rate_is_the_shortest_decimal_that_reads_back");
    rate(0.1, 1, 1, -1); rate(0.00612, 1, 612, -5); rate(1e23, 1, 1, 23); rate(2e-3, 1, 2, -3);
    rate(0.01, 1, 1, -2); rate(1.0, 1, 1, 0); rate(2.0, 1, 2, 0); rate(1.5e6, 1, 15, 5);
    rate(8e6, 1, 8, 6); rate(0.0123, 1, 123, -4);
    rate(ldexp(1.0, -24), 1, 5960464477539063ULL, -23);   // nearest 16 digits is ...062: outside
    rate(ldexp(1.0, -44), 1, 5684341886080802ULL, -29);   // nearest 16 digits is ...801: outside
    rate(5e-324, 1, 49, -325);
    rate(1.7976931348623157e308, 1, 17976931348623157ULL, 292);
    rate(0.30000000000000004, 1, 30000000000000004ULL, -17);
    rate(0.0, 0, 0, 0); rate(-0.0, 0, 0, 0); rate(-1.0, 0, 0, 0); rate(NAN, 0, 0, 0);
    rate(INFINITY, 0, 0, 0); rate(-INFINITY, 0, 0, 0);
    if (BRFiatRateFromDouble(1.0, NULL)) fail("NULL rate accepted%s%lld%lld", "", 0, 0);
    end_case();

    begin("preview_empty_without_amount_or_price");
    preview("", 0.01, ""); preview("abc", 0.01, ""); preview("1,2,3", 0.01, "");
    preview("1", 0.0, ""); preview("1", -1.0, ""); preview("1", NAN, ""); preview("1", INFINITY, "");
    dgbtext("", 0.01, ""); dgbtext("1.005", 0.01, ""); dgbtext("5", 0.0, ""); dgbtext("5", NAN, "");
    dgbtext("-5", 0.01, ""); dgbtext("1e2", 0.01, "");
    end_case();

    // Kotlin's own conversion vectors. The naive double conversion happens to get every one of
    // them right, which is why the cases below exist.
    begin("kotlin_conversion_vectors");
    preview("0,5", 2.0, "1.00"); preview("0.5", 2.0, "1.00"); preview("1,234.5", 1.0, "1234.50");
    preview("100", 0.00612, "0.61"); preview("0", 0.00612, "0.00"); preview("1234.5", 1.0, "1234.50");
    dgbtext("12.34", 0.01, "1234"); dgbtext("0,5", 0.01, "50"); dgbtext("0.50", 0.01, "50");
    dgbtext("5", 0.00612, "816.99346405");
    {
        char out[BR_FIAT_TEXT_BUFSIZE];
        int64_t v;
        BRFiatDgbTextFor("12.34", 5, 0.01, out, sizeof(out));
        if (! BRAmountParseDGB(out, strlen(out), &v) || v != 123400000000LL) fail("%s is not 1234 DGB%lld%lld", out, v, 0);
        BRFiatPreviewForDgb("1234.5", 6, 1.0, out, sizeof(out));
        if (! BRUsdCentsParse(out, strlen(out), &v) || v != 123450) fail("%s is not 123450 cents%lld%lld", out, v, 0);
    }
    // the 0.29 class: exact in both directions at a cent per DGB
    preview("0.29", 0.01, "0.00"); dgbtext("0.29", 0.01, "29"); s4c(29, 0.01, 1, 2900000000LL);
    c4s(29000000, 0.01, 1, 0);
    end_case();

    // The text goes back into an amount field, so it cannot depend on the C locale. On a host
    // with no comma-decimal locale installed, this runs in "C" and says so.
    begin("c_locale_decimal_point_guard");
    {
        static const char *names[] = { "de_DE.UTF-8", "de_DE.utf8", "de_DE", "fr_FR.UTF-8", "fr_FR.utf8", "nl_NL.UTF-8" };
        const char *used = NULL;
        char probe[16];
        for (size_t i = 0; i < sizeof(names) / sizeof(names[0]) && ! used; i++) {
            if (setlocale(LC_NUMERIC, names[i])) {
                snprintf(probe, sizeof(probe), "%.1f", 1.5);
                if (strchr(probe, ',')) used = names[i]; else setlocale(LC_NUMERIC, "C");
            }
        }
        printf("NOTE: locale guard ran in %s\n", used ? used : "\"C\" (no comma-decimal locale on this host)");
        rate(0.00612, 1, 612, -5); rate(0.1, 1, 1, -1); rate(ldexp(1.0, -24), 1, 5960464477539063ULL, -23);
        rate(5e-324, 1, 49, -325);
        format(123456, "1234.56"); format(5000, "50.00");
        preview("100", 0.00612, "0.61"); preview("1,234.5", 1.0, "1234.50");
        dgbtext("5", 0.00612, "816.99346405"); parse("1,50", 1, 150);
        setlocale(LC_NUMERIC, "C");
    }
    end_case();

    begin("header_links_from_two_units");
    {
        char out[BR_FIAT_TEXT_BUFSIZE];
        if (fiat_kat_second_tu(out, sizeof(out)) != 6 || strcmp(out, "123.45")) fail("second unit wrote %s%lld%lld", out, 0, 0);
    }
    end_case();

    // ==== RED: the naive double conversion gets every one of these wrong ======================

    // Exact decimal ties round to even. Half up gets 0.5 -> 1 and 2.5 -> 3; a binary 0.1 or 0.002
    // is not the decimal the user was shown, but the rate is.
    begin("cents_round_half_even");
    c4s(50000000, 0.01, 1, 0); c4s(150000000, 0.01, 1, 2); c4s(250000000, 0.01, 1, 2);
    c4s(5000000, 0.1, 1, 0); c4s(15000000, 0.1, 1, 2); c4s(25000000, 0.1, 1, 2);
    c4s(250000000, 2e-3, 1, 0); c4s(750000000, 2e-3, 1, 2);
    preview("0.5", 0.01, "0.00"); preview("2.5", 0.01, "0.02"); preview("1.5", 0.01, "0.02");
    end_case();

    begin("sats_round_half_even");
    s4c(4, 8e6, 1, 0); s4c(12, 8e6, 1, 2); s4c(20, 8e6, 1, 2);
    dgbtext("0.04", 8e6, "0"); dgbtext("0.20", 8e6, "0.00000002");
    end_case();

    // Beyond 2^53 a double cannot hold the amount; the rate is exact and so is the answer.
    begin("exact_beyond_double_precision");
    c4s(1, 1e23, 1, 100000000000000000LL); c4s(92, 1e23, 1, INT64_C(9200000000000000000));
    c4s(9007199254740993LL, 1e6, 1, 9007199254740993LL);
    s4c(9007199254740993LL, 1e6, 1, 9007199254740993LL);
    s4c(123456789, 0.01, 1, 12345678900000000LL);
    dgbtext("1234567.89", 0.01, "123456789");
    end_case();

    // The int64 ceiling: reached exactly, refused one past it, and refused when HALF_EVEN rounds
    // a .5 above INT64_MAX (odd) up to 2^63. Absurd rates are bounded before they are scaled.
    begin("ceiling_and_overflow");
    c4s(I64MAX, 1e6, 1, I64MAX); c4s(I64MAX, 1000001.0, 0, 0); c4s(100, 1e23, 0, 0);
    c4s(INT64_C(6148914691236517205), 1.5e6, 0, 0);
    c4s(INT64_C(6148914691236517204), 1.5e6, 1, INT64_C(9223372036854775806));
    c4s(1, 1.7976931348623157e308, 0, 0);
    s4c(I64MAX, 1e6, 1, I64MAX); s4c(I64MAX, 0.01, 0, 0); s4c(1, 5e-324, 0, 0);
    s4c(3, ldexp(1.0, -44), 0, 0);
    dgbtext("92233720368547758.07", 0.01, "");
    end_case();

    // ==== GUARD: the rest of the independent vectors, which the naive shape also gets right ====
    begin("independent_vectors");
    c4s(150000000, 0.01, 1, 2); c4s(10000000000LL, 0.00612, 1, 61); c4s(I64MAX, 1.0, 1, 9223372036855LL);
    c4s(I64MAX, 5e-324, 1, 0); c4s(0, 1.7976931348623157e308, 1, 0);
    c4s(100000000000000LL, ldexp(1.0, -24), 1, 6); c4s(1000000000000000000LL, ldexp(1.0, -44), 1, 0);
    c4s(123456789, 0.00612, 1, 1);
    s4c(5, 0.00612, 1, 816993464); s4c(1, 0.4, 1, 2500000); s4c(1, 1e23, 1, 0); s4c(I64MAX, 1e23, 1, 92);
    s4c(1, 2e-3, 1, 500000000); s4c(0, 5e-324, 1, 0); s4c(I64MAX, 1.7976931348623157e308, 1, 0);
    s4c(100, 0.1, 1, 1000000000); s4c(5, 0.1, 1, 50000000); s4c(1, ldexp(1.0, -24), 1, 16777216000000LL);
    s4c(1234567, 0.0123, 1, 100371300813008LL);
    {
        int64_t v = 7;
        BRFiatRate zero = { 0, 0 };
        if (BRFiatCentsForSats(-1, R(1.0), &v) || BRFiatSatsForCents(-1, R(1.0), &v)) fail("negative accepted%s%lld%lld", "", 0, 0);
        if (BRFiatCentsForSats(1, zero, &v) || BRFiatSatsForCents(1, zero, &v)) fail("zero rate accepted%s%lld%lld", "", 0, 0);
    }
    end_case();

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
#endif
