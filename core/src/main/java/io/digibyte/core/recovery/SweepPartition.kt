package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry

/**
 * Decides which of a foreign seed's UTXOs may be swept as plain DGB, and which must not be.
 *
 * ## The defect this closes
 *
 * [LegacySweepService] collects every UTXO with a scriptPubKey and spends them into one plain
 * output. It has no asset awareness and structurally could not have any: [UtxoEntry] carries
 * txid, vout, amountSatoshi, address, blockHeight and scriptPubKeyHex, and nothing that could
 * represent an asset.
 *
 * A DigiAsset lives on a specific UTXO. Spend that UTXO as ordinary DGB, without the DigiAsset
 * output structure, and the asset is **destroyed — not moved**. So sweeping a seed that holds
 * assets burns them, on a path shipped today at Settings → "Recover funds from another wallet".
 *
 * ## Why this is a pure function
 *
 * Working out *which* outputs carry assets needs raw transactions and the native DigiAsset
 * parser. Deciding *what to do about it* needs neither, and it is the half where a mistake costs
 * someone their asset. Split out, the rule is testable without JNI and the invariant that matters
 * can be asserted directly: **an outpoint known to carry an asset never appears in [sweepable]**.
 *
 * ## The default is paranoid on purpose
 *
 * An outpoint that could not be classified is held back rather than swept. An unswept asset can
 * be moved tomorrow; a burned one cannot. "We could not check" must never resolve to "go ahead" —
 * which is exactly what the current code does, by never asking.
 */
object SweepPartition {

    data class Result(
        /** Safe to spend as plain DGB. */
        val sweepable: List<UtxoEntry>,
        /** Known to carry a DigiAsset. Must be moved by the asset path, or left alone. */
        val assetBearing: List<UtxoEntry>,
        /** Could not be classified. Held back — see the class note on the paranoid default. */
        val unclassified: List<UtxoEntry>,
    ) {
        /** Total of what will actually be swept. Deliberately excludes the other two buckets, so
         *  a user is never quoted a figure that includes coins the sweep is not going to move. */
        val sweepableSat: Long get() = sweepable.sumOf { it.amountSatoshi }
    }

    /**
     * @param utxos        every UTXO found on the foreign seed's addresses.
     * @param carriesAsset whether this outpoint holds a DigiAsset. Only consulted for outpoints
     *                     that [classified] reports as answerable.
     * @param classified   whether the asset question could be answered for this outpoint at all.
     *                     False means the raw tx was unavailable or unparseable — distinct from
     *                     a confident "no asset here", and treated as unsafe.
     */
    fun split(
        utxos: List<UtxoEntry>,
        carriesAsset: (UtxoEntry) -> Boolean,
        classified: (UtxoEntry) -> Boolean,
    ): Result {
        val sweepable = mutableListOf<UtxoEntry>()
        val assetBearing = mutableListOf<UtxoEntry>()
        val unclassified = mutableListOf<UtxoEntry>()

        for (utxo in utxos) {
            when {
                // Order matters: ask "could we tell?" BEFORE "is it an asset?". Reversed, an
                // unanswerable outpoint would be read as a confident no and swept.
                !classified(utxo) -> unclassified += utxo
                carriesAsset(utxo) -> assetBearing += utxo
                else -> sweepable += utxo
            }
        }

        return Result(sweepable, assetBearing, unclassified)
    }
}
