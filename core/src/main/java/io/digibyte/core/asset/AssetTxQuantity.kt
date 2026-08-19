package io.digibyte.core.asset

import io.digibyte.core.model.AssetOperation

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
 *  - **BURN**: 0 to every output (asset destroyed).
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
    ): Long = when (header.operation) {
        AssetOperation.ISSUANCE ->
            if (vout == firstNonOpReturnVout) (header.totalQuantity ?: 0L) else 0L

        AssetOperation.TRANSFER ->
            header.transferInstructions
                .asSequence()
                .filter { !it.percent && !it.isBurn }
                .filter { inst -> if (inst.range) vout <= inst.outputIndex else inst.outputIndex == vout }
                .sumOf { it.amount }

        AssetOperation.BURN -> 0L
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
        return explicit + (implicitChange(header, inputUnits, outputCount) ?: 0L)
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
     * ISSUANCE returns 0: the issued supply is credited by [forOutput]'s first-non-OP_RETURN
     * convention, so computing a leftover here would double-count the issuer's marker.
     */
    fun implicitChange(header: DecodedAssetHeader, inputUnits: Long?, outputCount: Int): Long? {
        if (header.operation == AssetOperation.ISSUANCE) return 0L
        if (inputUnits == null) return null
        var assigned = 0L
        for (inst in header.transferInstructions) {
            if (inst.percent) return null
            assigned += if (inst.range) (inst.outputIndex.toLong() + 1L) * inst.amount else inst.amount
        }
        return (inputUnits - assigned).coerceAtLeast(0L)
    }

    /** The output index [implicitChange] lands on: the transaction's last output, verbatim.
     *  When that output is the OP_RETURN the reference credits it there anyway (an effective
     *  burn) — mirror the reference rather than "improving" it, or our view of the chain
     *  diverges from every other implementation's. */
    fun implicitChangeVout(outputCount: Int): Int = outputCount - 1
}
