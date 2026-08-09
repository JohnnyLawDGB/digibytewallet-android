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
    suppressionMaxMs: Long = CONVOY_SUPPRESSION_MAX_MS_FALLBACK,
): PostTimeoutAction = when {
    // PACED-CONVOY GATE (spec Part D). The re-anchor is destructive — its caller
    // deletes FilterHeaderStore AND CfScanLedgerStore — and under the convoy its
    // trigger condition ("headers caught up, cfheaders not advancing") is the DESIGNED
    // steady state at the window top, not a legacy filter-header deficit. Only a frozen
    // SCAN frontier warrants it, and never while the B2 valve is demonstrably still
    // working the stall — see [isConvoySuppressed], which releases once the frontier
    // has been pinned for a full valve budget with nothing to show for it.
    hasReachedSynced && !reanchoredThisSession &&
        scanStalledMs >= scanFrozenThresholdMs &&
        !isConvoySuppressed(abandonmentPendingCycles, scanStalledMs, suppressionMaxMs) ->
        PostTimeoutAction.REANCHOR
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
    suppressionMaxMs: Long = CONVOY_SUPPRESSION_MAX_MS_FALLBACK,
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
        // straight to the branch that deletes the ledger it is deciding on. BOUNDED by
        // the same frontier clock — once the valve has had its whole budget and the
        // frontier has still not moved, the stall is no longer "work in progress".
        !isConvoySuppressed(abandonmentPendingCycles, scanStalledMs, suppressionMaxMs)

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
    suppressionMaxMs: Long = CONVOY_SUPPRESSION_MAX_MS_FALLBACK,
): Boolean =
    blocksCaughtUp && peerCount > 0 && reanchored &&
        msSinceReanchor >= REANCHOR_GRACE_MS &&
        healsSoFar < maxHeals && cfFrozenMs >= thresholdMs &&
        // PACED-CONVOY GATE (spec Part D): same reasoning as [shouldRecoverFrozenCf],
        // one branch later and with a bigger blast radius (this wipes ALL persisted
        // filter state AND force-reconnects). A cfTip frozen at the convoy window top
        // while the scan drains is the designed steady state, not a poisoned chain.
        scanStalledMs >= thresholdMs &&
        // The wedge this branch EXISTS for (a corrupt persisted cfheader chain fails
        // BRCompactFilterChainVerifyFilter on every honest cfilter, which leaves every
        // height OUTSTANDING by design) is also a state where native reports an
        // unresolved hole — so the suppression has to be bounded or the heal can never
        // reach its own trigger. See [isConvoySuppressed].
        !isConvoySuppressed(abandonmentPendingCycles, scanStalledMs, suppressionMaxMs)

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
 * peers are connected. Deliberately INDEPENDENT of hasReachedSynced / gap /
 * blocksCaughtUp / blockClimbing — those are exactly the flags that misclassify a
 * frozen wallet as "healthy" and blind every existing recovery path.
 *
 * NOT SUPPRESSIBLE (fix wave C2). The recovery it drives is a full-locator
 * getheaders: it deletes nothing, frees nothing, re-inits nothing, and is a benign
 * 0-header no-op on a healthy at-tip wallet. There is therefore no ledger state for
 * it to destroy and no reason to stand it down while a height is unresolved — and
 * gating it on that disabled it permanently in the roaming / dead-branch wedges it
 * exists to cure, since an unresolved height is exactly what those wedges produce.
 * The convoy pacing is still respected through [isTipStallArmed]'s tip conjunct.
 */
internal fun shouldRerequestHeadersOnStall(
    peerCount: Int,
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    thresholdMs: Long = TIP_STALL_TIMEOUT_MS,
): Boolean = peerCount > 0 &&
    isTipStallArmed(scanStalledMs, blockTipStalledMs, convoyWindowFull, thresholdMs)

/**
 * Tier 2 — escalate to a full manager recreate when a Tier-1 header re-request
 * already fired this stall ([tier1Fired]) and the wallet is STILL armed a full window
 * later ([scanStalledMs] >= 2×[thresholdMs]). Covers the dead-branch case where the
 * connected peers can't (or won't) serve the real chain and only a fresh handshake
 * cohort will. The caller throttles the actual recreate.
 *
 * DESTRUCTIVE, so it carries the liveness gate (fix wave C2). `forceReconnect()` flags
 * the manager for a clean recreate and `startSync()` frees and rebuilds it; the fresh
 * manager's `_applyPendingBip158State` re-arms auto-fetch at the REMEMBERED start, and
 * `BRPeerManagerEnableAutoCompactFilterFetch` re-Inits the CF scan ledger there — so an
 * in-session tier 2 discards the descent's in-flight scan progress even though it
 * deletes no persisted store. That is the F2 wedge if it fires mid-descent, and a
 * necessary escape if the descent is genuinely dead, which is exactly the distinction
 * [isConvoySuppressed]'s frontier clock draws.
 */
