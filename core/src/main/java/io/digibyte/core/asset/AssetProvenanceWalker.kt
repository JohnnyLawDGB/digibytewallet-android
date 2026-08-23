package io.digibyte.core.asset

/** On-chain facts about an asset, taken from its issuance header. */
data class ResolvedAssetFacts(
    val assetId: String,
    val totalSupply: Long,
    val divisibility: Int,
    val metadataCid: String?,
)

/** How far a walk from [startTxid] got before it ran out of budget or reachable data. */
data class WalkFrontier(
    val startTxid: String,
    val resumeTxid: String,
    val hopsWalked: Int,
)

/**
 * What the wallet remembers between walks.
 *
 * Both halves matter and they fail differently: [assetFor] makes a repeat resolve free, while
 * the frontier makes a *deep* chain resolvable at all.
 */
interface ProvenanceStore {
    suspend fun assetFor(txid: String): ResolvedAssetFacts?
    suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts)
    suspend fun frontierFor(startTxid: String): WalkFrontier?
    suspend fun putFrontier(frontier: WalkFrontier)
    suspend fun clearFrontier(startTxid: String)
}

/**
 * Walks a DigiAsset transfer back to its issuance, keeping what it learns.
 *
 * A transfer's OP_RETURN carries no asset identity — only an issuance does — so the only way to
 * name an asset that arrived by transfer is to follow it back. The walk itself was never the
 * problem; discarding it was. See AssetProvenanceWalkerTest for the on-device failure this
 * exists to prevent.
 *
 * [hop] is the one thing the caller supplies: given a txid, say what that transaction is. Fetch,
 * OP_RETURN extraction and decoding all live behind it, so this class holds only the progress
 * accounting — which is the part worth testing.
 */
class AssetProvenanceWalker(
    private val hop: suspend (String) -> Hop,
    private val store: ProvenanceStore,
    private val maxHopsPerAttempt: Int = DEFAULT_MAX_HOPS_PER_ATTEMPT,
) {
    sealed interface Hop {
        /** The issuance — the walk's destination. */
        data class Issuance(val facts: ResolvedAssetFacts) : Hop
        /** A transfer; the asset came from [parentTxid]. */
        data class Transfer(val parentTxid: String) : Hop
        /** Temporarily unreadable (no endpoint had it, request failed). Progress is kept. */
        data object Unavailable : Hop
        /** Terminally unresolvable (a burn, an unsupported issuance form). Progress is dropped. */
        data object DeadEnd : Hop
    }

    suspend fun resolve(startTxid: String): ResolvedAssetFacts? {
        store.assetFor(startTxid)?.let { return it }

        // Pick up where the last attempt stopped rather than starting over. This is the whole
        // fix: without it a chain longer than one budget is unreachable no matter how often the
        // walk runs, and each run pays full price to learn nothing.
        val resumed = store.frontierFor(startTxid)
        var current = resumed?.resumeTxid ?: startTxid
        val priorHops = resumed?.hopsWalked ?: 0

        // Everything on this attempt's path shares whatever asset we end up finding, so they all
        // get memoised together — not just the txid we were asked about.
        val proven = linkedSetOf(startTxid)
        val seen = mutableSetOf<String>()

        for (step in 0 until maxHopsPerAttempt) {
            if (!seen.add(current)) {
                // A chain that loops will loop again next time; keeping a resume point would
                // just schedule the same dead end forever.
                store.clearFrontier(startTxid)
                return null
            }

            // An ancestor we already resolved answers the whole question — this is what makes
            // receiving back an asset we previously sent cost one hop instead of the chain.
            store.assetFor(current)?.let { known ->
                store.putAssets(proven.toList(), known)
                store.clearFrontier(startTxid)
                return known
            }

            when (val step2 = hop(current)) {
                is Hop.Issuance -> {
                    store.putAssets((proven + current).toList(), step2.facts)
                    store.clearFrontier(startTxid)
                    return step2.facts
                }
                is Hop.Transfer -> {
                    proven.add(current)
                    current = step2.parentTxid
                }
                Hop.Unavailable -> {
                    // Weather, not a verdict. Keep the ground already covered.
                    store.putFrontier(WalkFrontier(startTxid, current, priorHops + step))
                    return null
                }
                Hop.DeadEnd -> {
                    store.clearFrontier(startTxid)
                    return null
                }
            }
        }

        store.putFrontier(WalkFrontier(startTxid, current, priorHops + maxHopsPerAttempt))
        return null
    }

    companion object {
        /**
         * Hops per attempt. This bounds WORK, not reach: an attempt that runs out records where
         * it got to and the next one continues, so an arbitrarily deep chain still resolves.
         *
         * The predecessor constant was 12, justified by "real chains rarely exceed 2-3" — an
         * assumption that fails precisely on an asset being used to test transfers, since every
         * send-and-receive round trip adds two hops.
         */
        const val DEFAULT_MAX_HOPS_PER_ATTEMPT = 50
    }
}

/**
 * Process-lifetime [ProvenanceStore]. Used where no database is wired (tests, and any
 * AssetManager built without one) so the walker behaves identically everywhere — the only
 * difference being that what it learns doesn't outlive the process.
 */
class InMemoryProvenanceStore : ProvenanceStore {
    private val assets = mutableMapOf<String, ResolvedAssetFacts>()
    private val frontiers = mutableMapOf<String, WalkFrontier>()

    override suspend fun assetFor(txid: String): ResolvedAssetFacts? = assets[txid]
    override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {
        txids.forEach { assets[it] = facts }
    }
    override suspend fun frontierFor(startTxid: String): WalkFrontier? = frontiers[startTxid]
    override suspend fun putFrontier(frontier: WalkFrontier) {
        frontiers[frontier.startTxid] = frontier
    }
    override suspend fun clearFrontier(startTxid: String) { frontiers.remove(startTxid) }
}
