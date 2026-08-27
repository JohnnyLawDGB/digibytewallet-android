package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry

/**
 * Keeps enough DGB behind to pay for moving the assets the sweep refused to spend.
 *
 * ## Why [SweepPartition] alone was not enough
 *
 * The partition stops an asset being spent as plain DGB and destroyed — verified on mainnet. But
 * it left the asset holding only its own marker output, around 6,000 sats, while a DigiAsset
 * transfer costs roughly [DEFAULT_FEE_PER_ASSET]. The asset was safe and **could not pay to move
 * itself**: stranded in a wallet the user was in the middle of leaving, reachable again only by
 * sending DGB back into it.
 *
 * Not destroying an asset was never the goal. Getting it to the new wallet is — and the DGB that
 * pays for that is exactly what the sweep was about to take. Assets have to move while their fee
 * money is still there, so until transfer-on-sweep exists, the money stays put.
 *
 * ## Whole UTXOs, smallest first
 *
 * A UTXO cannot be split, so the reserve is met by holding back whole ones, smallest first.
 * Everything reserved is money the user does not receive today, so meeting a 40,000-sat reserve
 * with a 5 DGB output when a 0.5 DGB one would do is a worse answer even though both "work".
 */
object AssetFeeReserve {

    /**
     * Rough cost of one DigiAsset transfer: ~400 vbytes at DigiByte's 100 sat/byte minimum relay
     * fee. Deliberately an over-estimate — reserving slightly too much costs a later sweep;
     * reserving too little strands the asset, which is the failure this exists to prevent.
     */
    const val DEFAULT_FEE_PER_ASSET = 40_000L

    data class Result(
        /** Still safe to sweep once the reserve is set aside. */
        val stillSweepable: List<UtxoEntry>,
        /** Held back so the assets can be moved later. */
        val reserved: List<UtxoEntry>,
        /** Sats short of the target. Non-zero means the assets cannot be moved from this wallet
         *  without new funds, and the user has to be told plainly. */
        val shortfall: Long,
    )

    /**
     * @param sweepable  what [SweepPartition] cleared for sweeping.
     * @param assetCount how many asset-bearing outpoints were held back.
     * @param feePerAsset estimated cost of moving one asset.
     */
    fun reserve(
        sweepable: List<UtxoEntry>,
        assetCount: Int,
        feePerAsset: Long = DEFAULT_FEE_PER_ASSET,
    ): Result {
        if (assetCount <= 0) return Result(sweepable, emptyList(), 0L)

        val target = assetCount * feePerAsset
        val available = sweepable.sumOf { it.amountSatoshi }

        // Not enough to cover it: hold EVERYTHING rather than sweep. Sweeping here would take the
        // last coins that could ever move the asset — precisely what the reserve prevents — and
        // doing that while reporting a successful recovery is the worst available outcome.
        if (available < target) {
            return Result(emptyList(), sweepable, target - available)
        }

        val reserved = mutableListOf<UtxoEntry>()
        var held = 0L
        // Smallest first, so the least money is withheld from the user today.
        for (utxo in sweepable.sortedBy { it.amountSatoshi }) {
            if (held >= target) break
            reserved += utxo
            held += utxo.amountSatoshi
        }

        val reservedSet = reserved.toSet()
        return Result(
            stillSweepable = sweepable.filterNot { it in reservedSet },
            reserved = reserved,
            shortfall = 0L,
        )
    }
}
