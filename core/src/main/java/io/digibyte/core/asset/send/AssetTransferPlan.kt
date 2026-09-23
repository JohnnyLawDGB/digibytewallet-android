package io.digibyte.core.asset.send

import io.digibyte.core.asset.DigiAssetEncoder
import io.digibyte.core.db.entity.UtxoEntity

/** DGB change below this floor is folded into the fee rather than
 *  emitted as its own output. MUST be >= the network dust threshold
 *  for the change address type, or the node rejects the whole tx
 *  with reject-reason "dust". DigiByte 9.26 raised dust to 30,000
 *  sat/kB → legacy P2PKH floor = 5,460 sats (measured on a 9.26.4
 *  node). We use the legacy worst case so a change output is never
 *  dust regardless of the change address's script type. The old
 *  1,000 value produced dust change outputs that stalled sends. */
const val DGB_CHANGE_DUST_THRESHOLD: Long = 5_460L

/** One output of an asset send, in the order it is signed. */
data class PlannedOutput(val role: Role, val sats: Long) {
    enum class Role {
        /** The recipient's marker: vout 0, the output the transfer instructions credit. */
        RECIPIENT_MARKER,
        /** The OP_RETURN carrying the transfer instructions: vout 1, zero value. */
        ASSET_DATA,
        /** The sender's asset-change marker: vout 2, present only when units come back. */
        ASSET_CHANGE_MARKER,
        /** Plain DGB change, last; present only above the change dust threshold. */
        DGB_CHANGE,
    }
}

/**
 * Everything an asset send signs, decided before anything touches the native layer: which
 * coins it spends and what each output is worth. AssetManager.sendAsset turns [outputs] into
 * addresses and scripts in this order and signs exactly [inputs].
 */
data class AssetTransferPlan(
    val assetInputs: List<UtxoEntity>,
    val dgbInputs: List<UtxoEntity>,
    val outputs: List<PlannedOutput>,
    val opReturnScript: ByteArray,
    /** The fee the size estimate asked for. */
    val estimatedFeeSats: Long,
) {
    /** Asset inputs first (the instructions are read against them in order), then fee inputs. */
    val inputs: List<UtxoEntity> get() = assetInputs + dgbInputs

    /** What the signed transaction pays the network: every input's value minus every output's.
     *  Equals [estimatedFeeSats] plus a DGB remainder too small to be its own output. */
    val paidFeeSats: Long get() = inputs.sumOf { it.satoshis } - outputs.sumOf { it.sats }
}

/**
 * The pure half of an asset send: selection, transfer instructions, the size-aware fee and the
 * output values. No I/O and no native calls, so the numbers a send signs are testable on the JVM.
 */
object AssetTransferPlanner {

    sealed interface Result {
        data class Ready(val plan: AssetTransferPlan) : Result
        /** Nothing is built; [message] is what the send reports. */
        data class Refused(val message: String) : Result
    }

    fun plan(
        assetUtxos: List<UtxoEntity>,
        dgbUtxos: List<UtxoEntity>,
        quantity: Long,
        feePerKb: Long,
    ): Result {
        // The markers budgeted are the markers emitted: the recipient's always, the
        // asset-change marker only when the chosen asset inputs hold more than the send. The
        // selector decides the second from its own asset selection, which is fee-independent,
        // so every select below budgets the same markers the output list emits.
        val markerSats = DA_MARKER_SATS

        // First (bootstrap) selection with a conservative typical-shape
        // fee. The asset-input set and the OP_RETURN are FEE-INDEPENDENT
        // (they depend only on the transfer quantity), so this select
        // reveals the stable parts of the shape; only the DGB fee inputs
        // and DGB change vary with the fee. The bootstrap's DGB-input count
        // merely seeds the convergence loop below — it is NOT
        // assumed to be within one input of the final count.
        val bootstrapFeeSats = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1,
            dgbInputCount = 1,
            outputCount = 3,
            opReturnBytes = 80,
            feePerKb = feePerKb,
        )
        val bootstrap = AssetCoinSelector.select(
            assetUtxos = assetUtxos,
            dgbUtxos = dgbUtxos,
            assetNeeded = quantity,
            feeSats = bootstrapFeeSats,
            markerOutputSats = markerSats,
            assetChangeMarkerSats = markerSats,
        )
        val ok0 = when (bootstrap) {
            is AssetCoinSelector.Result.InsufficientAsset ->
                return Result.Refused("Not enough asset: need ${bootstrap.required}, have ${bootstrap.available}")
            is AssetCoinSelector.Result.InsufficientDgb ->
                return Result.Refused("Not enough DGB for fee: need ${bootstrap.required}, have ${bootstrap.available}")
            is AssetCoinSelector.Result.Ok -> bootstrap
        }

