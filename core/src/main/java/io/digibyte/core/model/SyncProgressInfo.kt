package io.digibyte.core.model

/**
 * User-facing sync state composed from native bridge polls + WalletConfig.
 * Exists so the UI doesn't have to derive ETA, scan-window, and stage from
 * raw block heights every recomposition.
 *
 * `recoveryFromTimestamp` is the wallet's stored creation-time / recovery
 * date — used to surface the scan-window honesty banner ("transactions
 * older than X are not being recovered"). Null for wallets that were
 * created (not recovered) on this device, which have no scan-window
 * limitation.
 */
data class SyncProgressInfo(
    val stage: SyncStage,
    val currentBlock: Long,
    val targetBlock: Long,
    val progressFraction: Float,   // 0.0 – 1.0
    val matchCount: Int,           // transactions registered so far this session
    val runningBalanceSat: Long,   // wallet balance found so far
    val etaSeconds: Long?,         // null if rate isn't yet stable
    val peerCount: Int,
    val recoveryFromTimestamp: Long?, // unix seconds; null = no scan window
    /** Blocks between the displayed frontier and the tip. Derived from the SAME
     *  numerator as [progressFraction] and [etaSeconds] (the compact-filter SCAN
     *  frontier under the paced convoy), so "% · N remaining · ETA" is internally
     *  consistent rather than three different heights' opinions. */
    val blocksBehind: Long = 0L,
    /** The compact-filter band the B2 abandonment valve gave up on and that no
     *  recovery has covered yet. Non-null → the persistent recover-me banner is
     *  shown and "Synced" is withheld (paced-convoy fetch, spec Part E / GATE 3). */
    val abandonedBand: io.digibyte.core.sync.AbandonedBand? = null,
    /** True iff the un-recovered [abandonedBand] is the ONLY thing withholding
     *  "Synced" — the scan itself has finished. Lets the card say "history gap"
     *  instead of pretending a completed scan is still running. */
    val abandonedBandHolding: Boolean = false,
) {
    val isWorking: Boolean get() = stage == SyncStage.Connecting || stage == SyncStage.Syncing
}

enum class SyncStage {
    /** No peers yet, can't make progress. */
    Connecting,

    /** Peers connected, downloading headers and/or scanning blocks. */
    Syncing,

    /** Caught up to chain tip; both header sync and bloom scan complete. */
    Synced,

    /** Sync attempted and failed (network error, etc.) */
    Failed
}
