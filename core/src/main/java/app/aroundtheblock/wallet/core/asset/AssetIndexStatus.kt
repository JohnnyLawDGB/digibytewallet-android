package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.asset.send.StackLookup

/**
 * Whether the DigiAsset index is, by its own latest answer, too far behind to say what the
 * wallet's newer outputs hold. A state for the assets screen to show beside the holdings it
 * already has: those stay exactly as they are (a row that counts was settled for good), and only
 * an output still waiting for the index is affected.
 */
sealed interface AssetIndexStatus {
    /** Nothing says the index is behind. Also the state before anything was asked. */
    data object Current : AssetIndexStatus

    /** The index's latest answer was "syncing"; [blocksBehind] is its own figure, when it gave one. */
    data class CatchingUp(val blocksBehind: Long?) : AssetIndexStatus
}

/** How one more indexer answer changes the [AssetIndexStatus]. Pure. */
object AssetIndexStatusRule {

    /**
     * Below this many blocks the index is at most a few minutes behind (a block is about 15 s) and
     * the next periodic check settles the output, so no note is shown: an output a block or two
     * newer than the index's tip gets the same 503 on every ordinary receipt. The wallet's own
     * choice; the server answers "syncing" for the whole index only when it is much further behind.
     */
    const val NOTE_MIN_BLOCKS_BEHIND = 20L

    fun next(current: AssetIndexStatus, lookup: StackLookup): AssetIndexStatus = when (lookup) {
        is StackLookup.NotYet ->
            if (lookup.indexSyncing && (lookup.blocksBehind == null || lookup.blocksBehind >= NOTE_MIN_BLOCKS_BEHIND)) {
                AssetIndexStatus.CatchingUp(lookup.blocksBehind)
            } else {
                AssetIndexStatus.Current
            }
        // The index answered about an output: it is not (or no longer) behind.
        is StackLookup.Found, StackLookup.NotUnspent -> AssetIndexStatus.Current
        // No answer about the index at all (offline, a refused request): what was last known stands.
        StackLookup.Unavailable, StackLookup.Refused -> current
    }
}
