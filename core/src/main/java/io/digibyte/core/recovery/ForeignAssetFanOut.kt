package io.digibyte.core.recovery

import io.digibyte.core.asset.send.AssetFeeEstimator
import io.digibyte.core.asset.send.DA_MARKER_SATS

/**
 * Splits a wallet's plain DGB into enough separate outputs that every DigiAsset can move.
 *
 * ## Why this is needed
 *
 * An asset moves in its own transaction, and two transactions cannot spend the same UTXO, so
 * moving N assets needs N spendable outputs. A transfer's change goes to the DESTINATION wallet,
 * so it never returns to fund the next move.
 *
 * A wallet holding 50 assets and a single DGB output therefore moves exactly one asset, and the
 * only remedy is sending DGB back into a wallet the user is walking away from — the failure
 * `AssetFeeReserve` existed to prevent, reappearing a level up. That shape is common in practice,
 * not hypothetical.
 *
 * Combining the assets into one transaction does not fix it: an instruction costs about two bytes
 * and the 80-byte OP_RETURN leaves room for roughly 38 of them against 50 needed — and it would
 * concentrate every asset onto one output, where a single careless plain-DGB spend destroys all
 * of them at once. Splitting the DGB instead keeps each asset in its own transaction.
 *
 * ## The outputs pay the wallet being recovered, not the destination
 *
 * They have to: the asset transfers spend them, and only the foreign seed can sign for them. It
 * also makes the whole thing restartable — if the app dies after the fan-out confirms, re-running
 * recovery simply finds more plain outputs and carries on. Nothing is stranded, nothing is
 * double-spent.
 *
 * Pure — no JNI, no network. The seed enters only at signing.
 */
object ForeignAssetFanOut {

    data class Out(
        val address: String,
        val amountSat: Long,
        /** True for the per-asset fee outputs; false for the change that returns the remainder. */
        val isFeeOutput: Boolean,
    )

    data class Plan(
        val inputs: List<ForeignAssetTransferPlan.Spend>,
        val outputs: List<Out>,
        val feeSat: Long,
    )

    sealed class Result {
        /** There are already at least as many spendable outputs as assets. */
        data object NotNeeded : Result()
        data class Ok(val plan: Plan) : Result()
        /** Stated BEFORE anything is broadcast — discovering this after three assets have moved
         *  and the money has run out is the worst version of this failure. */
        data class Refused(val shortfallSat: Long, val detail: String) : Result()
    }

    /**
     * What one fee output must hold to fund a single asset transfer.
     *
     * Derived from the transaction that will actually be built — one asset input, one fee input,
     * three outputs — rather than chosen. That exact seam has been got wrong here once already:
     * [AssetFeeReserve] shipped a 40,000-sat constant against a real 54,900–70,100 and called
     * itself "deliberately an over-estimate" while being an under-estimate.
     *
     * The asset's marker does NOT reduce what this output must hold. It appears on both sides of
     * the transfer — spent as the asset input, re-paid as the recipient marker — so it cancels.
     * Subtracting it undersized every output by 6,000 sats, which the seam test caught by
     * building a transfer against the result rather than comparing it to a number.
     */
    fun perAssetOutputSat(feePerKb: Long): Long {
        val transferFee = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1,
            dgbInputCount = 1,
            outputCount = 2,          // marker + change; the OP_RETURN is sized separately
            // Generous: a real DA transfer marker is 8-10 bytes. Over-stating it only makes the
            // output bigger, which is the safe direction — an undersized one strands the asset.
            opReturnBytes = 20,
            feePerKb = feePerKb,
        )
        // +1 so the transfer's change strictly clears dust rather than landing on it.
        return transferFee + ForeignAssetTransferPlan.CHANGE_DUST_THRESHOLD + 1
    }

    /**
     * @param assetCount    asset-bearing outpoints that need to move.
     * @param plainInputs   spendable plain-DGB outpoints. Never asset-bearing — the caller passes
     *                      [SweepPartition]'s sweepable set, so an asset can never be split up.
     * @param sourceAddress an address of the wallet being recovered. Every output pays here.
     */
    fun plan(
        assetCount: Int,
        plainInputs: List<ForeignAssetTransferPlan.Spend>,
        sourceAddress: String,
        feePerKb: Long,
    ): Result {
        if (assetCount <= 0) return Result.NotNeeded
        // Each asset needs its own output; if there are already enough, splitting would only cost
        // a fee and gain nothing.
        if (plainInputs.size >= assetCount) return Result.NotNeeded

        val perAsset = perAssetOutputSat(feePerKb)
        val available = plainInputs.sumOf { it.amountSat }

        // Size the fan-out's own fee from its real shape: every input, every fee output, and a
        // change output.
        val vsize = 10L + plainInputs.size * 160L + (assetCount + 1) * 34L
        val fanOutFee = maxOf(vsize * feePerKb / 1000L, 1_000L)

        val needed = perAsset * assetCount + fanOutFee
        if (available < needed) {
            return Result.Refused(
                shortfallSat = needed - available,
                detail = "moving $assetCount asset(s) needs $needed sats of plain DGB, " +
                    "this wallet has $available",
            )
        }

        val outputs = MutableList(assetCount) { Out(sourceAddress, perAsset, isFeeOutput = true) }
        val change = available - perAsset * assetCount - fanOutFee
        // A remainder too small to be worth an output rides to the fee rather than becoming
        // unspendable dust.
        if (change > ForeignAssetTransferPlan.CHANGE_DUST_THRESHOLD) {
            outputs += Out(sourceAddress, change, isFeeOutput = false)
        }

        return Result.Ok(
            Plan(
                inputs = plainInputs,
                outputs = outputs,
                feeSat = available - outputs.sumOf { it.amountSat },
            )
        )
    }
}
