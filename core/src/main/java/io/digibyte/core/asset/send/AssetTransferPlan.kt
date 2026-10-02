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
        /** Plain DGB change to the source address, before the last output; present only above
         *  the change dust threshold. No instruction targets it and it is not last, so the
         *  protocol puts no asset on it. */
        DGB_CHANGE,
        /** Always the last output, always to the source address: the asset change (when units
         *  come back) and the destination of every unit the instructions do not assign. */
        ASSET_CHANGE_MARKER,
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
    /** The one script every asset input pays to; the DGB change and the last output pay it too. */
    val sourceScript: ByteArray,
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

    /** A plan paying [paidFeeSats] may be signed under an approval of [approvedFeeSats]: never
     *  more than the user saw on the confirmation (B231). A zero or negative approval approves
     *  nothing. */
    fun feeWithinApproval(paidFeeSats: Long, approvedFeeSats: Long): Boolean =
        approvedFeeSats > 0 && paidFeeSats in 0..approvedFeeSats

    sealed interface Result {
        data class Ready(val plan: AssetTransferPlan) : Result
        /** Nothing is built; [message] is what the send reports. */
        data class Refused(val message: String) : Result
    }

    /**
     * The asset's outputs grouped by the script they pay to, keeping only the groups that can
     * cover [quantity] alone, largest first. A send draws its asset inputs from one group, so
     * every unit the transfer does not assign ends where it started (see [plan]).
     */
    fun sourceGroups(assetUtxos: List<UtxoEntity>, quantity: Long): List<List<UtxoEntity>> =
        assetUtxos
            .groupBy { it.scriptPubKey.toHexString() }
            .values
            .filter { group -> group.sumOf { it.assetQuantity } >= quantity }
            .sortedByDescending { group -> group.sumOf { it.assetQuantity } }

    /** The most of the asset any single address holds: the largest amount one send can move. */
    fun largestSingleSource(assetUtxos: List<UtxoEntity>): Long =
        assetUtxos.groupBy { it.scriptPubKey.toHexString() }.values.maxOfOrNull { g -> g.sumOf { it.assetQuantity } } ?: 0L

    /**
     * Whether a plain coin may pay the fee of a send from [sourceScript]. A fee input's assets,
     * if it had any, would join the leftover and arrive at the source address from elsewhere. So
     * a fee coin is either at the source address itself, or was created by a transaction with no
     * data output, which the protocol cannot have put an asset on. [parentHasDataOutput] is null
     * when the creating transaction cannot be read; such a coin is not used.
     */
    fun feeCoinEligible(coin: UtxoEntity, sourceScript: ByteArray, parentHasDataOutput: Boolean?): Boolean =
        coin.scriptPubKey.contentEquals(sourceScript) || parentHasDataOutput == false

    /**
     * Plan a send of [quantity] from [assetUtxos], which must all pay to one script (the source),
     * with the network fee and markers paid from [dgbUtxos] (already limited to eligible coins).
     *
     * Output layout: recipient marker, OP_RETURN, optional DGB change to the source, and LAST an
     * asset-change marker to the source, emitted on every send. The protocol gives every unit the
     * instructions do not assign to the last output, so under this layout such units stay at the
     * address they came from.
     */
    fun plan(
        assetUtxos: List<UtxoEntity>,
        dgbUtxos: List<UtxoEntity>,
        quantity: Long,
        feePerKb: Long,
    ): Result {
        val sourceScript = assetUtxos.firstOrNull()?.scriptPubKey
            ?: return Result.Refused("No UTXOs for this asset")
        if (assetUtxos.any { !it.scriptPubKey.contentEquals(sourceScript) }) {
            return Result.Refused("Asset inputs must come from one address")
        }
        // Both markers are emitted on every send: the recipient's and the last output's.
        val markerSats = DA_MARKER_SATS
        val bothMarkers = 2 * markerSats

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
            markerOutputSats = bothMarkers,
        )
        val ok0 = when (bootstrap) {
            is AssetCoinSelector.Result.InsufficientAsset ->
                return Result.Refused("Not enough asset: need ${bootstrap.required}, have ${bootstrap.available}")
            is AssetCoinSelector.Result.InsufficientDgb ->
                return Result.Refused("Not enough DGB for fee: need ${bootstrap.required}, have ${bootstrap.available}")
            is AssetCoinSelector.Result.Ok -> bootstrap
        }

        // The instructions' size does not depend on which vout the change instruction names (an
        // output index below 32 is always 5 bits), so the fee loop can run on a provisional
        // encoding and the final one is built once the DGB change is known.
        val recipientVout = 0
        val provisional = encodeInstructions(ok0, quantity, recipientVout, assetChangeVout = 3)
            ?: return Result.Refused("Could not build transfer instructions")

        // Value-output count for the estimate: recipient + DGB change (assumed) + last output.
        val estimateOutputCount = 3
        val maxFeeIterations = dgbUtxos.size + 2
        var estimatedForDgbInputs = ok0.dgbInputs.size
        var feeSats = bootstrapFeeSats
        var ok = ok0
        for (iter in 0 until maxFeeIterations) {
            feeSats = AssetFeeEstimator.estimateAssetTxFeeSats(
                assetInputCount = ok0.assetInputs.size,
                dgbInputCount = estimatedForDgbInputs,
                outputCount = estimateOutputCount,
                opReturnBytes = provisional.size,
                feePerKb = feePerKb,
            )
            val selection = AssetCoinSelector.select(
                assetUtxos = assetUtxos,
                dgbUtxos = dgbUtxos,
                assetNeeded = quantity,
                feeSats = feeSats,
                markerOutputSats = bothMarkers,
            )
            ok = when (selection) {
                is AssetCoinSelector.Result.InsufficientAsset ->
                    return Result.Refused("Not enough asset: need ${selection.required}, have ${selection.available}")
                is AssetCoinSelector.Result.InsufficientDgb ->
                    return Result.Refused("Not enough DGB for fee: need ${selection.required}, have ${selection.available}")
                is AssetCoinSelector.Result.Ok -> selection
            }
            if (ok.dgbInputs.size <= estimatedForDgbInputs) break
            estimatedForDgbInputs = ok.dgbInputs.size
        }

        val outputs = mutableListOf(
            PlannedOutput(PlannedOutput.Role.RECIPIENT_MARKER, markerSats),
            PlannedOutput(PlannedOutput.Role.ASSET_DATA, 0L),
        )
        val dgbChange = ok.dgbChangeSats
        if (dgbChange > DGB_CHANGE_DUST_THRESHOLD) {
            outputs += PlannedOutput(PlannedOutput.Role.DGB_CHANGE, dgbChange)
        }
        outputs += PlannedOutput(PlannedOutput.Role.ASSET_CHANGE_MARKER, markerSats)
        val lastVout = outputs.lastIndex

        val opReturnScript = encodeInstructions(ok0, quantity, recipientVout, assetChangeVout = lastVout)
            ?: return Result.Refused("Could not build transfer instructions")
        if (opReturnScript.size != provisional.size) {
            return Result.Refused("Transfer instructions changed size")
        }

        val plan = AssetTransferPlan(
            assetInputs = ok.assetInputs,
            dgbInputs = ok.dgbInputs,
            outputs = outputs,
            opReturnScript = opReturnScript,
            estimatedFeeSats = feeSats,
            sourceScript = sourceScript,
        )
        val beyondEstimate = plan.paidFeeSats - plan.estimatedFeeSats
        if (beyondEstimate !in 0L..DGB_CHANGE_DUST_THRESHOLD) {
            return Result.Refused("Fee does not match the transaction: pays ${plan.paidFeeSats}, estimated ${plan.estimatedFeeSats}")
        }
        return Result.Ready(plan)
    }

    private fun encodeInstructions(
        selection: AssetCoinSelector.Result.Ok,
        quantity: Long,
        recipientVout: Int,
        assetChangeVout: Int,
    ): ByteArray? {
        val instructions = buildTransferInstructions(
            assetInputs = selection.assetInputs,
            quantityToRecipient = quantity,
            assetChangeQty = selection.assetChangeQty,
            recipientVout = recipientVout,
            assetChangeVout = assetChangeVout,
        ) ?: return null
        return try {
            DigiAssetEncoder.encodeTransferScript(version = 3, instructions = instructions)
        } catch (e: Exception) {
            null
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

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