internal fun shouldForceReconnectOnStall(
    peerCount: Int,
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    tier1Fired: Boolean,
    abandonmentPendingCycles: Int,
    thresholdMs: Long = TIP_STALL_TIMEOUT_MS,
    suppressionMaxMs: Long = CONVOY_SUPPRESSION_MAX_MS_FALLBACK,
): Boolean = peerCount > 0 && tier1Fired &&
    !isConvoySuppressed(abandonmentPendingCycles, scanStalledMs, suppressionMaxMs) &&
    isTipStallArmed(scanStalledMs, blockTipStalledMs, convoyWindowFull, thresholdMs * 2)

/**
 * FAST tier — the sub-window nudge (re-request headers + pin a canon filter peer).
 * Extracted from [SyncService.startTipStallWatchdog] so the convoy re-key is
 * unit-testable.
 *
 * NOT SUPPRESSIBLE (fix wave C2), and this is the load-bearing half of that decision.
 * This is the ONLY code path that pins a validated canon CF peer, i.e. the only cure
 * for wedge class (a): the wallet holds peers but not a filter-capable one, so it
 * roams the junk pool. That wedge is *created* by a CF peer serving one batch and
 * dropping — which leaves the served range's holes unresolved — so suppressing this
 * tier on "a height is unresolved" disabled the cure in precisely the state that
 * produces it. Like tier 1 it deletes nothing and re-inits nothing.
 */
internal fun shouldFastRecoverOnStall(
    peerCount: Int,
    scanStalledMs: Long,
    blockTipStalledMs: Long,
    convoyWindowFull: Boolean,
    thresholdMs: Long,
): Boolean = peerCount > 0 &&
    isTipStallArmed(scanStalledMs, blockTipStalledMs, convoyWindowFull, thresholdMs)

// ── paced-convoy fetch (spec Part C/D) — window + abandonment-suppression ──

/**
 * ⚠️ THESE TWO ARE FALLBACKS, NOT THE SOURCE OF TRUTH (fix-wave I-3). The live
 * values come from native at runtime — `NativeBridge.getConvoyWindow()` and
 * `NativeBridge.getConvoyRearmMax()`, read once per sync start in [SyncService] and
 * threaded through the `window` / `suppressionMaxCycles` parameters below. They were
 * previously HAND-MIRRORED here from `BRPeerManager.h` with no build-time check,
 * which is a drift trap the spec actively walks into: its own tuning signal tells the
 * operator to RAISE `CF_CONVOY_REARM_MAX` in the C header when an abandoned height
 * later reconciles. Doing that would have left this file releasing its suppression at
 * 4 while the valve was still legitimately working cycles 4..N — the tip-stall
 * watchdog escalating INTO a productive valve, up to a tier-2 manager recreate. A
 * `CF_CONVOY_WINDOW` lowered natively is worse: Kotlin would read "window not full",
 * drop the tip-frozen conjunct and arm tier 1/tier 2 during a HEALTHY paced descent.
 *
 * These constants are therefore used ONLY when the native read is unavailable (a
 * stale/unloadable `.so`), where holding the last-known values is the fail-open
 * direction — the same call Task 8 made for the pending-abandonment accessor. Keep
 * them equal to the header's values on a best-effort basis; nothing depends on it.
 */
internal const val CF_CONVOY_WINDOW_FALLBACK = 10_000L

/** @see CF_CONVOY_WINDOW_FALLBACK — fallback only; the live value is native. */
internal const val CF_CONVOY_REARM_MAX_FALLBACK = 2

/**
 * One full native retry cycle for a single CF scan hole, in wall clock:
 * `CF_REREQ_MAX_ATTEMPTS` (5) attempts on the `CF_REREQ_BACKOFF` schedule
 * 30 + 60 + 120 + 120 + 120 s = 450 s (`BRCFScanLedger.h`). Mirrored here only to SIZE
 * the suppression ceiling below — nothing about correctness depends on it matching to
 * the second, and the ceiling carries a full extra cycle of slack.
 */
internal const val CF_VALVE_RETRY_CYCLE_MS = 450_000L

