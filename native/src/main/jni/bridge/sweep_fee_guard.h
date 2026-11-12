#ifndef SWEEP_FEE_GUARD_H
#define SWEEP_FEE_GUARD_H

#include <stdint.h>
#include <stddef.h>
/* The rule and every limit below are the core's, the one copy the core's own sweep
 * (BRLegacySweepBuildAndSign) also applies: this header maps its verdicts and keeps the
 * pre-SWEEP-ABS-CEIL comparison arm. */
#include "../digibytewallet-core/BRLegacySweepFee.h"

/*
 * The fee backstop of buildAndSignLegacySweep, kept free of JNI so sweep_fee_ceiling_kat pins its exact
 * boundaries on the host.
 *
 * The sweep pays one output, `totalIn - fee`, where totalIn is the sum of the amounts its caller listed
 * and fee is the size estimate (10 + 160 per input + 34 bytes) at feePerKb, floored at the 1,000-sat
 * relay minimum. The primary defence of the amounts is Kotlin's (RawTxBinding: each parent is bound to
 * its txid and the amount read from it). This is the native backstop, and it applies the same bounds to
 * EVERY sweep, single-input sweeps included (SWEEP-ABS-CEIL):
 *
 *   rate      feePerKb is within [SWEEP_MIN_FEE_PER_KB, SWEEP_MAX_FEE_PER_KB]: at least DGB's minimum
 *             relay rate (a sweep below it never relays) and at most ten times the default. The sweep
 *             always asks for the default; a rate outside that range is a caller error, and refusing it
 *             also keeps the size x rate product far from wrapping.
 *   ceiling   the fee is never above SWEEP_FEE_CEILING_SATS, whatever the input count.
 *   ratio     the fee is under 5% of the listed total (fee * 20 < totalIn), for one input as for many.
 *             A legacy input's signature does not commit to its amount, so an amount listed below what
 *             the coin holds still signs and the difference goes to the miner as an implicit fee the
 *             estimate never sees. Native cannot read the real amount; what it can see is a listed total
 *             that the estimated fee is a large part of, which is how an under-report shows. A single
 *             input is not exempt: at the default rate it estimates 20,400 sats, so the smallest
 *             single-input sweep that signs lists more than 408,000 sats (0.00408 DGB). A coin under
 *             that is worth less than 20 times its own sweep fee.
 *   dust      what is left after the fee is above 546 sats. (Implied by the ratio today; kept so the
 *             bound does not depend on the ratio's constant.)
 *
 * RESIDUAL, stated: an under-report small enough that the listed total still passes the ratio (for
 * example two coins each listed 10% below what they hold) signs, and the difference is paid as fee. Only
 * the parent binding catches it (RawTxBinding, in Kotlin); this backstop bounds only what it can see.
 *
 * SWEEP_FEE_CEILING_SATS, derived here rather than borrowed: TX_MAX_SIZE (100,000 bytes, the largest
 * transaction the core builds or relays) at DEFAULT_FEE_PER_KB (100,000 sat/kB) is 10,000,000 sats
 * (0.1 DGB). At the default rate the estimate reaches it only at 625 inputs, an estimated size over
 * TX_MAX_SIZE, so the ceiling refuses no sweep the default rate could relay. A single-input sweep
 * estimates 204 bytes: 20,400 sats at the default rate, at most 204,000 at the rate ceiling.
 *
 * ONE COPY: the shipped arm is BRLegacySweepFeeCheck (digibytewallet-core/BRLegacySweepFee.h), and the
 * SWEEP_* limits are its BR_LEGACY_SWEEP_* constants. legacy_sweep_kat holds the core's sweep to signing
 * byte for byte what this bridge signs and refusing what it refuses.
 *
 * MACRO CONVENTION: SWEEP_ABS_CEIL_UNFIXED is tested by VALUE (#if). =1 is sweep_fee_ceiling_kat's
 * comparison arm, the guard before this item: no rate range, no ceiling, single-input sweeps exempt from
 * every bound but dust. Unset or =0 is what ships.
 */

#ifndef SWEEP_ABS_CEIL_UNFIXED
#define SWEEP_ABS_CEIL_UNFIXED 0
#endif

