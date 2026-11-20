#ifndef DIGIDOLLAR_TRANSFER_GUARD_H
#define DIGIDOLLAR_TRANSFER_GUARD_H

#include <stdint.h>
#include <stddef.h>

#include "BRTransaction.h"
#include "asset_tx_checks.h"              /* tx_inputs_distinct: the check the wallet asset signer makes */
#include "foreign_tx_fee_guard.h"         /* the size-implied fee: the one copy of that arithmetic */
#include "digidollar_transfer_layout.h"   /* DD_MIN_FEE_SATS */

/*
 * DD-FEE-CEIL: the checks buildAndSignForeignDigiDollarTransfer makes on the transfer it assembled,
 * before it signs it. Kept free of JNI so that dd_transfer_guard_kat runs them against the live core
 * on the host.
 *
 * Invariant. A DigiDollar recovery transfer is signed only when
 *
 *   its rate     feePerKb, the rate the caller says it sized the transfer at, is above zero and not
 *                above DD_TRANSFER_MAX_FEE_PER_KB. The required fee below is computed at this rate, so
 *                the rate is held to a bound of this file's own, not to the caller's word;
 *   its inputs   each name a different outpoint (tx_inputs_distinct, the check the wallet asset
 *                signer makes in jni_asset_send.c);
 *   its fee      -- the amounts its inputs are listed at, minus its outputs -- is
 *                  not below the required fee: the larger of the 0.1 DGB DigiDollar floor
 *                    (DD_MIN_FEE_SATS) and what the transfer's size needs at feePerKb
 *                    (foreign_tx_fee_check's estimate, the one the asset recovery signer uses);
 *                  not above the fee the caller approved (approvedMaxFee: the fee of the plan being
 *                    signed, which is the figure recorded for the transfer);
 *                  not above FOREIGN_TX_MAX_FEE_FACTOR times the required fee, whatever the caller
 *                    approved.
 *
 * What the band does and does not rest on: the 0.1 DGB floor, the factor and the rate bound are
 * constants here; the input and output counts are read from the assembled transaction; the rate
 * within its bound is the caller's. So the most a transfer can pay is FOREIGN_TX_MAX_FEE_FACTOR times
 * the larger of 0.1 DGB and its estimated size at DD_TRANSFER_MAX_FEE_PER_KB, whatever the caller
 * states.
 *
 * DD_TRANSFER_MAX_FEE_PER_KB, derived here: the wallet sizes a recovery transfer at 100,000 sat/kB
 * (DigiByte's minimum relay rate; DigiDollarTransferService.DEFAULT_FEE_PER_KB), and DigiByte has no
 * fee market, so no caller has a reason to name a higher one. Ten times that leaves room for a caller
 * that does and keeps size x rate far from wrapping. At the bound the estimate passes the 0.1 DGB
 * floor only above 61 inputs (10 + 160n + 68 bytes at 1,000 sat/byte), so for an ordinary transfer
 * the floor is the required fee at every rate that is accepted.
 *
 * Checked on the assembled transaction, not on the arrays it was assembled from: the sums are of
 * the inputs and outputs that are signed.
 *
 * MACRO CONVENTION: DD_TRANSFER_GUARD_UNFIXED is tested by VALUE (#if). =1 is dd_transfer_guard_kat's
 * comparison arm, the checks before this header: outputs within the inputs and the 0.1 DGB floor,
 * nothing else. Unset or =0 is what ships.
 */

/** The highest rate a transfer may be sized at, satoshis per 1000 bytes: ten times the 100,000 the
 *  wallet sizes at. */
#define DD_TRANSFER_MAX_FEE_PER_KB 1000000ULL

#ifndef DD_TRANSFER_GUARD_UNFIXED
#define DD_TRANSFER_GUARD_UNFIXED 0
#endif

