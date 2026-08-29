package io.digibyte.core.recovery

/**
 * Plans the transaction that moves a foreign wallet's DigiDollar into this one.
 *
 * ## What makes this different from the other transfers here
 *
 * A DigiDollar token output carries ZERO satoshis — the cents live in the OP_RETURN — so the DGB
 * paying for the move is entirely separate from the thing being moved. And the DigiDollar fee is
 * a **consensus floor, not an estimate**: 0.1 DGB, matching DigiByte Core's own builder. Below it
 * the transfer is not accepted however well-formed, which is why a wallet that cannot meet it is
 * refused here rather than rejected by the network after signing.
 *
 * That floor is roughly twice what a DigiAsset transfer costs, so a wallet can comfortably move
 * its assets and still be unable to move its dollars. Saying so with the number is the whole point
 * of refusing early.
 *
 * ## The whole balance, always
 *
 * Recovery never leaves dollars behind, so there is no DD change output and no second cent push in
 * the marker. A partial transfer would need a change output on the foreign seed's taproot chain —
 * more moving parts in service of an outcome nobody wants.
 *
 * Pure — no JNI, no network. The seed enters only at signing.
 */
object DigiDollarTransferPlan {

    /** Consensus fee floor for a DigiDollar transaction: 0.1 DGB. Mirrors DD_MIN_FEE_SATS in
     *  digidollar_transfer_layout.h, which the native builder enforces independently. */
    const val DD_MIN_FEE_SATS = 10_000_000L

    /** Per-output consensus bounds, in cents. */
    const val DD_MIN_CENTS = 100L          // $1.00
    const val DD_MAX_CENTS = 10_000_000L   // $100,000

    data class Plan(
        val ddInputs: List<DigiDollarScan.Holding>,
        val feeInputs: List<ForeignAssetTransferPlan.Spend>,
        val recipientKeyHex: String,
        val changeAddress: String,
        val changeAmountSat: Long,
        val feeSat: Long,
        val cents: Long,
    )

    enum class Reason {
        /** Not enough DGB to meet the DigiDollar consensus fee floor. */
        BELOW_FEE_FLOOR,
        /** Under the $1.00 per-output consensus minimum. */
        BELOW_MIN_CENTS,
        /** Over the $100,000 per-transfer-output maximum. */
        ABOVE_MAX_CENTS,
        /** No spendable DigiDollar outpoint, or nowhere to send it. */
        NOTHING_TO_MOVE,
    }

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Refused(
            val reason: Reason,
            /** Sats short of the fee floor, when that is why. Zero otherwise. */
            val shortfallSat: Long,
            val detail: String,
        ) : Result()
    }

    /**
     * @param holdings   spendable DigiDollar outpoints, from [DigiDollarScan].
     * @param totalCents everything those outpoints hold. Moved in full.
     * @param feeInputs  plain DGB to pay the consensus fee with.
     */
    fun build(
        holdings: List<DigiDollarScan.Holding>,
        totalCents: Long,
        feeInputs: List<ForeignAssetTransferPlan.Spend>,
        recipientKeyHex: String,
        changeAddress: String,
        feePerKb: Long,
    ): Result {
        if (holdings.isEmpty() || recipientKeyHex.isBlank()) {
            return Result.Refused(Reason.NOTHING_TO_MOVE, 0L, "no DigiDollar outpoint to move")
        }
        if (totalCents < DD_MIN_CENTS) {
            return Result.Refused(
                Reason.BELOW_MIN_CENTS, 0L,
                "$totalCents cents is under the \$1.00 per-output consensus minimum",
            )
        }
        if (totalCents > DD_MAX_CENTS) {
            return Result.Refused(
                Reason.ABOVE_MAX_CENTS, 0L,
                "$totalCents cents is over the \$100,000 per-transfer maximum",
            )
        }

        val available = feeInputs.sumOf { it.amountSat }

        // Size from the real shape, then hold it at the consensus floor. The floor dominates in
        // practice — a DD transfer is small — but sizing first means an unusually large transfer
        // is not underpaid just because the floor happened to be enough for a typical one.
        val vsize = 10L + (holdings.size + feeInputs.size) * 160L + 3L * 34L + 20L
        val sized = maxOf(vsize * feePerKb / 1000L, 1_000L)
        val fee = maxOf(sized, DD_MIN_FEE_SATS)

        if (available < fee) {
            return Result.Refused(
                Reason.BELOW_FEE_FLOOR,
                shortfallSat = fee - available,
                detail = "a DigiDollar transfer needs $fee sats of DGB; this wallet has $available",
            )
        }

        // A change output below dust would be unspendable, so the remainder rides to the fee.
        val remainder = available - fee
        val change = if (remainder > ForeignAssetTransferPlan.CHANGE_DUST_THRESHOLD) remainder else 0L

        return Result.Ok(
            Plan(
                ddInputs = holdings,
                feeInputs = feeInputs,
                recipientKeyHex = recipientKeyHex,
                changeAddress = changeAddress,
                changeAmountSat = change,
                feeSat = available - change,
                cents = totalCents,
            )
        )
    }
}
