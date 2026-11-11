#ifndef SWEEP_FEE_GUARD_H
#define SWEEP_FEE_GUARD_H

#include <stdint.h>
#include <stddef.h>

/*
 * The fee backstop of buildAndSignLegacySweep, kept free of JNI so sweep_fee_ceiling_kat pins its exact
 * boundaries on the host.
 *
 * The sweep pays one output, `totalIn - fee`, where totalIn is the sum of the amounts its caller listed
 * and fee is the size estimate (10 + 160 per input + 34 bytes) at feePerKb, floored at the 1,000-sat
 * relay minimum. The primary defence of the amounts is Kotlin's (RawTxBinding: each parent is bound to
 * its txid and the amount read from it). This is the native backstop, and it bounds the fee of EVERY
 * sweep (SWEEP-ABS-CEIL):
 *
 *   rate      feePerKb is never negative and never above SWEEP_MAX_FEE_PER_KB (ten times the default
 *             rate). The sweep always asks for the default; a rate outside that range is a caller
 *             error, and refusing it also keeps the size x rate product far from wrapping.
 *   ceiling   the fee is never above SWEEP_FEE_CEILING_SATS, whatever the input count: an absolute
 *             ceiling, which a single-input sweep is not exempt from.
 *   ratio     on a multi-input sweep the fee is under 5% of the listed total (fee * 20 < totalIn): a
 *             legitimate consolidation dwarfs its fee, so a fee that is not means the amounts are
 *             almost certainly stale or under-reported. (A lone small coin can legitimately pay a fee
 *             that is a real fraction of it, so the ratio stays multi-input; the ceiling does not.)
 *   dust      what is left after the fee is above 546 sats.
 *
 * SWEEP_FEE_CEILING_SATS, derived here rather than borrowed: TX_MAX_SIZE (100,000 bytes, the largest
 * transaction the core builds or relays) at DEFAULT_FEE_PER_KB (100,000 sat/kB) is 10,000,000 sats
 * (0.1 DGB). At the default rate the estimate reaches it only at 625 inputs, an estimated size over
 * TX_MAX_SIZE, so the ceiling refuses no sweep the default rate could relay. A single-input sweep
 * estimates 204 bytes: 20,400 sats at the default rate, at most 204,000 at the rate ceiling.
 *
 * MACRO CONVENTION: SWEEP_ABS_CEIL_UNFIXED is tested by VALUE (#if). =1 is sweep_fee_ceiling_kat's
 * comparison arm, the guard before this item: no rate range, no ceiling, single-input sweeps exempt from
 * every bound but dust. Unset or =0 is what ships.
 */

#ifndef SWEEP_ABS_CEIL_UNFIXED
#define SWEEP_ABS_CEIL_UNFIXED 0
#endif

#define SWEEP_MIN_RELAY_SATS     1000ULL
#define SWEEP_DUST_SATS          546ULL
#define SWEEP_MAX_FEE_PER_KB     1000000ULL      /* 10 x DEFAULT_FEE_PER_KB */
#define SWEEP_FEE_CEILING_SATS   10000000ULL     /* TX_MAX_SIZE at DEFAULT_FEE_PER_KB */

typedef enum {
    SWEEP_FEE_OK = 0,
    /** feePerKb is negative or above SWEEP_MAX_FEE_PER_KB. */
    SWEEP_FEE_RATE_OUT_OF_RANGE = 1,
    /** The fee is above SWEEP_FEE_CEILING_SATS. */
    SWEEP_FEE_ABOVE_CEILING = 2,
    /** A multi-input sweep whose fee is 5% or more of the listed total. */
    SWEEP_FEE_UNDER_REPORTED = 3,
    /** Nothing above dust is left after the fee. */
    SWEEP_FEE_DUST = 4,
} SweepFeeVerdict;

/* Whether a sweep of inputCount inputs listing totalIn sats may be signed at feePerKb; *feeOut receives
 * the fee it would pay (0 when the rate is refused). */
static inline SweepFeeVerdict sweep_fee_check(uint64_t totalIn, size_t inputCount, int64_t feePerKb,
                                              uint64_t *feeOut)
{
    uint64_t estSize = 10ULL + (uint64_t)inputCount * 160ULL + 34ULL;
    uint64_t fee;

    if (feeOut) *feeOut = 0;
#if SWEEP_ABS_CEIL_UNFIXED
    fee = (estSize * (uint64_t)feePerKb) / 1000ULL;
    if (fee < SWEEP_MIN_RELAY_SATS) fee = SWEEP_MIN_RELAY_SATS;
    if (feeOut) *feeOut = fee;
    if (inputCount > 1 && totalIn <= fee * 20) return SWEEP_FEE_UNDER_REPORTED;
#else
    if (feePerKb < 0 || (uint64_t)feePerKb > SWEEP_MAX_FEE_PER_KB) return SWEEP_FEE_RATE_OUT_OF_RANGE;
    /* No wrap: inputCount is a JNI array length (< 2^31), so estSize < 2^39, and the rate is < 2^20. */
    fee = (estSize * (uint64_t)feePerKb) / 1000ULL;
    if (fee < SWEEP_MIN_RELAY_SATS) fee = SWEEP_MIN_RELAY_SATS;
    if (feeOut) *feeOut = fee;
    if (fee > SWEEP_FEE_CEILING_SATS) return SWEEP_FEE_ABOVE_CEILING;
    if (inputCount > 1 && totalIn <= fee * 20) return SWEEP_FEE_UNDER_REPORTED;
#endif
    if (totalIn <= fee + SWEEP_DUST_SATS) return SWEEP_FEE_DUST;
    return SWEEP_FEE_OK;
}

#endif /* SWEEP_FEE_GUARD_H */
