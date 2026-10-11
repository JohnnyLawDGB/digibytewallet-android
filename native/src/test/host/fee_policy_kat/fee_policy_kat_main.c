// Host KAT for BRFeePolicy.h -- the fee rate a send is built at, and the custom-fee warning.
// Values computed from Android's SendViewModel (TYPICAL_TX_VSIZE 141, DEFAULT_FEE_PER_KB 100,000).
//
// Built twice by run.sh:
//   -DFEE_POLICY_RED  the custom fee taken as a per-kB rate with no size, and no relay check;
//                     every RED case fails
//   (default)         the shipped rule; every case passes

#include "BRFeePolicy.h"
#include <stdio.h>

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

int main(void)
{
    char why[200];

    result("default_rate", BRFeeRateForSend(0, 0) == 100000 && BRFeeRateForSend(0, 99999999) == 100000, NULL);
    result("default_estimate", BR_FEE_DEFAULT_ESTIMATE_SATS == 14100, NULL);

    // Kotlin: (feeSat * 1000) / 141 in Long arithmetic, rounded down.
    snprintf(why, sizeof(why), "14100 -> %llu, 100000 -> %llu, 1 -> %llu, 141 -> %llu",
             (unsigned long long)BRFeeRateForSend(1, 14100), (unsigned long long)BRFeeRateForSend(1, 100000),
             (unsigned long long)BRFeeRateForSend(1, 1), (unsigned long long)BRFeeRateForSend(1, 141));
    result("custom_rate_over_typical_size",
           BRFeeRateForSend(1, 14100) == 100000 && BRFeeRateForSend(1, 100000) == 709219 &&
           BRFeeRateForSend(1, 1) == 7 && BRFeeRateForSend(1, 141) == 1000 && BRFeeRateForSend(1, 1000000) == 7092198, why);

    result("non_positive_custom_fee_builds_at_default",
           BRFeeRateForSend(1, 0) == 100000 && BRFeeRateForSend(1, -5) == 100000, NULL);

    // Exact floor without overflow, and saturation where Kotlin's Long wrapped.
    result("large_fees_exact_then_saturated",
           BRFeeRateForSend(1, 1000000000000000LL) == 7092198581560283ULL &&   // 10M DGB: exact
           BRFeeRateForSend(1, INT64_MAX) == (uint64_t)INT64_MAX, NULL);

    result("warnings",
           BRFeeWarningFor(0, 0) == BRFeeWarningNone && BRFeeWarningFor(0, 5) == BRFeeWarningNone &&
           BRFeeWarningFor(1, 0) == BRFeeWarningRequired && BRFeeWarningFor(1, -1) == BRFeeWarningRequired, NULL);

    result("below_relay_warned",
           BRFeeWarningFor(1, 14099) == BRFeeWarningBelowRelay && BRFeeWarningFor(1, 1) == BRFeeWarningBelowRelay &&
           BRFeeWarningFor(1, 14100) == BRFeeWarningNone && BRFeeWarningFor(1, 100000000) == BRFeeWarningNone, NULL);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