        val hasAssetChange = ok0.assetChangeQty > 0L

        // Output layout. Recipient marker at vout 0, OP_RETURN at vout 1,
        // optional asset-change marker at vout 2, optional DGB change at
        // the next free vout. Transfer instructions reference these vouts
        // directly so we have to commit to the layout before encoding.
        val recipientVout = 0
        val assetChangeVout = if (hasAssetChange) 2 else -1

        // Transfer instructions, built from the bootstrap selection's asset side — identical
        // across every select since asset selection is fee-independent.
        val instructions = buildTransferInstructions(
            assetInputs = ok0.assetInputs,
            quantityToRecipient = quantity,
            assetChangeQty = ok0.assetChangeQty,
            recipientVout = recipientVout,
            assetChangeVout = assetChangeVout,
        ) ?: return Result.Refused("Could not build transfer instructions")

        val opReturnScript = try {
            DigiAssetEncoder.encodeTransferScript(version = 3, instructions = instructions)
        } catch (e: Exception) {
            return Result.Refused("Encode failed: ${e.message}")
        }

        // Now that we know the real OP_RETURN length and the concrete
        // output count (recipient + optional asset-change + a DGB-change
        // output we conservatively assume is present), compute the actual
        // size-aware fee and RE-select with it. Value-output count for the
        // estimate: recipient(1) + asset-change(0/1) + dgb-change(1).
        //
        // CONVERGENCE LOOP (not a single pass): the size-aware fee is a
        // function of the DGB-input count, and the DGB-input count is a
        // function of the fee — a wallet whose DGB side is fragmented into
        // many small UTXOs can pull far more inputs when the fee jumps from
        // the bootstrap estimate to the real one than the estimator's fixed
        // +1-input margin covers. If we only re-selected once, the built tx
        // would pay below the 100 sat/byte min relay for its (larger) actual
        // vsize and never relay. So iterate select→estimate→select, feeding
        // the actual DGB-input count back into the next fee estimate, until
        // the count stops growing. DGB-input count is monotonically
        // non-decreasing in the fee and bounded by dgbUtxos.size, so the
        // loop is guaranteed to reach a fixed point; the cap is a safety net.
        val estimateOutputCount = 1 + (if (hasAssetChange) 1 else 0) + 1
        // dgbUtxos.size distinct growth steps at most, +2 slack. Never below 2.
        val maxFeeIterations = dgbUtxos.size + 2
        var estimatedForDgbInputs = ok0.dgbInputs.size
        var feeSats = bootstrapFeeSats
        var ok = ok0
        for (iter in 0 until maxFeeIterations) {
            feeSats = AssetFeeEstimator.estimateAssetTxFeeSats(
                assetInputCount = ok0.assetInputs.size,
                dgbInputCount = estimatedForDgbInputs,
                outputCount = estimateOutputCount,
                opReturnBytes = opReturnScript.size,
                feePerKb = feePerKb,
            )
            val selection = AssetCoinSelector.select(
                assetUtxos = assetUtxos,
                dgbUtxos = dgbUtxos,
                assetNeeded = quantity,
                feeSats = feeSats,
                markerOutputSats = markerSats,
                assetChangeMarkerSats = markerSats,
            )
            ok = when (selection) {
                is AssetCoinSelector.Result.InsufficientAsset ->
                    return Result.Refused("Not enough asset: need ${selection.required}, have ${selection.available}")
                is AssetCoinSelector.Result.InsufficientDgb ->
                    return Result.Refused("Not enough DGB for fee: need ${selection.required}, have ${selection.available}")
                is AssetCoinSelector.Result.Ok -> selection
            }
            // Converged: the fee we just charged was estimated for at least as
            // many DGB inputs as the selection actually pulled (the estimator's
            // internal +1 margin then still leaves a cushion), so the built tx
            // pays >= min relay for its real vsize.
            if (ok.dgbInputs.size <= estimatedForDgbInputs) break
            estimatedForDgbInputs = ok.dgbInputs.size
        }