#define SWEEP_MIN_RELAY_SATS     ((uint64_t)BR_LEGACY_SWEEP_MIN_FEE)         /* 1,000 */
#define SWEEP_DUST_SATS          ((uint64_t)BR_LEGACY_SWEEP_DUST)            /* 546 */
#define SWEEP_MIN_FEE_PER_KB     ((uint64_t)BR_LEGACY_SWEEP_MIN_FEE_PER_KB)  /* 100,000: DGB minimum relay rate */
#define SWEEP_MAX_FEE_PER_KB     ((uint64_t)BR_LEGACY_SWEEP_MAX_FEE_PER_KB)  /* 1,000,000: 10 x the minimum */
#define SWEEP_FEE_CEILING_SATS   ((uint64_t)BR_LEGACY_SWEEP_FEE_CEILING)     /* 10,000,000: TX_MAX_SIZE at it */

typedef enum {
    SWEEP_FEE_OK = 0,
    /** feePerKb is below SWEEP_MIN_FEE_PER_KB or above SWEEP_MAX_FEE_PER_KB. */
    SWEEP_FEE_RATE_OUT_OF_RANGE = 1,
    /** The fee is above SWEEP_FEE_CEILING_SATS. */
    SWEEP_FEE_ABOVE_CEILING = 2,
    /** The fee is 5% or more of the listed total: the amounts are almost certainly under-reported. */
    SWEEP_FEE_UNDER_REPORTED = 3,
    /** Nothing above dust is left after the fee. */
    SWEEP_FEE_DUST = 4,
} SweepFeeVerdict;

/* Whether a sweep of inputCount inputs listing totalIn sats may be signed at feePerKb; *feeOut receives
 * the fee it would pay (0 when the rate is refused). */
static inline SweepFeeVerdict sweep_fee_check(uint64_t totalIn, size_t inputCount, int64_t feePerKb,
                                              uint64_t *feeOut)
{
#if SWEEP_ABS_CEIL_UNFIXED
    uint64_t estSize = (uint64_t)BR_LEGACY_SWEEP_BASE_BYTES + (uint64_t)inputCount * BR_LEGACY_SWEEP_INPUT_BYTES
                     + BR_LEGACY_SWEEP_OUTPUT_BYTES;
    uint64_t fee;

    if (feeOut) *feeOut = 0;
    fee = (estSize * (uint64_t)feePerKb) / 1000ULL;
    if (fee < SWEEP_MIN_RELAY_SATS) fee = SWEEP_MIN_RELAY_SATS;
    if (feeOut) *feeOut = fee;
    if (inputCount > 1 && totalIn <= fee * 20) return SWEEP_FEE_UNDER_REPORTED;
    if (totalIn <= fee + SWEEP_DUST_SATS) return SWEEP_FEE_DUST;
    return SWEEP_FEE_OK;
#else
    /* A negative jlong rate reads as above 2^63: a wrap or above the range, refused either way. */
    uint64_t fee = 0;
    const BRLegacySweepFeeVerdict v = BRLegacySweepFeeCheck(totalIn, inputCount, (uint64_t)feePerKb, NULL, &fee);

    if (feeOut) *feeOut = 0;
    switch (v) {
    case BR_LEGACY_SWEEP_FEE_CHECK_WRAPS:
    case BR_LEGACY_SWEEP_FEE_CHECK_RATE_TOO_LOW:
    case BR_LEGACY_SWEEP_FEE_CHECK_RATE_TOO_HIGH: return SWEEP_FEE_RATE_OUT_OF_RANGE;   /* *feeOut stays 0 */
    default: break;
    }
    if (feeOut) *feeOut = fee;
    switch (v) {
    case BR_LEGACY_SWEEP_FEE_CHECK_OK: return SWEEP_FEE_OK;
    case BR_LEGACY_SWEEP_FEE_CHECK_ABOVE_CEILING: return SWEEP_FEE_ABOVE_CEILING;
    case BR_LEGACY_SWEEP_FEE_CHECK_RATIO: return SWEEP_FEE_UNDER_REPORTED;   /* every sweep: no single-input exemption */
    default: return SWEEP_FEE_DUST;                                          /* BR_LEGACY_SWEEP_FEE_CHECK_DUST */
    }
#endif
}

#endif /* SWEEP_FEE_GUARD_H */
