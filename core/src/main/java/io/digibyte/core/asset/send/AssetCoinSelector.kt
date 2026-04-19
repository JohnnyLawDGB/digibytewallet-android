package io.digibyte.core.asset.send

import io.digibyte.core.db.entity.UtxoEntity

/**
 * Pure selection logic for DigiAsset transfer transactions.
 *
 * A DigiAsset transfer tx has a mixed input set:
 *   - **Asset inputs** — UTXOs carrying the asset type being transferred.
 *     Must sum to at least the send quantity; any surplus returns as asset
 *     change to the sender via transfer-instruction encoding.
 *   - **DGB fee inputs** — plain DGB UTXOs funding the network fee and
 *     the per-recipient "marker" outputs (600–1000 sats per output, which
 *     the DigiAsset spec calls the node-dust threshold).
 *
 * This class picks the minimum-viable set of each kind. No privacy-aware
 * coin selection yet — naive largest-first; enough to ship. Upgrade to
 * BnB / Lyle's algorithm when we have the baseline working.
 *
 * Pure function: no I/O, no coroutines, fully testable.
 */
object AssetCoinSelector {

    /**
     * @param assetUtxos  Unspent UTXOs carrying the asset being transferred.
     * @param dgbUtxos    Unspent plain-DGB UTXOs available for fee funding.
     * @param assetNeeded Asset quantity being transferred (integer units per
     *                    the asset's divisibility — caller scales from UI).
     * @param feeSats     Estimated tx fee in DGB sats (upper bound; any
     *                    surplus returns as DGB change).
     * @param markerOutputSats Total sats across all per-recipient marker
     *                    outputs. For a single-recipient transfer, this is
     *                    one dust output (600 sats); for a multi-recipient
     *                    transfer it's `N × 600`.
     */
    fun select(
        assetUtxos: List<UtxoEntity>,
        dgbUtxos: List<UtxoEntity>,
        assetNeeded: Long,
        feeSats: Long,
        markerOutputSats: Long,
    ): Result {
        require(assetNeeded > 0) { "assetNeeded must be positive" }
        require(feeSats >= 0) { "feeSats must be non-negative" }
        require(markerOutputSats >= 0) { "markerOutputSats must be non-negative" }

        // 1. Pick asset UTXOs — largest first until we cover the quantity.
        //    (Multi-asset UTXOs aren't a thing in DA2's common case; assume
        //    one asset per UTXO at this layer.)
        val sortedAsset = assetUtxos.sortedByDescending { it.assetQuantity }
        val pickedAsset = mutableListOf<UtxoEntity>()
        var assetSum = 0L
        for (u in sortedAsset) {
            if (assetSum >= assetNeeded) break
            pickedAsset += u
            assetSum += u.assetQuantity
        }
        if (assetSum < assetNeeded) {
            return Result.InsufficientAsset(required = assetNeeded, available = assetSum)
        }

        // 2. The asset inputs also contribute their marker sats toward the
        //    tx's DGB balance — we don't need to fund the full marker-output
        //    total from plain DGB, only the delta.
        val assetInputSats = pickedAsset.sumOf { it.satoshis }
        val dgbRequired = (feeSats + markerOutputSats - assetInputSats).coerceAtLeast(0L)

        // 3. Pick DGB UTXOs — largest first until we cover the required sats.
        val pickedDgb = mutableListOf<UtxoEntity>()
        var dgbSum = 0L
        if (dgbRequired > 0) {
            for (u in dgbUtxos.sortedByDescending { it.satoshis }) {
                if (dgbSum >= dgbRequired) break
                pickedDgb += u
                dgbSum += u.satoshis
            }
            if (dgbSum < dgbRequired) {
                return Result.InsufficientDgb(required = dgbRequired, available = dgbSum)
            }
        }

        val totalInSats = assetInputSats + dgbSum
        val dgbChangeSats = totalInSats - feeSats - markerOutputSats
        val assetChangeQty = assetSum - assetNeeded

        return Result.Ok(
            assetInputs = pickedAsset.toList(),
            dgbInputs = pickedDgb.toList(),
            assetChangeQty = assetChangeQty,
            dgbChangeSats = dgbChangeSats,
        )
    }

    sealed class Result {
        data class Ok(
            val assetInputs: List<UtxoEntity>,
            val dgbInputs: List<UtxoEntity>,
            /** Unspent asset quantity to route back to sender via transfer
             *  instructions. Zero when the selected inputs match exactly. */
            val assetChangeQty: Long,
            /** Unspent DGB sats to route back to sender via a change output.
             *  Zero or negative on exact-match (caller should skip the
             *  output if below dust threshold and fold into fee). */
            val dgbChangeSats: Long,
        ) : Result()

        data class InsufficientAsset(val required: Long, val available: Long) : Result()
        data class InsufficientDgb(val required: Long, val available: Long) : Result()
    }
}
