package io.digibyte.core.asset

/**
 * True only when it is safe to run the native-positive-removal prune:
 *  - syncedThisSession: an onSyncComplete was observed IN THIS PROCESS (NOT the
 *    persisted has_synced flag, which is true before this session verifies the
 *    tx set — a sticky flag would arm the prune against an unrescanned wallet);
 *  - a peer is connected and sync progress is at tip, so native's tx set is
 *    current rather than mid-rebuild.
 */
fun assetPruneGateOpen(
    syncedThisSession: Boolean,
    peerCount: Int,
    progress: Float,
    walletLoaded: Boolean,
): Boolean = syncedThisSession && peerCount > 0 && progress >= 1.0f && walletLoaded