/**
 * How long a pinned CF scan frontier may keep destructive recovery stood down.
 *
 * DERIVED, not picked: the native B2 valve's whole budget for one hole is the initial
 * attempt cycle plus [rearmMax] re-arm cycles — `(rearmMax + 1) × CF_VALVE_RETRY_CYCLE_MS`
 * (22.5 min at the shipped `CF_CONVOY_REARM_MAX = 2`) — after which it either abandons
 * the hole (raising `abandonedBelow`, releasing the frontier and surfacing the band) or
 * proves it cannot decide (no CF peer connected, or a peer flap tainted the deciding
 * cycle). One EXTRA cycle of slack absorbs KeepAlive tick latency and exactly one such
 * tainted cycle. Past that, the frontier has not moved for the valve's entire budget
 * plus a spare cycle with a hole outstanding the whole time: native retry has provably
 * failed to release it, and standing the app-side recovery down any longer is the
 * permanent wedge, not caution.
 *
 * Deriving it from the live native `rearmMax` also closes the drift trap the spec walks
 * into: its own tuning signal tells the operator to RAISE `CF_CONVOY_REARM_MAX`, which
 * lengthens the valve's budget — and this ceiling grows with it automatically instead of
 * releasing into a still-productive valve.
 */
internal fun convoySuppressionMaxMs(rearmMax: Int): Long =
    CF_VALVE_RETRY_CYCLE_MS * (rearmMax + 2L)

/** Fallback ceiling (30 min), used only when the native `getConvoyRearmMax()` read fails. */
internal val CONVOY_SUPPRESSION_MAX_MS_FALLBACK =
    convoySuppressionMaxMs(CF_CONVOY_REARM_MAX_FALLBACK)

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
    window: Long = CF_CONVOY_WINDOW_FALLBACK,
): Boolean = isScanFrontierArmed(scanFrontier) &&
    blockHeaderFrontier >= scanFrontier &&
    blockHeaderFrontier - scanFrontier >= window

/**
 * Should DESTRUCTIVE watchdog recovery stand down this poll?
 *
 * Two conditions, and BOTH must hold:
 *  1. native reports that its retry/abandon machinery OWNS the frontier-pinning hole
 *     ([abandonmentPendingCycles] > 0 — `BRPeerManagerHasPendingAbandonment`, which
 *     counts valve-granted re-arm cycles and deliberately reads 0 for an ordinary
 *     first-cycle outstanding request); and
 *  2. the convoy is still demonstrably ALIVE: the CF scan frontier has moved within
 *     the last [suppressionMaxMs] ([frontierPinnedMs] is the caller's `scanStalledMs`,
 *     i.e. time since the last frontier CHANGE — and a re-init counts as a change, see
 *     [stepScanFrontier], so a legitimate post-recovery re-climb re-arms the protection
 *     instead of being read as more of the same stall).
 *
 * WHY IT MUST BE BOUNDED (fix wave C2). The unbounded form (`pending > 0`) was keyed on
 * a signal that is 1 whenever a hole exists, and every recovery tier conjoined it — so
 * every tier was disabled in exactly the states it exists to cure. Worst case, the
 * corrupt-cfheader wedge: a diverged persisted chain fails verification for every honest
 * cfilter, each is left outstanding BY DESIGN, native reports "pending" forever, and the
 * clean-slate heal built for that precise signature can never fire.
 *
 * WHY IT IS STILL SUPPRESSED AT ALL. The intent behind the unbounded form is real: a
 * destructive tier firing on top of a working retry deletes the ledger the retry is
 * deciding on and re-inits the descent at the birth floor. So while the frontier is
 * moving — or while native is inside its budget for the hole that is pinning it — the
 * destructive tiers stay out of the way. The bound converts "never" into "not while it
 * is working". Non-destructive tiers do not consult this at all.
 */
internal fun isConvoySuppressed(
    abandonmentPendingCycles: Int,
    frontierPinnedMs: Long,
    suppressionMaxMs: Long = CONVOY_SUPPRESSION_MAX_MS_FALLBACK,
): Boolean = abandonmentPendingCycles > 0 && frontierPinnedMs < suppressionMaxMs

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

// ── CF scan-frontier progress tracking (the watchdogs' liveness clock) ──

/** One poll of a CF scan-frontier tracker: the frontier to carry forward, the
 *  wall-clock of its last CHANGE, and whether this poll observed one. */
internal data class FrontierProgress(
    val frontier: Long,
    val lastChangeMs: Long,
    val changed: Boolean,
)

/**
 * Step a CF scan-frontier tracker one poll: MOTION restarts the stall clock, and a
 * REGRESSION is motion.
 *
 * A forward-only tracker (what both watchdogs did before this fix) has a blind spot
 * that inverts the watchdog into an attacker. Every destructive recovery in
 * [SyncService] — the frozen-CF recovery, the corrupt-chain heal, the post-timeout
 * re-anchor — deletes `CfScanLedgerStore` and recreates the manager, so native
 * re-`Init`s the CF scan ledger at the floor and `getLowestNeededHeight()` drops far
 * BELOW the last observed value. A deep reorg re-inits it the same way with no app
 * branch involved at all (`BRPeerManager.c:4035`), which no post-branch reset could
 * ever cover. The frontier is then below the high-water mark for the whole hours-long
 * clean re-climb, so a forward-only clock never restarts: the stall grows without
 * bound and the tier-1 latch can never clear, and the tip-stall watchdog spends the
 * rest of the session issuing ungated getheaders and recreating the peer manager once
 * per throttle window — harassing the very recovery that just ran, mid-descent.
 *
 * A regression is therefore adopted as a legitimate re-init. The one exception is an
 * UNARMED reading (`0` while the peer manager is absent — the window between
 * `forceReconnect` and `startSync` — or `1` on a freshly `calloc`'d ledger): those are
 * not frontiers at all, and adopting them would let a flapping read hold the stall
 * clock near zero and silently disable the backstop. They are dropped, so the tracker
 * keeps the last real frontier and its clock keeps running.
 */
