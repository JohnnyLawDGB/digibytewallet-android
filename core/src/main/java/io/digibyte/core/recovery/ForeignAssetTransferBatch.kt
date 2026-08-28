package io.digibyte.core.recovery

/**
 * Shares one pool of reserved DGB across the several DigiAssets a foreign wallet may hold, one
 * transaction each.
 *
 * ## Why per-asset transactions
 *
 * Combining assets into a single transfer is legal, but it couples their fates: one malformed
 * instruction, one dust miscalculation, and every asset in the batch fails together. It also
 * makes the transaction far harder to read back when something does go wrong. [AssetFeeReserve]
 * already sets money aside per outpoint, so per outpoint is the shape the funding arrives in.
 *
 * ## Why allocation is not obvious
 *
 * The reserve is whole UTXOs of assorted sizes — a UTXO cannot be split — and each transfer must
 * clear its own fee AND leave a non-dust change output. Spending generously on the first asset
 * strands the last one with nothing to pay with, which is precisely the failure the reserve was
 * built to prevent, reintroduced one layer up. So the pool is drawn down smallest-first, and a
 * transfer that cannot be funded releases everything it was holding.
 *
 * Pure — no JNI, no network. The seed enters only at signing.
 */
object ForeignAssetTransferBatch {

    /** One asset-bearing outpoint and the units [ForeignAssetQuantity] could read on it. */
    data class AssetItem(
        val spend: ForeignAssetTransferPlan.Spend,
        val units: Long,
    )

    /** What happened for one asset. [outpoint] is carried so the UI can name it either way. */
    data class Item(
        val outpoint: String,
        val result: ForeignAssetTransferPlan.Result,
    )

    /**
     * @param assets      asset-bearing outpoints, with their known unit counts.
     * @param feePool     the DGB [AssetFeeReserve] held back for exactly this.
     * @param destAddress the destination wallet's receive address.
     * @return one [Item] per asset, in the order given. Every asset is reported, funded or not —
     *         an asset that quietly vanished from this list would look moved when it was not.
     */
    fun plan(
        assets: List<AssetItem>,
        feePool: List<ForeignAssetTransferPlan.Spend>,
        destAddress: String,
        feePerKb: Long,
    ): List<Item> {
        // Smallest first, so the least money is committed to each transfer and the largest
        // outputs stay available for whatever still needs funding.
        val available = feePool.sortedBy { it.amountSat }.toMutableList()
        val items = mutableListOf<Item>()

        for (asset in assets) {
            val outpoint = "${asset.spend.txid}:${asset.spend.vout}"

            // Grow the input set one output at a time until the plan builds. Each attempt is a
            // full build, so the fee it clears is the fee for the shape it actually produces —
            // adding an input raises the fee, and this loop lets that settle instead of
            // estimating around it.
            var funded: ForeignAssetTransferPlan.Result? = null
            var used = 0
            for (take in 1..available.size) {
                val candidate = available.take(take)
                val attempt = ForeignAssetTransferPlan.build(
                    assetInput = asset.spend,
                    assetUnits = asset.units,
                    feeInputs = candidate,
                    destAddress = destAddress,
                    feePerKb = feePerKb,
                )
                if (attempt is ForeignAssetTransferPlan.Result.Ok) {
                    funded = attempt
                    used = take
                    break
                }
                // A refusal that more money cannot fix — an unreadable quantity, no destination —
                // stops the search here rather than walking the whole pool to the same answer.
                if (attempt is ForeignAssetTransferPlan.Result.Refused &&
                    attempt.reason != ForeignAssetTransferPlan.Reason.INSUFFICIENT_FEE_FUNDS
                ) {
                    funded = attempt
                    used = 0
                    break
                }
            }

            if (funded == null) {
                // Nothing in the pool, or the whole pool was not enough. Either way this asset
                // stays where it is and the pool is untouched — one unfundable asset must not
                // take the assets after it down with it.
                funded = ForeignAssetTransferPlan.build(
                    assetInput = asset.spend,
                    assetUnits = asset.units,
                    feeInputs = available.toList(),
                    destAddress = destAddress,
                    feePerKb = feePerKb,
                ).let { attempt ->
                    attempt as? ForeignAssetTransferPlan.Result.Refused
                        ?: ForeignAssetTransferPlan.Result.Refused(
                            ForeignAssetTransferPlan.Reason.INSUFFICIENT_FEE_FUNDS,
                            "no DGB left to move $outpoint",
                        )
                }
                used = 0
            }

            if (funded is ForeignAssetTransferPlan.Result.Ok) {
                repeat(used) { available.removeAt(0) }
            }
            items += Item(outpoint, funded)
        }

        return items
    }
}
