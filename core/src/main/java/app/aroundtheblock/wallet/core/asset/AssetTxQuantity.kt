package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.AssetOperation

/**
 * Pure per-output DigiAsset token quantity, computed sovereignly from the decoded
 * OP_RETURN header — no network, no index. The single source of truth for "how many
 * tokens land on output `vout`", shared by asset detection/persistence and the
 * activity-row token-count display.
 *
 * Handles:
 *  - **ISSUANCE**: `totalQuantity` lands on the first non-OP_RETURN output (DA
 *    convention — the issuer's marker); other outputs get 0.
 *  - **FIXED transfer** (`!range`): the instruction's `amount` lands on
 *    `outputIndex` only.
 *  - **RANGE transfer** (`range`): the instruction's `amount` lands on EVERY output
 *    `0..outputIndex` inclusive (confirmed vs RenzoDD/digiasset-core
 *    `DigiByteTransaction.cpp:257-329` — `startI = range ? 0 : output`). Previously
 *    dropped entirely, so range receives under-counted to 0.
 *  - **BURN**: like a transfer, except that a non-range instruction to output 31 destroys
 *    its units and credits nobody. The burn transaction's other instructions still deliver
 *    (DigiAsset_Core `DigiByteTransaction.cpp` `decodeAssetTransfer` applies the destroy marker
 *    only when `type == DIGIASSET_BURN`). In a TRANSFER, output 31 is an ordinary output.
 *  - **UNCLASSIFIABLE**: 0 to every output — the carrier could not be read, so nothing is
 *    credited; [targetsOutput] holds every output instead.
 *
 * SKIPS **percent** instructions: resolving a percentage needs the per-input asset
 * balances (an index / provenance walk we don't have here), and the reference
 * implementation's percent path is itself buggy. An underestimate over a fake number.
 * Callers already exclude the OP_RETURN output, so range hitting the marker vout is
 * a non-issue (its share is an unintentional burn we don't count).
 */
object AssetTxQuantity {
    fun forOutput(
        header: DecodedAssetHeader,
        vout: Int,
        firstNonOpReturnVout: Int?,
    ): Long {
        when (header.operation) {
            AssetOperation.ISSUANCE ->
                return if (vout == firstNonOpReturnVout) (header.totalQuantity ?: 0L).coerceAtLeast(0L) else 0L
            AssetOperation.UNCLASSIFIABLE -> return 0L
            AssetOperation.TRANSFER, AssetOperation.BURN -> Unit
        }
        // Checked: an instruction list whose amounts are not counts (a negative one, or a sum past
        // the signed range) credits nothing rather than a wrapped number.
        var sum = 0L
        for (inst in header.transferInstructions) {
            if (inst.percent || destroys(header, inst)) continue
            if (inst.amount < 0L || inst.outputIndex < 0) return 0L
            val named = if (inst.range) vout <= inst.outputIndex else inst.outputIndex == vout
            if (!named) continue
            sum = try { Math.addExact(sum, inst.amount) } catch (e: ArithmeticException) { return 0L }
        }
        return sum
    }

    /**
     * The whole per-output quantity: what the explicit instructions assign ([forOutput])
     * plus the implicit remainder ([implicitChange]) when this is the transaction's last
     * output. Detection and display both go through here so they cannot diverge.
     *
     * An unknown remainder credits nothing — the balance under-states rather than invents.
     * Keeping an output OUT of a plain-DGB spend is a separate, fail-closed decision that
     * does NOT wait on the quantity being knowable; see the spec.
     */
    fun forOutputTotal(
        header: DecodedAssetHeader,
        vout: Int,
        firstNonOpReturnVout: Int?,
        inputUnits: Long?,
        outputCount: Int,
    ): Long {
        val explicit = forOutput(header, vout, firstNonOpReturnVout)
        if (vout != implicitChangeVout(outputCount)) return explicit
        val change = implicitChange(header, inputUnits, outputCount) ?: return explicit
        return try { Math.addExact(explicit, change) } catch (e: ArithmeticException) { 0L }
    }

    /**
     * Units the transfer instructions do NOT assign, which the protocol credits to the
     * transaction's LAST output ("implicit change"). This is the rule bread-era wallets and
     * digiasset-core rely on: they emit one instruction for the recipient and let the
     * remainder ride. Confirmed against DigiAsset_Core `DigiByteTransaction.cpp`
     * `decodeAssetTransfer` — after the instruction loop, `lastOutput = _outputs.size() - 1`
     * receives whatever is left in every input.
     *
     * Consumption is NOT the same as crediting: a range instruction credits `amount` to each
     * output in `0..outputIndex` but consumes `(outputIndex + 1) * amount` from the inputs
     * (`totalAmount = range ? (output + 1) * amount : amount` in the reference), and a burn
     * instruction consumes its units while crediting nobody.
     *
     * Returns null for "unknown" — [inputUnits] unresolved, or a percent instruction whose
     * amount depends on per-input balances we don't have here. Callers must credit nothing
     * on null rather than guess a quantity; see the spec's fail-closed rule, which keeps the
     * *spending* decision separate from the *display* decision.
     *
     * Also null when what the instructions consume is not a count ([assignedUnits] null: a
     * negative amount, or a product or sum past the signed range). Instructions that consume more
     * than [inputUnits] are invalid: DigiAsset Core voids every one of them and the last output
     * receives everything the inputs carried, so the remainder is [inputUnits] itself, never a
     * clamped zero. **The remainder is never more than the inputs carried, and never wraps.**
     *
     * ISSUANCE returns 0: the issued supply is credited by [forOutput]'s first-non-OP_RETURN
     * convention, so computing a leftover here would double-count the issuer's marker.
     * UNCLASSIFIABLE returns null: with no instructions read, no remainder is known.
     */
    fun implicitChange(header: DecodedAssetHeader, inputUnits: Long?, outputCount: Int): Long? {
        if (header.operation == AssetOperation.ISSUANCE) return 0L
        if (header.operation == AssetOperation.UNCLASSIFIABLE) return null
        if (inputUnits == null || inputUnits < 0L) return null
        val assigned = assignedUnits(header) ?: return null
        if (assigned > inputUnits) return inputUnits
        return inputUnits - assigned
    }

