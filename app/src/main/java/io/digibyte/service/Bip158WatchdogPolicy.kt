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
    scanStalledMs: Long,
    abandonmentPendingCycles: Int,
    scanFrozenThresholdMs: Long = CF_FROZEN_RECOVERY_MS,
): PostTimeoutAction = when {
    // PACED-CONVOY GATE (spec Part D). The re-anchor is destructive — its caller
    // deletes FilterHeaderStore AND CfScanLedgerStore — and under the convoy its
    // trigger condition ("headers caught up, cfheaders not advancing") is the DESIGNED
    // steady state at the window top, not a legacy filter-header deficit. Only a frozen
    // SCAN frontier warrants it, and never while the B2 valve owns the stall (bounded).
    hasReachedSynced && !reanchoredThisSession &&
        scanStalledMs >= scanFrozenThresholdMs &&
        !isConvoySuppressed(abandonmentPendingCycles) -> PostTimeoutAction.REANCHOR
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
    scanStalledMs: Long,
    abandonmentPendingCycles: Int,
    thresholdMs: Long = CF_FROZEN_RECOVERY_MS,
): Boolean =
    !alreadyRecovered && blockClimbing && cfNetMax > 0 && cfFrozenMs >= thresholdMs &&
        // PACED-CONVOY GATE (spec Part D fix I-2) — THE dangerous branch. During a
        // healthy convoy climb the single connected filter-capable peer can drop:
        // getCFChainTipHeight() then freezes while the buffered cfilters keep draining
        // and the B1 driver keeps the block tip climbing, i.e. EXACTLY
        // blockClimbing && cfTip-frozen. The recovery deletes FilterHeaderStore AND
        // CfScanLedgerStore and recreates the manager, throwing away every block of
        // convoy scan progress; it fires once per session but RE-fires each session, so
        // a deep restore can loop forever and never complete. A draining scan frontier
        // is the proof that the CF path is alive — only a frozen SCAN is a real wedge.
        scanStalledMs >= thresholdMs &&
        // ...and never on the valve's own stall: a gaveUp hole PINS the scan frontier
        // by construction, so scan-frozen alone would hand the valve's decision window
        // straight to the branch that deletes the ledger it is deciding on. Bounded.
        !isConvoySuppressed(abandonmentPendingCycles)

/**
 * How long the compact-filter tip may stay frozen AFTER the ordinary one-time
 * re-anchor has already fired and been given its grace window — with headers
 * caught up to the network tip and filter peers connected — before the watchdog
 * treats the persisted filter chain as CORRUPT (poisoned by a prior build) and
 * performs the stronger clean-slate heal.
 */
internal const val CF_CORRUPT_HEAL_MS = 90_000L

/**
 * Minimum spacing between successive clean-slate heals — deliberately LONGER than
 * [CF_CORRUPT_HEAL_MS]. A heal calls forceReconnect(), which tears down every peer
 * socket, so getCFChainTipHeight() reads 0 until the recreated manager reconnects
 * AND lands its first cfheaders batch. During that reconnect window cfTip == 0 ==
 * the reset cfNetMax, so the "no net progress" frozen timer keeps growing even
 * though the heal's own re-fetch is legitimately still coming up. Without a cooldown
 * wider than that window, the next poll would re-heal — tearing down the very
 * reconnect it's waiting on and burning the whole heal budget in minutes (an
 * adversarial-review finding). 4 minutes is comfortably longer than a filter peer
 * reconnect + first-batch round-trip even over Tor, so a batch lands (cfTip jumps
 * far above 0, resetting the timer) before another heal can fire; if none lands in
 * 4 min the wallet is genuinely wedged and rotating to the next peer is warranted.
 */
internal const val CF_CORRUPT_HEAL_COOLDOWN_MS = 240_000L

/**
 * Max clean-slate corrupt-chain heals per session. Each wipes ALL persisted
 * filter state and re-fetches from a freshly-pinned canon peer, forcing a full
 * re-scan — so this is bounded: a wallet that genuinely can't reach any healthy
 * filter peer must not loop full re-syncs forever. After [MAX_CF_CORRUPT_HEALS]
 * the watchdog gives up gracefully (stays on filters, no bloom).
 */
