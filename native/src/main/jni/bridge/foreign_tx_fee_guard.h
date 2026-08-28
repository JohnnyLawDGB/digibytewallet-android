#ifndef FOREIGN_TX_FEE_GUARD_H
#define FOREIGN_TX_FEE_GUARD_H

#include <stdint.h>
#include <stddef.h>

/**
 * The two-sided implied-fee guard for buildAndSignForeignAssetTransfer.
 *
 * ## Why a foreign asset transfer needs one and a sweep does not
 *
 * buildAndSignLegacySweep computes `out = totalIn - fee`, so it cannot leak value however
 * wrong its caller is. buildAndSignForeignAssetTransfer cannot do that: the caller states
 * the outputs, because only the caller knows the DigiAsset marker layout. Whatever is left
 * over becomes the miner's fee — including anything the caller forgot to write back.
 *
 * A transfer spending a 6,000-sat asset marker plus a 200,000-sat fee UTXO, writing back
 * only the marker, pays ~200,000 sats on a transaction that should cost ~55,000. Nothing on
 * the network rejects it; it is a perfectly valid transaction. The user simply loses the
 * difference, silently and permanently.
 *
 * So the implied fee is bounded on both sides:
 *
 *   - **too low** — under what the transaction's size needs. It would never relay, and the
 *     asset stays put. Recoverable, but a wasted attempt.
 *   - **too high** — far over what the size justifies, which means the caller lost track of
 *     value; in practice, a missing change output. Refused rather than signed.
 *
 * ## No JNI here on purpose
 *
 * This is the arithmetic standing between a correct transfer and an irreversible burn, and
 * its interesting cases are exact boundaries. Kept free of JNI so `foreign_tx_fee_guard_kat`
 * can pin those boundaries on the host, where they are cheap — rather than only on a device
 * holding a real asset and a real fee UTXO.
 */

/** Ceiling multiple of the size-implied fee. Above this we refuse to sign. */
#define FOREIGN_TX_MAX_FEE_FACTOR 3

/** Absolute floor for the expected fee, in satoshis (DGB min relay). */
#define FOREIGN_TX_MIN_RELAY_SATS 1000ULL

typedef enum {
    FOREIGN_TX_FEE_OK = 0,
    /** Below what this size needs — would not relay. */
    FOREIGN_TX_FEE_TOO_LOW = 1,
    /** Over FOREIGN_TX_MAX_FEE_FACTOR x the expectation — value is being lost. */
    FOREIGN_TX_FEE_TOO_HIGH = 2,
    /** Outputs claim more than the inputs hold. Not a fee question. */
    FOREIGN_TX_FEE_OVERSPEND = 3,
} ForeignTxFeeVerdict;

/**
 * Decide whether the fee implied by (totalIn - totalOut) may be signed.
 *
 * @param impliedFeeOut   optional; receives totalIn - totalOut (0 on overspend).
 * @param expectedFeeOut  optional; receives the size-implied fee, floored at min relay.
 */
static inline ForeignTxFeeVerdict foreign_tx_fee_check(
    uint64_t totalIn,
    uint64_t totalOut,
    size_t inputCount,
    size_t outputCount,
    uint64_t feePerKb,
    uint64_t *impliedFeeOut,
    uint64_t *expectedFeeOut)
{
    /* Checked BEFORE the subtraction: on unsigned types totalIn - totalOut would wrap to an
     * enormous value that happens to read as TOO_HIGH. Right answer, wrong reason — and the
     * wrong reason stops being right the moment the ceiling changes. */
    if (totalOut > totalIn) {
        if (impliedFeeOut) *impliedFeeOut = 0;
        if (expectedFeeOut) *expectedFeeOut = 0;
        return FOREIGN_TX_FEE_OVERSPEND;
    }

    const uint64_t impliedFee = totalIn - totalOut;

    /* Conservative size estimate: 160 bytes per input covers a legacy P2PKH spend (~148)
     * with slack, 34 per output, 10 for the envelope. Over-estimating the size raises the
     * floor, which is the safe direction — it can refuse a thin fee, never accept one. */
    const uint64_t estSize = 10ULL
        + (uint64_t)inputCount * 160ULL
        + (uint64_t)outputCount * 34ULL;

    uint64_t expectedFee;
    if (feePerKb != 0 && estSize > UINT64_MAX / feePerKb) {
        /* An absurd rate would wrap the multiplication and, worse, wrap the ceiling below
         * the floor — switching the upper bound off exactly when it matters. Saturate. */
        expectedFee = UINT64_MAX;
    } else {
        expectedFee = (estSize * feePerKb) / 1000ULL;
    }
    if (expectedFee < FOREIGN_TX_MIN_RELAY_SATS) expectedFee = FOREIGN_TX_MIN_RELAY_SATS;

    if (impliedFeeOut) *impliedFeeOut = impliedFee;
    if (expectedFeeOut) *expectedFeeOut = expectedFee;

    if (impliedFee < expectedFee) return FOREIGN_TX_FEE_TOO_LOW;

    /* Saturating ceiling, for the same reason as above. */
    const uint64_t ceiling = (expectedFee > UINT64_MAX / FOREIGN_TX_MAX_FEE_FACTOR)
        ? UINT64_MAX
        : expectedFee * FOREIGN_TX_MAX_FEE_FACTOR;

    if (impliedFee > ceiling) return FOREIGN_TX_FEE_TOO_HIGH;

    return FOREIGN_TX_FEE_OK;
}

#endif /* FOREIGN_TX_FEE_GUARD_H */
