package io.digibyte.service

/**
 * Pure decision logic for the BIP158 watchdog's post-timeout branch, split out
 * of [SyncService.startBip158Watchdog] so it can be unit-tested without the
 * Android service, the JNI bridge, or real coroutine timing.
 *
 * Context: the wallet syncs on compact filters for privacy. A legacy wallet can
 * carry a persisted filter-header tip (cfTip) far below its lowest contiguously
 * downloaded block (the "block floor") — a deficit header-retention can't bridge
 * because the gap blocks were never re-downloaded. The watchdog's recovery is a
 * one-time re-anchor: discard the stuck chain and rebuild it from the floor (the
 * skipped gap was already bloom-scanned, which `has_synced` guarantees).
 *
 * The subtlety this models: re-anchoring FREES the compact-filter chain, so
 * `getCFChainTipHeight()` reads 0 until the first cfheaders response lazily
 * rebuilds it. The watchdog polls every 15s; without a grace window a single
 * poll landing in that rebuild gap (a slow/Tor round-trip, or no filter peer
 * connected at re-anchor time) reads cfTip=0, sees "no progress," and gives up
 * — abandoning a re-anchor that was about to succeed. [REANCHOR_GRACE_MS]
 * keeps the watchdog on filters across that gap; if the chain still hasn't
 * rebuilt when the window expires, staying on compact filters (and giving up
 * gracefully) is the safe floor — there is no bloom fallback.
 */
internal enum class PostTimeoutAction {
    /** cfTip is (probably) below the block floor and we haven't tried yet —
     *  attempt the one-time re-anchor at the floor. */
    REANCHOR,

    /** A re-anchor already fired and the freed chain is still rebuilding
     *  (cfTip reads 0). Keep polling — don't read the rebuild as a dead chain. */
    AWAIT_REANCHOR,

    /** No re-anchor is warranted (never synced) or the re-anchored chain never
     *  rebuilt within the grace window — stay on compact filters and give up
     *  gracefully for the session. Bloom (BIP37) is removed as a data path:
     *  this is never a fallback to bloom, only the terminal "nothing left to
     *  try" state. */
    STAY_ON_FILTERS,
}

/**
 * Grace window granted to a freshly re-anchored compact-filter chain to land its
 * first cfheaders append before the watchdog gives up and degrades to bloom.
 * ~4 watchdog polls (15s each): generous enough for a filter peer to (re)connect
 * and answer one getcfheaders even over Tor, bounded so a wallet that genuinely
 * can't reach a filter peer doesn't sit silently un-private for long.
 */
internal const val REANCHOR_GRACE_MS = 60_000L

/**
 * Decide what the watchdog should do once it has waited the full fallback
 * timeout with headers caught up but cfheaders not advancing.
 *
 * @param hasReachedSynced wallet has fully synced before (the guarantee that the
 *   gap the re-anchor skips was already scanned by bloom).
 * @param reanchoredThisSession a re-anchor has already been attempted this session.
 * @param msSinceReanchor wall-clock since that re-anchor (ignored unless
 *   [reanchoredThisSession]).
 */
internal fun decidePostTimeoutAction(
    hasReachedSynced: Boolean,
    reanchoredThisSession: Boolean,
    msSinceReanchor: Long,
): PostTimeoutAction = when {
    hasReachedSynced && !reanchoredThisSession -> PostTimeoutAction.REANCHOR
    reanchoredThisSession && msSinceReanchor < REANCHOR_GRACE_MS -> PostTimeoutAction.AWAIT_REANCHOR
    else -> PostTimeoutAction.STAY_ON_FILTERS
}

/**
 * Largest block-height gap between the block tip and the compact-filter tip
 * (cfTip) that still counts as "filters keeping pace."
 */
internal const val HEALTHY_CF_GAP_BLOCKS = 100L

/**
 * Is compact-filter sync healthy this poll? Healthy = cfTip is within
 * [HEALTHY_CF_GAP_BLOCKS] of the block tip AND either it advanced this session
 * (actively riding the chain) OR the block chain has caught up to the network tip.
 *
 * The `blocksCaughtUp` disjunct fixes a false "Privacy degraded for this session"
 * banner: a wallet already fully filter-synced at launch never advances cfTip —
 * there is nothing new to fetch — so an advance-only check mislabeled it "stuck"
 * and degraded a genuinely-synced wallet to bloom after the timeout. When cfTip
 * is within the gap of a block chain that IS at the network tip, the filter chain
 * is effectively complete: that is synced, not stuck. A filter chain that is truly
 * behind (gap > [HEALTHY_CF_GAP_BLOCKS]) or a block chain not yet at the tip
 * ("stuck at restore below the tip", blocksCaughtUp=false) is still, correctly,
 * not healthy and falls through to the recovery/degrade path.
 */
internal fun isFilterSyncHealthy(
    gap: Long,
    cfAdvancedSinceStart: Boolean,
    blocksCaughtUp: Boolean,
): Boolean = gap <= HEALTHY_CF_GAP_BLOCKS && (cfAdvancedSinceStart || blocksCaughtUp)
