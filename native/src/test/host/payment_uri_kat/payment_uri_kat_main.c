// Host KAT for BRPaymentUri.h -- the digibyte: payment URI, untrusted input from QR codes and
// deep links. Carries Android's DigiByteUriTest.kt case for case, then the hostile inputs.
//
// Built twice by run.sh:
//   -DPAYMENT_URI_UNFIXED  the last duplicate key wins, and malformed asset requests degrade
//                          to plain payments (fail open); every RED case fails
//   (default)              the shipped rule; every case passes

#include "BRPaymentUri.h"
#include <stdio.h>
#include <stdlib.h>

static int g_failed = 0;
static BRPaymentUri U;
static char B[BR_PAYMENT_URI_BUF_SIZE];   // the text fields live here

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static int parse(const char *s) { return BRPaymentUriParse(s, strlen(s), &U, B, sizeof(B)); }
static int rejected(const char *s) { return parse(s) == 0; }
static int eqs(const char *a, const char *b) { return strcmp(a, b) == 0; }

int main(void)
{
    char out[512], why[300];
    int ok;

    // ---- DigiByteUriTest.kt -------------------------------------------------------------------
    ok = parse("DTest123") && eqs(U.address, "DTest123") && ! U.hasAmount && ! U.hasAsset;
    result("bare_address", ok, U.address);

    ok = parse("digibyte:DTest123?amount=1.5") && eqs(U.address, "DTest123") && U.hasAmount && U.amount == 150000000;
    result("amount_dgb_to_sats", ok, NULL);

    ok = parse("digibyte:DTest123?amount=5.00001234") && U.amount == 500001234 &&
         parse("digibyte:DTest123?amount=0.29") && U.amount == 29000000 &&
         parse("digibyte:DTest123?amount=1.00000005") && U.amount == 100000005;
    result("amount_eight_decimals_exact", ok, NULL);

    {
        static const int64_t sats[] = { 1, 29000000, 500001234, 1000000000000000LL };
        ok = BRPaymentUriEncode(out, sizeof(out), "DTest123", 1, 500001234, NULL) && eqs(out, "digibyte:DTest123?amount=5.00001234");
        ok = ok && BRPaymentUriEncode(out, sizeof(out), "DTest123", 1, 1000000000000000LL, NULL) && eqs(out, "digibyte:DTest123?amount=10000000");
        ok = ok && BRPaymentUriEncode(out, sizeof(out), "DTest123", 1, 29000000, NULL) && eqs(out, "digibyte:DTest123?amount=0.29");
        for (size_t i = 0; ok && i < 4; i++) {
            ok = BRPaymentUriEncode(out, sizeof(out), "DTest123", 1, sats[i], NULL) && parse(out) && U.hasAmount && U.amount == sats[i];
        }
        result("encode_exact_and_round_trip", ok, out);
    }

    ok = BRPaymentUriEncode(out, sizeof(out), "DTest123", 1, 1, NULL) && eqs(out, "digibyte:DTest123?amount=0.00000001") &&
         BRPaymentUriEncode(out, sizeof(out), "DTest123", 1, 10, NULL) && eqs(out, "digibyte:DTest123?amount=0.0000001") &&
         parse("digibyte:DTest123?amount=0.00000001") && U.amount == 1;
    result("one_satoshi_plain_decimal", ok, out);

    ok = parse("digibyte:DTest123?amount=1.123456789") && eqs(U.address, "DTest123") && ! U.hasAmount;
    result("unrepresentable_amount_dropped", ok, NULL);

    ok = parse("digibyte:DTest123?label=Coffee%20Shop&message=Order%20%231") &&
         U.hasLabel && eqs(U.label, "Coffee Shop") && U.hasMessage && eqs(U.message, "Order #1");
    result("label_message_percent_decoded", ok, U.label);

    result("foreign_scheme_rejected", rejected("digiid://example.com/callback") && rejected("https://example.com"), NULL);
    result("blank_rejected", rejected("   ") && rejected("digibyte:") && rejected(""), NULL);

    ok = parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount=5") && eqs(U.address, "DTest123") &&
         U.hasAsset && eqs(U.assetId, "La3Qxyz") && U.assetAmount == 5 && ! U.hasAmount;
    result("asset_request_parsed", ok, NULL);

    ok = rejected("digibyte:DTest123?assetId=La3Qxyz") && rejected("digibyte:DTest123?assetId=La3Qxyz&assetAmount=") &&
         rejected("digibyte:DTest123?assetId=La3Qxyz&assetAmount=abc") && rejected("digibyte:DTest123?assetId=La3Qxyz&assetAmount=1.5");
    result("asset_without_valid_quantity_rejected", ok, NULL);

    ok = rejected("digibyte:DTest123?assetId=La3Qxyz&assetAmount=0") && rejected("digibyte:DTest123?assetId=La3Qxyz&assetAmount=-5");
    result("non_positive_quantity_rejected", ok, NULL);

    result("quantity_without_asset_rejected", rejected("digibyte:DTest123?assetAmount=5"), NULL);

    ok = BRPaymentUriEncodeAsset(out, sizeof(out), "DTest123", "La3Qxyz", 42, "Sale #7") &&
         eqs(out, "digibyte:DTest123?assetId=La3Qxyz&assetAmount=42&label=Sale%20%237") &&
         parse(out) && eqs(U.address, "DTest123") && U.hasAsset && eqs(U.assetId, "La3Qxyz") && U.assetAmount == 42 && eqs(U.label, "Sale #7");
    result("asset_request_round_trip", ok, out);

    ok = parse("digibyte:DTest123?amount=2") && ! U.hasAsset && U.assetId[0] == '\0' && U.assetAmount == 0;
    result("plain_request_has_no_asset_fields", ok, NULL);

    // ---- hostile and edge inputs ----------------------------------------------------------------
    ok = parse("digibyte:DReal?amount=1&amount=1000&label=Shop&label=Evil") && U.amount == 100000000 && eqs(U.label, "Shop");
    snprintf(why, sizeof(why), "amount %lld, label %s", (long long)U.amount, U.label);
    result("duplicate_key_first_wins", ok, why);

    ok = parse("digibyte:DReal?amou%6Et=3&amount=9") && U.amount == 300000000;
    result("encoded_key_is_the_same_key", ok, NULL);

    ok = rejected("digibyte:DReal?assetId=X&assetAmount=5&assetAmount=abc") == 0 && U.hasAsset && U.assetAmount == 5 &&
         rejected("digibyte:DReal?assetAmount=abc&assetAmount=5");
    result("duplicate_asset_quantity_first_wins", ok, NULL);

    ok = parse("digibyte:DReal?assetId=%20%20&amount=1") && ! U.hasAsset && U.hasAmount &&
         rejected("digibyte:DReal?assetId=%20&assetAmount=5");
    result("blank_asset_id_is_no_asset", ok, NULL);

    ok = parse("digibyte:DReal?assetId=X&assetAmount=%2B7") && U.assetAmount == 7 &&
         rejected("digibyte:DReal?assetId=X&assetAmount=9223372036854775808") &&
         parse("digibyte:DReal?assetId=X&assetAmount=9223372036854775807") && U.assetAmount == INT64_MAX &&
         rejected("digibyte:DReal?assetId=X&assetAmount=-9223372036854775808") &&
         rejected("digibyte:DReal?assetId=X&assetAmount=%D9%A3");   // Arabic-Indic three
    result("asset_quantity_integer_rules", ok, NULL);

    // Kotlin decoded "%-1" to byte 0xFF and "%+F" to 0x0F; here an escape is two hex digits,
    // so both stay literal (and the '+' after a literal '%' is a space, as everywhere).
    ok = parse("digibyte:DReal?label=%-1%+F%4") && eqs(U.label, "%-1% F%4") &&
         parse("digibyte:DReal?label=a%zzb%") && eqs(U.label, "a%zzb%");
    result("percent_needs_two_hex_digits", ok, U.label);

    ok = parse("digibyte:DReal?label=%F0%9F%8D%95+%2B") && U.labelLen == 6 && memcmp(U.label, "\xF0\x9F\x8D\x95 +", 6) == 0;
    result("utf8_outside_bmp_survives", ok, NULL);

    ok = parse("digibyte:DReal?message=a%00b") && U.messageLen == 3 && U.message[1] == '\0' && U.message[2] == 'b' &&
         parse("digibyte:DReal?amount=1%00") && ! U.hasAmount;
    result("decoded_nul_is_kept_by_length", ok, NULL);

    {
        static char big[BR_PAYMENT_URI_MAX + 2];
        memset(big, 'D', sizeof(big) - 1); big[sizeof(big) - 1] = '\0';
        ok = rejected(big);                                   // MAX + 1 bytes
        big[BR_PAYMENT_URI_MAX] = '\0';
        ok = ok && parse(big) && strlen(U.address) == BR_PAYMENT_URI_MAX;
        ok = ok && BRPaymentUriParse("DRe\0al", 6, &U, B, sizeof(B)) == 0;
        ok = ok && BRPaymentUriParse("DReal", 5, &U, B, sizeof(B) - 1) == 0;   // buffer too small
        result("bounded_and_nul_free_input", ok, NULL);
    }

    ok = parse("  digibyte:  DReal  ?amount=1  ") && eqs(U.address, "DReal") &&
         parse("digibyte:DReal?amount=%201%20") && U.hasAmount && U.amount == 100000000 &&
         parse("digibyte:DReal?&&=x&amount=2&") && U.amount == 200000000 &&
         parse("digibyte:DReal?unknown=1&label") && U.hasLabel && U.labelLen == 0;
    result("query_shapes", ok, U.address);

    ok = parse("DIGIBYTE:DReal?amount=1") && eqs(U.address, "DIGIBYTE:DReal?amount=1") && ! U.hasAmount &&
         parse("digibyte://DReal") && eqs(U.address, "//DReal");
    result("scheme_case_and_slashes_as_before", ok, U.address);

    ok = BRPaymentUriEncode(out, 10, "DTest123", 0, 0, NULL) == 0 &&
         BRPaymentUriEncodeAsset(out, sizeof(out), "D", "X", 0, NULL) == 0 &&
         BRPaymentUriEncode(out, sizeof(out), "D", 0, 0, "a b/\xC3\xA9") && eqs(out, "digibyte:D?label=a%20b%2F%C3%A9") &&
         BRPaymentUriEncode(out, sizeof(out), "D", 1, -1, NULL) == 0;
    result("encode_bounds", ok, out);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
