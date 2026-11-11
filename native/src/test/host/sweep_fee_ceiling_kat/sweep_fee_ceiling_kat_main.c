/* sweep_fee_ceiling_kat -- SWEEP-ABS-CEIL: the legacy sweep's native backstop bounds the fee for
 * single-input sweeps too -- an absolute ceiling, not an exemption.
 *
 * Compiles bridge/sweep_fee_guard.h (the JNI-free backstop buildAndSignLegacySweep calls) in two arms:
 *   default                       the shipped guard.
 *   -DSWEEP_ABS_CEIL_UNFIXED=1    the guard before this item: no rate range, no ceiling, and a single-input
 *                                 sweep exempt from every bound but dust.
 *
 * Each case prints PASS/FAIL lines tagged with its letter.
 *   [A] RED-THEN-GREEN. A single-input 100 DGB sweep at a rate that makes the fee 0.816 DGB: signed before,
 *       refused now (the rate is out of range).
 *   [B] RED-THEN-GREEN. A single-input sweep at the rate ceiling pays at most 204,000 sats, never more;
 *       one sat/kB above the ceiling is refused.
 *   [C] RED-THEN-GREEN. A 100-input sweep of 10,000 DGB at the rate ceiling estimates 16,044,000 sats: over
 *       the absolute ceiling, refused (the 5% ratio alone signs it).
 *   [D] GUARD, both arms. A single-input 1 DGB sweep at the default rate signs and pays 20,400 sats.
 *   [E] GUARD, both arms. A multi-input sweep whose fee is 5% or more of its listed total is refused.
 *   [F] GUARD, both arms. A sweep that would leave only dust is refused.
 *   [G] GUARD, both arms. At the default rate, 624 inputs (the most whose estimate fits TX_MAX_SIZE) sign
 *       under the ceiling: the ceiling refuses no sweep the default rate could relay.
 */
#include <stdio.h>
#include <stdint.h>
#include "sweep_fee_guard.h"

static int g_fail = 0;
static void check(int c, const char *what)
{
    printf("%s: %s\n", c ? "PASS" : "FAIL", what);
    if (!c) g_fail++;
}

#define DGB 100000000ULL
#define DEFAULT_RATE 100000LL

int main(void)
{
    uint64_t fee = 0;
    SweepFeeVerdict v;
    printf("ARM: %s\n", SWEEP_ABS_CEIL_UNFIXED ? "comparison (SWEEP_ABS_CEIL_UNFIXED=1)" : "shipped");

    printf("[A] single input, absurd rate\n");
    v = sweep_fee_check(100 * DGB, 1, 400000000LL, &fee);
    printf("NOTE: A: fee %llu sats, verdict %d\n", (unsigned long long)fee, (int)v);
    if (v == SWEEP_FEE_OK) { printf("FAIL: A: a single-input sweep paying %llu sats of fee is signed\n", (unsigned long long)fee); g_fail++; }
    else check(v == SWEEP_FEE_RATE_OUT_OF_RANGE, "A: a single-input sweep at an out-of-range rate is refused");

    printf("[B] single input, at and above the rate ceiling\n");
    v = sweep_fee_check(100 * DGB, 1, (int64_t)SWEEP_MAX_FEE_PER_KB, &fee);
    check(v == SWEEP_FEE_OK && fee == 204000, "B: at the rate ceiling a single-input sweep pays 204,000 sats");
    v = sweep_fee_check(100 * DGB, 1, (int64_t)SWEEP_MAX_FEE_PER_KB + 1, &fee);
    if (v == SWEEP_FEE_OK) { printf("FAIL: B: a single-input sweep above the rate ceiling is signed\n"); g_fail++; }
    else check(1, "B: one sat/kB above the rate ceiling is refused");

    printf("[C] many inputs, over the absolute ceiling\n");
    v = sweep_fee_check(10000 * DGB, 100, (int64_t)SWEEP_MAX_FEE_PER_KB, &fee);
    printf("NOTE: C: fee %llu sats, verdict %d\n", (unsigned long long)fee, (int)v);
    if (v == SWEEP_FEE_OK) { printf("FAIL: C: a sweep paying more than the absolute ceiling is signed\n"); g_fail++; }
    else check(v == SWEEP_FEE_ABOVE_CEILING, "C: a fee over the absolute ceiling is refused");

    printf("[D] guard: single input at the default rate\n");
    v = sweep_fee_check(1 * DGB, 1, DEFAULT_RATE, &fee);
    check(v == SWEEP_FEE_OK && fee == 20400, "D guard: a 1 DGB single-input sweep signs and pays 20,400 sats");

    printf("[E] guard: multi-input under-report\n");
    v = sweep_fee_check(500000, 2, DEFAULT_RATE, &fee);   /* fee 36,400; 5% of the total is 25,000 */
    check(v == SWEEP_FEE_UNDER_REPORTED, "E guard: a two-input sweep whose fee is over 5% of its total is refused");

    printf("[F] guard: dust\n");
    v = sweep_fee_check(20400 + 546, 1, DEFAULT_RATE, &fee);
    check(v == SWEEP_FEE_DUST, "F guard: a sweep leaving only dust is refused");
    v = sweep_fee_check(20400 + 547, 1, DEFAULT_RATE, &fee);
    check(v == SWEEP_FEE_OK, "F guard: one sat above dust signs");

    printf("[G] guard: the ceiling refuses no sweep the default rate could relay\n");
    v = sweep_fee_check(1000 * DGB, 624, DEFAULT_RATE, &fee);
    check(v == SWEEP_FEE_OK && fee <= SWEEP_FEE_CEILING_SATS && 10 + 624 * 160 + 34 <= 100000,
          "G guard: 624 inputs at the default rate sign, under the ceiling, and fit TX_MAX_SIZE");

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: sweep_fee_ceiling_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