        // The output list — order locked to match the vout references baked into the transfer
        // instructions above. The asset side of `ok` is identical to `ok0` (fee-independent);
        // only the DGB inputs / change reflect the real fee.
        val outputs = mutableListOf(
            PlannedOutput(PlannedOutput.Role.RECIPIENT_MARKER, markerSats),
            PlannedOutput(PlannedOutput.Role.ASSET_DATA, 0L),
        )
        if (hasAssetChange) outputs += PlannedOutput(PlannedOutput.Role.ASSET_CHANGE_MARKER, markerSats)
        val dgbChange = ok.dgbChangeSats
        if (dgbChange > DGB_CHANGE_DUST_THRESHOLD) {
            outputs += PlannedOutput(PlannedOutput.Role.DGB_CHANGE, dgbChange)
        }

        val plan = AssetTransferPlan(
            assetInputs = ok.assetInputs,
            dgbInputs = ok.dgbInputs,
            outputs = outputs,
            opReturnScript = opReturnScript,
            estimatedFeeSats = feeSats,
        )
        // Invariant: what the signed transaction pays is the estimated fee plus at most a DGB
        // remainder below the change dust threshold. Checked on the final values, not assumed.
        val beyondEstimate = plan.paidFeeSats - plan.estimatedFeeSats
        if (beyondEstimate !in 0L..DGB_CHANGE_DUST_THRESHOLD) {
            return Result.Refused("Fee does not match the transaction: pays ${plan.paidFeeSats}, estimated ${plan.estimatedFeeSats}")
        }
        return Result.Ready(plan)
    }

    /**
     * Build the DA TRANSFER instruction list for a single-recipient send
     * with optional asset change.
     *
     * Walks the chosen asset inputs in order. Each input contributes its
     * full quantity, distributed first toward the recipient (until [quantity
     * ToRecipient] is exhausted), then toward the asset-change marker. The
     * last instruction pulling from a non-final input is marked `skip=true`
     * so the decoder advances to the next input.
     *
     * Returns null only if the input set's combined quantity doesn't match
     * `quantityToRecipient + assetChangeQty` — programmer error, never user
     * error (the coin selector enforces sums).
     */
    private fun buildTransferInstructions(
        assetInputs: List<UtxoEntity>,
        quantityToRecipient: Long,
        assetChangeQty: Long,
        recipientVout: Int,
        assetChangeVout: Int,
    ): List<DigiAssetEncoder.TransferInstruction>? {
        val totalIn = assetInputs.sumOf { it.assetQuantity }
        if (totalIn != quantityToRecipient + assetChangeQty) return null

        val out = mutableListOf<DigiAssetEncoder.TransferInstruction>()
        var qtyRemaining = quantityToRecipient
        var changeRemaining = assetChangeQty

        for ((idx, input) in assetInputs.withIndex()) {
            val isLastInput = idx == assetInputs.lastIndex
            var inputRemaining = input.assetQuantity

            // Allocate toward recipient first.
            if (inputRemaining > 0 && qtyRemaining > 0) {
                val take = minOf(inputRemaining, qtyRemaining)
                out += DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = recipientVout, amount = take,
                )
                qtyRemaining -= take
                inputRemaining -= take
            }

            // Then toward asset change.
            if (inputRemaining > 0 && changeRemaining > 0 && assetChangeVout >= 0) {
                val take = minOf(inputRemaining, changeRemaining)
                out += DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = assetChangeVout, amount = take,
                )
                changeRemaining -= take
                inputRemaining -= take
            }

            // Mark the last instruction pulling from this input with skip=true
            // (except on the final input — skip is a no-op there).
            if (!isLastInput && out.isNotEmpty()) {
                val last = out.removeAt(out.lastIndex)
                out += last.copy(skip = true)
            }
        }
        return out
    }
}