internal const val MAX_CF_CORRUPT_HEALS = 3

/**
 * Should the watchdog perform the clean-slate corrupt-filter-chain heal this poll?
 *
 * The poisoned-chain signature: the block-header chain is at the network tip
 * ([blocksCaughtUp]) and filter peers are connected ([peerCount] > 0), yet the
 * compact-filter tip has made no NET forward progress for [cfFrozenMs] >=
 * [thresholdMs] AND the ordinary one-time re-anchor has ALREADY fired this session
 * ([reanchored]) and been given its full grace window ([msSinceReanchor] >=
 * [REANCHOR_GRACE_MS]) without freeing it. That combination cannot be "slow sync"
 * (cfTip would still creep, resetting the frozen timer) nor "headers still
 * importing" (blocksCaughtUp rules it out) nor "re-anchor still rebuilding"
 * (grace elapsed) — it means the wallet's OWN persisted filter chain can't extend,
 * i.e. a prior build wrote corrupt chain data. Bounded to [maxHeals] attempts
 * ([healsSoFar]) so an unreachable-fleet wallet can't re-sync-loop forever.
 *
 * Distinct from [shouldRecoverFrozenCf], which fires only WHILE headers import
 * ([blockClimbing]) and BEFORE any re-anchor. This fires AFTER, with headers done
 * — exactly the gap that left post-bad-version wallets wedged at a fixed cfheaders
 * height with no remaining recovery (the one-time re-anchor having already given
 * up for the session).
 */
internal fun shouldHealCorruptFilterChain(
    blocksCaughtUp: Boolean,
    peerCount: Int,
    cfFrozenMs: Long,
    reanchored: Boolean,
    msSinceReanchor: Long,
    healsSoFar: Int,
    scanStalledMs: Long,
    abandonmentPendingCycles: Int,
    thresholdMs: Long = CF_CORRUPT_HEAL_MS,
    maxHeals: Int = MAX_CF_CORRUPT_HEALS,
): Boolean =
    blocksCaughtUp && peerCount > 0 && reanchored &&
        msSinceReanchor >= REANCHOR_GRACE_MS &&
        healsSoFar < maxHeals && cfFrozenMs >= thresholdMs &&
        // PACED-CONVOY GATE (spec Part D): same reasoning as [shouldRecoverFrozenCf],
        // one branch later and with a bigger blast radius (this wipes ALL persisted
        // filter state AND force-reconnects). A cfTip frozen at the convoy window top
        // while the scan drains is the designed steady state, not a poisoned chain.
        scanStalledMs >= thresholdMs &&
        !isConvoySuppressed(abandonmentPendingCycles)

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
 * the wallet is armed per [isTipStallArmed] (the CF SCAN frontier frozen for
 * [scanStalledMs] >= [thresholdMs], plus the residual dead-branch conjunct) while
 * peers are connected and the B2 valve is not mid-decision. Deliberately INDEPENDENT
 * of hasReachedSynced / gap / blocksCaughtUp / blockClimbing — those are exactly the
 * flags that misclassify a frozen wallet as "healthy" and blind every existing
 * recovery path. The recovery it drives is a full-locator getheaders — benign as a
 * 0-header no-op on a healthy at-tip wallet, but UNGATED with respect to the convoy
 * window, which is why it must not fire on the convoy's designed pacing.
 */
internal fun shouldRerequestHeadersOnStall(
    peerCount: Int,
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    abandonmentPendingCycles: Int,
    thresholdMs: Long = TIP_STALL_TIMEOUT_MS,
): Boolean = peerCount > 0 && !isConvoySuppressed(abandonmentPendingCycles) &&
    isTipStallArmed(scanStalledMs, blockTipStalledMs, convoyWindowFull, thresholdMs)

