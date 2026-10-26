// Host KAT for BRAmountParse.h, carrying Android's DgbAmountTest.kt case for case with its
// values (the Kotlin DgbAmount is the reference these were written against).
//
// Built twice by run.sh:
//   -DAMOUNT_PARSE_VIA_DOUBLE_UNFIXED  the old double conversion: every RED case fails
//   (default)                          the exact parser: every case passes

#include "BRAmountParse.h"
#include <stdio.h>
#include <string.h>

static int g_failed = 0, g_caseFailed = 0;
static const char *g_case = "";

static void begin(const char *name) { g_case = name; g_caseFailed = 0; }
static void end_case(void)
{
    if (g_caseFailed) g_failed = 1;
    printf("RESULT %s %s\n", g_case, g_caseFailed ? "fail" : "pass");
}
static void expect(const char *text, int ok, int64_t sats)
{
    int64_t got = -1;
    int r = BRAmountParseDGB(text, strlen(text), &got);
    if (r != ok || (ok && got != sats)) {
        if (! g_caseFailed) printf("FAIL: %s: \"%s\" -> %s %lld, want %s %lld\n", g_case, text,
                                   r ? "ok" : "refused", (long long)got, ok ? "ok" : "refused", (long long)sats);
        g_caseFailed = 1;
    }
}
static void expect_format(int64_t sats, const char *want)
{
    char out[40];
    size_t n = BRAmountFormatDGB(sats, out, sizeof(out));
    if (n == 0 || strcmp(out, want) != 0) {
        if (! g_caseFailed) printf("FAIL: %s: format(%lld) -> \"%s\", want \"%s\"\n", g_case, (long long)sats, n ? out : "", want);
        g_caseFailed = 1;
    }
}

int main(void)
{
    // ---- RED: the double conversion is one satoshi low on these --------------------------
    begin("plain_amounts_exact");
    expect("0.29", 1, 29000000LL); expect("0.57", 1, 57000000LL); expect("2.3", 1, 230000000LL);
    expect("10.7", 1, 1070000000LL); expect("19.99", 1, 1999000000LL); expect("1.5", 1, 150000000LL);
    expect("2", 1, 200000000LL);
    end_case();

    begin("every_dust_code_round_trips");
    {
        static const int64_t wholes[] = { 1, 5, 25, 1000 };
        char text[32], fmt[40];
        for (int w = 0; w < 4; w++) for (int code = 0; code <= 9999; code++) {
            int64_t want = wholes[w] * 100000000LL + code;
            snprintf(text, sizeof(text), "%lld.0000%04d", (long long)wholes[w], code);
            expect(text, 1, want);
            BRAmountFormatDGB(want, fmt, sizeof(fmt));
            expect(fmt, 1, want);
        }
    }
    end_case();

    // ---- GUARD: true of both shapes or independent of the conversion ----------------------
    begin("grouping_and_whitespace");
    expect("1,234.5", 1, 123450000000LL); expect("1,234,567.5", 1, 123456750000000LL);
    expect("  0.29 ", 1, 29000000LL);
    end_case();

    begin("lone_comma_is_the_decimal_point");
    expect("0,29", 1, 29000000LL); expect("0,00014100", 1, 14100LL); expect("1,234", 1, 123400000LL);
    expect("1,2,3", 0, 0); expect("1,234,567", 0, 0);
    end_case();

    begin("absurd_magnitude_refused_without_materialising");
    expect("1e50000000", 0, 0); expect("1e999999999", 0, 0); expect("1e2147483647", 0, 0);
    expect("1e-50000000", 0, 0);
    end_case();

    begin("overflow_refused_ceiling_accepted");
    expect("99999999999999999", 0, 0); expect("92233720368.54775808", 0, 0);
    expect("92233720368.54775807", 1, 9223372036854775807LL); expect("1e2", 1, 10000000000LL);
    end_case();

    begin("more_than_eight_decimals_refused");
    expect("1.123456789", 0, 0); expect("1.100000000", 1, 110000000LL); expect("1.00000000", 1, 100000000LL);
    end_case();

    begin("negative_empty_nonnumeric_refused_zero_allowed");
    expect("-1", 0, 0); expect("", 0, 0); expect("   ", 0, 0); expect("abc", 0, 0);
    expect("1.2.3", 0, 0); expect("0", 1, 0);
    end_case();

    begin("format_plain_decimal");
    expect_format(29000000LL, "0.29"); expect_format(500001234LL, "5.00001234");
    expect_format(100000000LL, "1"); expect_format(123450000000LL, "1234.5");
    expect_format(1LL, "0.00000001"); expect_format(0LL, "0");
    end_case();

    // BigDecimal grammar edges the Kotlin accepts, and the inputs only C sees.
    begin("bigdecimal_grammar_edges");
    expect(".5", 1, 50000000LL); expect("5.", 1, 500000000LL); expect("+1", 1, 100000000LL);
    expect("-0", 1, 0); expect("1E2", 1, 10000000000LL); expect("1.5e-8", 0, 0); expect("15e-8", 1, 15LL);
    expect("1e", 0, 0); expect("e5", 0, 0); expect("1+2", 0, 0); expect(".", 0, 0);
    expect("0e2147483647", 1, 0); expect("0e2147483648", 0, 0);
    end_case();

    begin("long_inputs_have_no_length_limit");
    {
        char t[600];
        memset(t, '0', 300); memcpy(t + 300, "1", 2);                         // 300 leading zeros, then 1
        expect(t, 1, 100000000LL);
        memcpy(t, "1.", 2); memset(t + 2, '0', 400); t[402] = '\0';           // 1. then 400 zeros
        expect(t, 1, 100000000LL);
        memcpy(t, "0.", 2); memset(t + 2, '0', 400); memcpy(t + 402, "1", 2); // 400 decimals
        expect(t, 0, 0);
    }
    end_case();

    begin("ascii_only");
    expect("\xC2\xA0" "0.29", 0, 0);                    // NBSP: refused (Kotlin trims it)
    expect("\xE0\xA5\xA6.\xE0\xA5\xA8\xE0\xA5\xAF", 0, 0); // Devanagari digits: refused
    end_case();

    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
