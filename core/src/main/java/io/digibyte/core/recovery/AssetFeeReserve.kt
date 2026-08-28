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
     * Cost of moving one DigiAsset, derived from the transaction
     * [ForeignAssetTransferPlan] actually builds rather than estimated in the abstract.
     *
     * That transaction is one asset input, one or two DGB inputs, and three outputs (marker,
     * OP_RETURN, change). Priced through `AssetFeeEstimator` at DigiByte's 100 sat/byte minimum
     * relay fee that is ~54,900 sats with one DGB input and ~70,100 with two, and the change
     * output must clear dust on top. The asset's own marker contributes 6,000 of it.
     *
     * This was 40,000 — chosen before there was a transfer to price against, and documented as
     * "deliberately an over-estimate" when it was in fact short. A reserve that holds coins back,
     * tells the user they were kept so the assets could move, and then cannot move them is the
     * failure this class exists to prevent, wearing its fix as a disguise.
     */
    const val DEFAULT_FEE_PER_ASSET = 80_000L

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

        // ONE covering output per asset. Each asset moves in its own transaction
        // ([ForeignAssetTransferBatch]), so two transfers cannot share a fee UTXO — reserving a
        // single large output for three assets funds exactly one of them.
        //
        // Within that, prefer the SMALLEST output that covers an asset on its own: it withholds
        // the least from the user and keeps each transfer to one fee input. Accumulating
        // smallest-first — which this did — is wrong in a way that is not obvious: at 100
        // sat/byte an input costs about 15,000 sats to spend, so paying a ~55,000-sat fee out of
        // 10,000-sat pieces adds cost faster than it adds value and never converges. Fewer,
        // larger inputs is not a preference here, it is the only thing that works.
        val remaining = sweepable.toMutableList()
        val reserved = mutableListOf<UtxoEntry>()
        var unfunded = 0

        repeat(assetCount) {
            val single = remaining
                .filter { it.amountSatoshi >= feePerAsset }
                .minByOrNull { it.amountSatoshi }
            if (single != null) {
                reserved += single
                remaining.remove(single)
            } else {
                unfunded++
            }
        }

        // Assets with no single output big enough still get funded from what is left, largest
        // first so the target is met in as few inputs as possible.
        if (unfunded > 0) {
            var need = unfunded * feePerAsset
            for (utxo in remaining.sortedByDescending { it.amountSatoshi }) {
                if (need <= 0) break
                reserved += utxo
                need -= utxo.amountSatoshi
            }
        }

        val reservedSet = reserved.toSet()
        return Result(
            stillSweepable = sweepable.filterNot { it in reservedSet },
            reserved = reserved,
            shortfall = 0L,
        )
    }
}