/**
 * Tier 2 — escalate to a full manager recreate when a Tier-1 header re-request
 * already fired this stall ([tier1Fired]) and the wallet is STILL armed a full window
 * later ([scanStalledMs] >= 2×[thresholdMs]). Covers the dead-branch case where the
 * connected peers can't (or won't) serve the real chain and only a fresh handshake
 * cohort will. The caller throttles the actual recreate. The most expensive action in
 * the whole watchdog — a manager recreate mid-descent throws away in-flight convoy
 * state — so it carries the same convoy arming + bounded suppression as Tier 1.
 */
internal fun shouldForceReconnectOnStall(
    peerCount: Int,
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    tier1Fired: Boolean,
    abandonmentPendingCycles: Int,
    thresholdMs: Long = TIP_STALL_TIMEOUT_MS,
): Boolean = peerCount > 0 && tier1Fired && !isConvoySuppressed(abandonmentPendingCycles) &&
    isTipStallArmed(scanStalledMs, blockTipStalledMs, convoyWindowFull, thresholdMs * 2)

/**
 * FAST tier — the sub-window nudge (re-request headers + pin a canon filter peer).
 * Extracted from [SyncService.startTipStallWatchdog] so the convoy re-key and the
 * abandonment suppression are unit-testable.
 */
internal fun shouldFastRecoverOnStall(
    peerCount: Int,
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    abandonmentPendingCycles: Int,
    thresholdMs: Long,
): Boolean = peerCount > 0 && !isConvoySuppressed(abandonmentPendingCycles) &&
    isTipStallArmed(scanStalledMs, blockTipStalledMs, convoyWindowFull, thresholdMs)

// ── paced-convoy fetch (spec Part C/D) — window + abandonment-suppression ──

/** Mirror of the native `CF_CONVOY_WINDOW` (BRPeerManager.h): the max the block-header
 *  / cfheader frontier may lead the CF SCAN frontier before the fetch gate suppresses
 *  the tip-racing continuations. */
internal const val CF_CONVOY_WINDOW = 10_000L

/** Mirror of the native `CF_CONVOY_REARM_MAX` (BRPeerManager.h): fresh retry cycles the
 *  B2 valve grants a gaveUp hole against a live CF-peer set before it may abandon it. */
internal const val CF_CONVOY_REARM_MAX = 2

/**
 * Upper bound on the abandonment cycle count for which the tip-stall watchdog stands
 * down. `BRPeerManagerHasPendingAbandonment` returns a CYCLE COUNT, not a boolean:
 * `N == rearmCycles + 1`, and the valve is entitled to decide at `N == CF_CONVOY_REARM_MAX + 1`
 * (the deciding cycle). See [isConvoySuppressed] for why the bound is mandatory.
 */
internal const val CONVOY_SUPPRESSION_MAX_CYCLES = CF_CONVOY_REARM_MAX + 1

/** The CF scan ledger reports `LowestNeededHeight == 0` when the peer manager doesn't
 *  exist and `1` on a freshly calloc'd (unarmed) ledger. Neither is a real scan
 *  frontier, so the convoy re-key must fall back to the legacy block-tip keying. */
internal fun isScanFrontierArmed(scanFrontier: Long): Boolean = scanFrontier > 1L

/**
 * Is the convoy's header window FULL (`W_hdr >= CF_CONVOY_WINDOW`)? When it is, the
 * block-header frontier is frozen BY DESIGN — the native gate suppresses the
 * tip-racing getheaders continuations — so a frozen block tip carries no information.
 *
 * Compares before subtracting: the scan frontier can legitimately sit ABOVE
 * `lastBlock->height` (an abandonment watermark past a not-yet-synced tip), and an
 * unsigned-style wrap there would read as "permanently full" (the same UNDERFLOW
 * GUARD the native gate carries).
 */
internal fun isConvoyWindowFull(
    blockHeaderFrontier: Long,
    scanFrontier: Long,
    window: Long = CF_CONVOY_WINDOW,
): Boolean = isScanFrontierArmed(scanFrontier) &&
    blockHeaderFrontier >= scanFrontier &&
    blockHeaderFrontier - scanFrontier >= window