internal fun stepScanFrontier(
    prevFrontier: Long,
    prevChangeMs: Long,
    frontierNow: Long,
    nowMs: Long,
): FrontierProgress = when {
    frontierNow == prevFrontier -> FrontierProgress(prevFrontier, prevChangeMs, false)
    frontierNow > prevFrontier -> FrontierProgress(frontierNow, nowMs, true)
    isScanFrontierArmed(frontierNow) -> FrontierProgress(frontierNow, nowMs, true)   // re-init
    else -> FrontierProgress(prevFrontier, prevChangeMs, false)                      // transient
}

/**
 * The tip-stall watchdog's liveness state, lifted out of the coroutine body so the
 * signal selection is unit-testable (`NativeBridge` is JNI and unmockable on the host
 * JVM, so the pure predicate is the only testable seam).
 */
internal data class TipStallState(
    /** Last observed CF scan frontier (`getLowestNeededHeight()`). */
    val lastScan: Long,
    /** Wall-clock of the last scan-frontier CHANGE (advance or re-init). */
    val lastScanChangeMs: Long,
    /** Last observed raw block-header tip (`getLastBlockHeight()`). */
    val lastTip: Long,
    /** Wall-clock of the last block-tip advance. */
    val lastTipAdvanceMs: Long,
    /** Is the scan frontier a usable signal this poll? */
    val scanArmed: Boolean,
    /** Has tier 1 already fired for the current stall? */
    val tier1Fired: Boolean,
    /** Did this poll observe progress on the AUTHORITATIVE signal? The caller also
     *  releases any pinned canon peer on it. */
    val progressed: Boolean,
)

/** Seed the tracker from the first readings taken at watchdog start. */
internal fun initialTipStallState(tip: Long, scan: Long, nowMs: Long): TipStallState =
    TipStallState(
        lastScan = scan,
        lastScanChangeMs = nowMs,
        lastTip = tip,
        lastTipAdvanceMs = nowMs,
        scanArmed = isScanFrontierArmed(scan),
        tier1Fired = false,
        progressed = false,
    )

/**
 * Advance the tip-stall tracker by one poll.
 *
 * AUTHORITATIVE SIGNAL: the CF scan frontier while it is armed, else the raw block
 * tip. Clearing the tier-1 latch / the canon pin follows whichever that is — clearing
 * on a block-tip advance while the scan is armed would let the convoy gate's periodic
 * 2000-header re-kick reset [TipStallState.tier1Fired] forever, so tier 2 (which
 * requires it) could never escalate a genuine scan wedge.
 */
internal fun TipStallState.step(tipNow: Long, scanNow: Long, nowMs: Long): TipStallState {
    val scanArmedNow = isScanFrontierArmed(scanNow)
    val tipAdvanced = tipNow > lastTip
    val scanStep = stepScanFrontier(lastScan, lastScanChangeMs, scanNow, nowMs)
    val progressedNow = if (scanArmedNow) scanStep.changed else tipAdvanced
    return TipStallState(
        lastScan = scanStep.frontier,
        lastScanChangeMs = scanStep.lastChangeMs,
        lastTip = if (tipAdvanced) tipNow else lastTip,
        lastTipAdvanceMs = if (tipAdvanced) nowMs else lastTipAdvanceMs,
        scanArmed = scanArmedNow,
        tier1Fired = if (progressedNow) false else tier1Fired,
        progressed = progressedNow,
    )
}

/**
 * How long the authoritative liveness signal has been stopped. Before the CF scan
 * ledger is armed (no peer manager => 0, freshly calloc'd ledger => 1) the scan
 * frontier is not a signal at all, so fall back to the legacy block-tip keying —
 * otherwise a constant 0/1 would read as "frozen forever" and escalate on a wallet
 * that is simply still coming up.
 */
internal fun TipStallState.scanStalledMs(nowMs: Long): Long =
    if (scanArmed) nowMs - lastScanChangeMs else nowMs - lastTipAdvanceMs

/** How long the RAW block-header tip has been frozen (the residual dead-branch
 *  conjunct in [isTipStallArmed]). */
internal fun TipStallState.blockTipStalledMs(nowMs: Long): Long = nowMs - lastTipAdvanceMs
