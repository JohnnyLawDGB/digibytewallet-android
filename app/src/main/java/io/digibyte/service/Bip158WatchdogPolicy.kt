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
 * connected at re-anchor time) reads cfTip=0, sees "no progress," and degrades
 * to bloom — abandoning a re-anchor that was about to succeed. [REANCHOR_GRACE_MS]
 * keeps the watchdog on filters across that gap; if the chain still hasn't
 * rebuilt when the window expires, bloom is the safe floor.
 */
internal enum class PostTimeoutAction {
    /** cfTip is (probably) below the block floor and we haven't tried yet —
     *  attempt the one-time re-anchor at the floor. */
    REANCHOR,

    /** A re-anchor already fired and the freed chain is still rebuilding
     *  (cfTip reads 0). Keep polling — don't read the rebuild as a dead chain. */
    AWAIT_REANCHOR,

    /** The wallet holds DigiDollar and bloom (BIP37) never matches P2TR outputs,
     *  so degrading to it would silently miss DigiDollar receives (issue #19).
     *  Keep retrying compact filters and surface a degraded-detection warning
     *  instead of blinding the wallet to its own stablecoin. Headers are already
     *  caught up at this branch, so staying on filters remains viable. */
    STAY_ON_FILTERS_DD,

    /** No re-anchor is warranted (never synced) or the re-anchored chain never
     *  rebuilt within the grace window — degrade to bloom for the session. */
    FALLBACK_BLOOM,
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
 * @param hasDigiDollarBalance the wallet holds DigiDollar. Bloom is P2TR-blind,
 *   so once re-anchor is exhausted the choice is stay-on-filters (preserve DD
 *   detection) rather than the DD-blinding bloom degrade (issue #19). Re-anchor
 *   still takes precedence — it also stays on filters.
 */
internal fun decidePostTimeoutAction(
    hasReachedSynced: Boolean,
    reanchoredThisSession: Boolean,
    msSinceReanchor: Long,
    hasDigiDollarBalance: Boolean = false,
): PostTimeoutAction = when {
    hasReachedSynced && !reanchoredThisSession -> PostTimeoutAction.REANCHOR
    reanchoredThisSession && msSinceReanchor < REANCHOR_GRACE_MS -> PostTimeoutAction.AWAIT_REANCHOR
    hasDigiDollarBalance -> PostTimeoutAction.STAY_ON_FILTERS_DD
    else -> PostTimeoutAction.FALLBACK_BLOOM
}