/**
 * Should the tip-stall watchdog stand down because the B2 abandonment valve owns the
 * scan-frontier stall? [abandonmentPendingCycles] is the count from
 * `BRPeerManagerHasPendingAbandonment` (0 = nothing pending).
 *
 * **THE BOUND IS LOAD-BEARING — do NOT reduce this to `cycles > 0`.** The valve's
 * per-cycle `offersReachedLivePeer` latch is cleared by ANY disconnect of the peer
 * stamped on the hole, and a deciding cycle is 5 offers over ~7.5 min. On this
 * wallet's documented fleet — canon oracles at `maxconnections`, errno-101 blips,
 * ~8 peers rotating — EVERY cycle can be tainted, so the valve re-arms INDEFINITELY
 * and the accessor returns non-zero forever. A bare-boolean suppression would then be
 * PERMANENTLY active and the watchdog would stand down forever, in exactly the case
 * the backstop exists for. So suppress only while the valve is inside the cycles it
 * is actually entitled to (`N <= CF_CONVOY_REARM_MAX + 1`, i.e. through the deciding
 * cycle); above that the hole is re-arming without converging and MUST be re-exposed
 * to the watchdog's escalation.
 */
internal fun isConvoySuppressed(abandonmentPendingCycles: Int): Boolean =
    abandonmentPendingCycles in 1..CONVOY_SUPPRESSION_MAX_CYCLES

/**
 * Is the tip-stall watchdog armed this poll?
 *
 * PRIMARY SIGNAL — the CF SCAN frontier ([scanStalledMs], from
 * `getLowestNeededHeight()`). The paced convoy deliberately freezes the block-header
 * frontier at `scanFrontier + CF_CONVOY_WINDOW`, so `getLastBlockHeight()` is the
 * PACING signal now, not a liveness signal; a watchdog keyed on it reads intentional
 * pacing as a stall. Scan progress is what actually proves the wallet is advancing,
 * and it degrades gracefully to the original behaviour: an idle wallet at the network
 * tip with live-but-silent peers scans nothing either, so the frontier freezes with
 * the tip and the "no confirms for days" wedge is still caught.
 *
 * RESIDUAL DEAD-BRANCH CONJUNCT (spec Part D fix M-2) — while the convoy window is
 * FULL the header frontier is pinned BY THE GATE, so escalation additionally requires
 * the raw block tip to be frozen too. If the tip is still re-kicking (the B1 driver
 * just landed another 2000-header batch) the header layer is alive and a scan stall
 * belongs to the filter layer — the BIP158 watchdog and the B2 valve own it, and tier
 * 1's UNGATED getheaders (which blows straight past the window) cannot help. When the
 * window is OPEN there is no gating to explain a frozen tip, so the scan signal stands
 * alone.
 *
 * DELIBERATE DEVIATION FROM THE SPEC SKETCH, stated: M-2 reads as "ALSO arm when the
 * block tip is frozen and the window is full", i.e. a DISJUNCT that would arm while
 * the scan still climbs, so a dead branch pinned at the window top is caught before
 * the scan climbs the whole window to reach it. That input state is indistinguishable
 * from a healthy gated descent (block tip frozen + window full is the convoy's steady
 * state between header batches — the discriminator is only whether the tip eventually
 * re-kicks), so as a disjunct it false-fires tier 1 on any descent whose 2000-block
 * gated interval exceeds the threshold, and it directly contradicts the mandated
 * behaviour "tier 1 must NOT fire when the header tip is frozen at scanFrontier + W
 * while the scan advances". The conjunctive form keeps the dead-branch case (both
 * clocks stop, so it still fires) and drops only the early-catch; the dead branch is
 * still caught, just when the scan reaches it.
 */
internal fun isTipStallArmed(
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    thresholdMs: Long,
): Boolean = scanStalledMs >= thresholdMs &&
    (!convoyWindowFull || blockTipStalledMs >= thresholdMs)