    /**
     * Units the instructions CONSUME from the inputs: a fixed instruction its amount, a range
     * instruction `(outputIndex + 1) * amount`, a burn instruction its amount. Null when a
     * percent instruction makes the total depend on per-input balances, or when the carrier is
     * UNCLASSIFIABLE (its instructions were never read). The input total at which
     * [implicitChange] is exactly zero.
     *
     * Also null when the consumption is not a count: a negative amount or index, or a product or
     * sum past the signed 64-bit range. Every step is exact; nothing here wraps.
     */
    fun assignedUnits(header: DecodedAssetHeader): Long? {
        if (header.operation == AssetOperation.UNCLASSIFIABLE) return null
        var assigned = 0L
        for (inst in header.transferInstructions) {
            if (inst.percent) return null
            if (inst.amount < 0L || inst.outputIndex < 0) return null
            try {
                val consumed = if (inst.range) Math.multiplyExact(inst.outputIndex.toLong() + 1L, inst.amount) else inst.amount
                assigned = Math.addExact(assigned, consumed)
            } catch (e: ArithmeticException) {
                return null
            }
        }
        return assigned
    }

    /**
     * Can what the fixed and range instructions consume not be counted — a negative amount or
     * index, or a total past the signed 64-bit range? Such instructions are never an honest
     * transfer (no input holds that many units), so nothing about the transaction's outputs is
     * believed. Percent instructions are left out: what they consume depends on the inputs.
     */
    fun consumptionUncountable(header: DecodedAssetHeader): Boolean {
        if (header.operation == AssetOperation.UNCLASSIFIABLE) return false
        var assigned = 0L
        for (inst in header.transferInstructions) {
            if (inst.percent) continue
            if (inst.amount < 0L || inst.outputIndex < 0) return true
            try {
                val consumed = if (inst.range) Math.multiplyExact(inst.outputIndex.toLong() + 1L, inst.amount) else inst.amount
                assigned = Math.addExact(assigned, consumed)
            } catch (e: ArithmeticException) {
                return true
            }
        }
        return false
    }

    /** The output index [implicitChange] lands on: the transaction's last output, verbatim.
     *  When that output is the OP_RETURN the reference credits it there anyway (an effective
     *  burn) — mirror the reference rather than "improving" it, or our view of the chain
     *  diverges from every other implementation's. */
    fun implicitChangeVout(outputCount: Int): Int = outputCount - 1

    /**
     * Does an instruction (or the implicit-change rule) target output [vout]? This is the
     * PROTECTION decision, and it differs from [forOutput]'s crediting decision in two ways.
     * It is fail-closed and independent of whether the quantity is knowable, so it COUNTS
     * percent targets — a percent instruction still moves units to its output even though the
     * amount cannot be resolved here. And it does NOT count an owned output that no instruction
     * names (ordinary DGB change), so protecting a targeted output never locks change out of
     * spending. Implicit change is targeted when the remainder is positive OR unknown.
     *
     * Every operation distributes units through the same instruction list — an issuance hands
     * out what it issues, and a burn transaction still delivers the units of its non-burn
     * instructions — so instruction targets count for all three. The non-range index 31 is the
     * destroy marker only in a BURN; in any other operation it names a real output (one that
     * exists once a transaction has 32 or more outputs) and is a target like any other.
     *
     * An UNCLASSIFIABLE carrier targets every output: which ones the protocol credits cannot be
     * read from it, so every owned output of the transaction is held, whatever came in.
     */
    fun targetsOutput(
        header: DecodedAssetHeader,
        vout: Int,
        firstNonOpReturnVout: Int?,
        inputUnits: Long?,
        outputCount: Int,
    ): Boolean {
        if (header.operation == AssetOperation.UNCLASSIFIABLE) return true
        val instructionTargets = header.transferInstructions.any { inst ->
            when {
                destroys(header, inst) -> false
                inst.range -> vout <= inst.outputIndex
                else -> inst.outputIndex == vout
            }
        }
        if (instructionTargets) return true
        // The issuer's marker: [forOutput] credits the issued supply here.
        if (header.operation == AssetOperation.ISSUANCE && vout == firstNonOpReturnVout) return true
        if (vout == implicitChangeVout(outputCount)) {
            val change = implicitChange(header, inputUnits, outputCount)
            return change == null || change > 0L
        }
        return false
    }

    /**
     * Is [inst] the destroy marker — units consumed, credited to nobody? Only the non-range
     * output 31 of a BURN operation, as [TransferInstruction.isBurn] is set by the decoder. Read
     * from the instruction and the operation here rather than from the flag, so crediting and
     * holding agree with the reference however an instruction was built.
     */
    private fun destroys(header: DecodedAssetHeader, inst: TransferInstruction): Boolean =
        header.operation == AssetOperation.BURN && !inst.range && inst.outputIndex == 31
}
