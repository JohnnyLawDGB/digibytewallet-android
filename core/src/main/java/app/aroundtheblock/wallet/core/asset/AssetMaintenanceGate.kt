package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.CF_BEHIND_THRESHOLD

/**
 * True only when it is safe to run the native-positive-removal prune:
 *  - syncedThisSession: an onSyncComplete was observed IN THIS PROCESS (NOT the
 *    persisted has_synced flag, which is true before this session verifies the
 *    tx set — a sticky flag would arm the prune against an unrescanned wallet);
 *  - a peer is connected and sync progress is at tip, so native's tx set is
 *    current rather than mid-rebuild;
 *  - the compact-filter SCAN frontier ([scanFrontier], `getLowestNeededHeight`: the lowest height
 *    the scan still needs) is within [CF_BEHIND_THRESHOLD] of the header tip ([headerTip]), the
 *    same rule the sync stage uses. Header progress and `syncedThisSession` can both read "done"
 *    while a rescan from a floor is still far down the chain (a completion compared against a
 *    lower estimate, or reported when every peer dropped); native does not hold the history a
 *    prune judges rows against until the scan itself arrives. An unknown frontier or tip (0)
 *    keeps the gate closed.
 */
fun assetPruneGateOpen(
    syncedThisSession: Boolean,
    peerCount: Int,
    progress: Float,
    walletLoaded: Boolean,
    scanFrontier: Long,
    headerTip: Long,
): Boolean = syncedThisSession && peerCount > 0 && progress >= 1.0f && walletLoaded &&
    scanFrontier > 0L && headerTip > 0L && (headerTip - scanFrontier) <= CF_BEHIND_THRESHOLD
