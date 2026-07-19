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
}