typedef enum {
    DD_TRANSFER_OK = 0,
    /** No transaction, no input or no output, or amounts that do not fit 64 bits. */
    DD_TRANSFER_MALFORMED = 1,
    /** An outpoint is listed more than once among the inputs. */
    DD_TRANSFER_DUPLICATE_INPUT = 2,
    /** The outputs claim more than the inputs hold. */
    DD_TRANSFER_OVERSPEND = 3,
    /** The fee is below the required fee (the DigiDollar floor, or what the size needs). */
    DD_TRANSFER_FEE_TOO_LOW = 4,
    /** The fee is above the fee the caller approved. */
    DD_TRANSFER_FEE_ABOVE_APPROVED = 5,
    /** The fee is over FOREIGN_TX_MAX_FEE_FACTOR times the required fee. */
    DD_TRANSFER_FEE_TOO_HIGH = 6,
    /** feePerKb is zero or above DD_TRANSFER_MAX_FEE_PER_KB. */
    DD_TRANSFER_RATE_OUT_OF_RANGE = 7,
} DdTransferVerdict;

/** The fee a transfer of this shape must pay at least: the DigiDollar floor, or the size-implied
 *  fee at feePerKb when that is larger. */
static inline uint64_t dd_transfer_required_fee(size_t inputCount, size_t outputCount, uint64_t feePerKb)
{
    uint64_t expected = 0;
    (void)foreign_tx_fee_check(0, 0, inputCount, outputCount, feePerKb, NULL, &expected);
    return expected > DD_MIN_FEE_SATS ? expected : DD_MIN_FEE_SATS;
}

/**
 * Whether the assembled transfer may be signed.
 *
 * @param feePerKb        the rate the caller sized the transfer at, satoshis per 1000 bytes; within
 *                        (0, DD_TRANSFER_MAX_FEE_PER_KB] or the transfer is refused.
 * @param approvedMaxFee  the most this transfer may pay: the fee of the plan being signed.
 * @param feeOut          optional; receives the inputs minus the outputs (0 when that has no value).
 */
static inline DdTransferVerdict dd_transfer_check(const BRTransaction *tx, uint64_t feePerKb,
                                                  uint64_t approvedMaxFee, uint64_t *feeOut)
{
    uint64_t totalIn = 0, totalOut = 0, fee;

    if (feeOut) *feeOut = 0;
    if (!tx || tx->inCount == 0 || tx->outCount == 0) return DD_TRANSFER_MALFORMED;

#if ! DD_TRANSFER_GUARD_UNFIXED
    /* A negative jlong rate reads here as above 2^63: above the bound, refused. */
    if (feePerKb == 0 || feePerKb > DD_TRANSFER_MAX_FEE_PER_KB) return DD_TRANSFER_RATE_OUT_OF_RANGE;
    if (!tx_inputs_distinct(tx)) return DD_TRANSFER_DUPLICATE_INPUT;
#endif

    for (size_t i = 0; i < tx->inCount; i++) {
        if (tx->inputs[i].amount > UINT64_MAX - totalIn) return DD_TRANSFER_MALFORMED;
        totalIn += tx->inputs[i].amount;
    }
    for (size_t i = 0; i < tx->outCount; i++) {
        if (tx->outputs[i].amount > UINT64_MAX - totalOut) return DD_TRANSFER_MALFORMED;
        totalOut += tx->outputs[i].amount;
    }
    if (totalOut > totalIn) return DD_TRANSFER_OVERSPEND;
    fee = totalIn - totalOut;
    if (feeOut) *feeOut = fee;

#if DD_TRANSFER_GUARD_UNFIXED
    (void)feePerKb; (void)approvedMaxFee;
    return fee < DD_MIN_FEE_SATS ? DD_TRANSFER_FEE_TOO_LOW : DD_TRANSFER_OK;
#else
    {
        const uint64_t required = dd_transfer_required_fee(tx->inCount, tx->outCount, feePerKb);
        /* Saturating, so a required fee near the top of the range cannot wrap the ceiling under it. */
        const uint64_t ceiling = (required > UINT64_MAX / FOREIGN_TX_MAX_FEE_FACTOR)
            ? UINT64_MAX
            : required * FOREIGN_TX_MAX_FEE_FACTOR;

        if (fee < required) return DD_TRANSFER_FEE_TOO_LOW;
        if (fee > approvedMaxFee) return DD_TRANSFER_FEE_ABOVE_APPROVED;
        if (fee > ceiling) return DD_TRANSFER_FEE_TOO_HIGH;
        return DD_TRANSFER_OK;
    }
#endif
}

#endif /* DIGIDOLLAR_TRANSFER_GUARD_H */
