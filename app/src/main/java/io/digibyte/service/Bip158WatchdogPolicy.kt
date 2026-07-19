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

/**
 * How long the compact-filter tip may make no NET forward progress — while the
 * block-header chain IS still climbing — before the watchdog treats the filter
 * chain as WEDGED and forces a one-time recovery. A healthy cfheaders sync rides
 * just behind the header chain and makes net progress every few seconds during a
 * rescan; a continuity re-anchor loop that never converges makes none. Wide
 * enough that a slow-but-progressing filter sync (Tor, flaky fleet) is never
 * misread as wedged.
 */
internal const val CF_FROZEN_RECOVERY_MS = 120_000L

/**
 * Should the watchdog force a one-time CF-wedge recovery this poll?
 *
 * True when the compact-filter tip made SOME progress (session running-max
 * [cfNetMax] > 0) then stopped advancing for [cfFrozenMs] >= [thresholdMs] while
 * the block-header chain is still climbing ([blockClimbing]) — i.e. cfheaders
 * SHOULD be able to ride the header chain but is stuck (the continuity re-anchor
 * loop) — and we haven't already recovered this session ([alreadyRecovered]).
 *
 * This is deliberately INDEPENDENT of blocksCaughtUp: the wedge happens WHILE
 * headers import, so the watchdog's blocksCaughtUp short-circuit is structurally
 * blind to it. [cfNetMax] must be the session running-max (not the current cfTip)
 * so the native chain oscillating 0↔N on each re-anchor cannot reset the frozen
 * timer. Requiring cfNetMax > 0 avoids firing during the normal pre-CF phase
 * where headers haven't yet climbed above the filter frontier.
 */
internal fun shouldRecoverFrozenCf(
    blockClimbing: Boolean,
    cfFrozenMs: Long,
    cfNetMax: Int,
    alreadyRecovered: Boolean,
    thresholdMs: Long = CF_FROZEN_RECOVERY_MS,
): Boolean =
    !alreadyRecovered && blockClimbing && cfNetMax > 0 && cfFrozenMs >= thresholdMs

/**
 * How long the BLOCK-header tip may make no forward progress — while peers are
 * connected — before the watchdog proactively re-requests headers. Every native
 * getheaders sender is reactive (sync-start, relayed inv/orphan, forward-only
 * continuation); once the wallet idles at a stale estimatedHeight, a tip with
 * live-but-silent peers freezes forever and stops confirming txs. 20 min ≈ 80
 * missed DGB blocks (~15s target), so a healthy wallet — which advances every few
 * seconds and resets the timer — never reaches it.
 */
internal const val TIP_STALL_TIMEOUT_MS = 20 * 60 * 1000L

/**
 * Tier 1 — should the watchdog proactively re-request headers this poll? True when
 * the block-header tip has been frozen for [tipStalledMs] >= [thresholdMs] while
 * peers are connected. Deliberately INDEPENDENT of hasReachedSynced / gap /
 * blocksCaughtUp / blockClimbing — those are exactly the flags that misclassify a
 * frozen tip as "healthy" and blind every existing recovery path. Only inputs:
 * peers connected + wall-clock since the last tip advance. The recovery it drives
 * (a full-locator getheaders) is a benign 0-header no-op on a healthy at-tip wallet.
 */
internal fun shouldRerequestHeadersOnStall(
    peerCount: Int,
    tipStalledMs: Long,
    thresholdMs: Long = TIP_STALL_TIMEOUT_MS,
): Boolean = peerCount > 0 && tipStalledMs >= thresholdMs

/**
 * Tier 2 — escalate to a full manager recreate ([forceReconnect]) when a Tier-1
 * header re-request already fired this stall ([tier1Fired]) and the tip is STILL
 * frozen a full window later ([tipStalledMs] >= 2×[thresholdMs]). Covers the
 * dead-branch case where the connected peers can't (or won't) serve the real chain
 * and only a fresh handshake cohort will. The caller throttles the actual recreate.
 */
internal fun shouldForceReconnectOnStall(
    peerCount: Int,
    tipStalledMs: Long,
    tier1Fired: Boolean,
    thresholdMs: Long = TIP_STALL_TIMEOUT_MS,
): Boolean = peerCount > 0 && tier1Fired && tipStalledMs >= thresholdMs * 2
