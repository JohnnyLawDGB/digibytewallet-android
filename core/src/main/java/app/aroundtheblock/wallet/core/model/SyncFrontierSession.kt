package app.aroundtheblock.wallet.core.model

/**
 * The per-process half of the sync frontier: whether THIS process has already derived
 * [SyncStage.Synced] (or the abandoned-band hold), which [deriveSyncFrontier] takes as
 * `reachedSyncedThisSession`. The core's `BRSyncFrontierSession.reachedSynced` is the same latch.
 *
 * One instance per process, held by `WalletManager`, and every screen that shows sync status
 * derives through [derive]. When each screen kept its own latch, a screen that did not pass it
 * (Network Info) left the [SYNC_TARGET_MAX_LEAD] rule off and could read Syncing while the main
 * card, whose latch was set, read Synced from the same inputs.
 *
 * The latch is read BEFORE the derivation and set AFTER it, so a derivation sees the previous
 * one's latch. It starts false and is never persisted: a cold start must not inherit it.
 */
class SyncFrontierSession {

    /** True once a derivation in this process has reached Synced or the band hold. */
    @Volatile
    var reachedSynced: Boolean = false
        private set

    /** [deriveSyncFrontier] with this session's latch, then latch from the result. */
    @Synchronized
    @Suppress("LongParameterList")
    fun derive(
        state: SyncState,
        peerCount: Int,
        currentHeight: Long,
        targetHeight: Long,
        externalTip: Long,
        cfTip: Long,
        scanFrontier: Long = 0L,
        abandonedBandUnrecovered: Boolean = false,
    ): SyncFrontier {
        val frontier = deriveSyncFrontier(
            state = state,
            peerCount = peerCount,
            currentHeight = currentHeight,
            targetHeight = targetHeight,
            externalTip = externalTip,
            cfTip = cfTip,
            scanFrontier = scanFrontier,
            abandonedBandUnrecovered = abandonedBandUnrecovered,
            reachedSyncedThisSession = reachedSynced,
        )
        if (frontier.stage == SyncStage.Synced || frontier.abandonedBandHolding) {
            reachedSynced = true
        }
        return frontier
    }

    /** Back to a fresh process's state. Called by a wallet wipe: the next wallet starts cold. */
    @Synchronized
    fun reset() {
        reachedSynced = false
    }
}
