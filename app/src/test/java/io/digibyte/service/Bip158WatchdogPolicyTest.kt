package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [decidePostTimeoutAction] — the BIP158 watchdog's
 * post-timeout branch that chooses between re-anchoring the filter chain,
 * waiting for a just-re-anchored chain to rebuild, or staying on compact
 * filters and giving up gracefully for the session (no bloom fallback).
 *
 * Regression guard for the deep-deficit recovery bug: a re-anchor frees the
 * compact-filter chain, so getCFChainTipHeight() reads 0 until the first
 * cfheaders response lazily rebuilds it. The old one-shot logic gave that
 * rebuild a single 15s poll before giving up, so a slow/Tor round-trip or a
 * momentarily-absent filter peer abandoned a re-anchor that would have
 * succeeded. AWAIT_REANCHOR within REANCHOR_GRACE_MS is the fix.
 */
class Bip158WatchdogPolicyTest {

    /** "The CF scan frontier has not moved in a long time" — the convoy-era liveness
     *  signal every recovery branch is now keyed on. */
    private val SCAN_FROZEN = 10 * 60 * 1000L

    /** "The CF scan frontier just advanced" — the convoy is descending healthily. */
    private val SCAN_ADVANCING = 0L

    @Test
    fun `synced wallet that has not re-anchored attempts a re-anchor`() {
        assertEquals(
            PostTimeoutAction.REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `never-synced wallet skips the re-anchor and stays on filters`() {
        // The re-anchor skips the historical [cfTip, floor] gap on the has_synced
        // guarantee that bloom already scanned it. Absent that, don't re-anchor.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS,
            decidePostTimeoutAction(
                hasReachedSynced = false,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `freshly re-anchored chain is given grace to rebuild, not abandoned`() {
        // THE FIX: getCFChainTipHeight() reads 0 while the freed chain rebuilds;
        // within the grace window the watchdog must keep waiting, not fall back.
        assertEquals(
            PostTimeoutAction.AWAIT_REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
        assertEquals(
            PostTimeoutAction.AWAIT_REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = REANCHOR_GRACE_MS - 1,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `re-anchored chain that never rebuilt within grace stays on filters`() {
        // Bounded: if the first cfheaders append never lands, staying on compact
        // filters (giving up gracefully) is the safe floor — there is no bloom
        // fallback, the wallet keeps syncing on filters.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = REANCHOR_GRACE_MS,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `grace timer is ignored before any re-anchor`() {
        // reanchoredThisSession=false must never yield AWAIT regardless of the
        // (meaningless) timer value — a never-synced wallet still stays on filters.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS,
            decidePostTimeoutAction(
                hasReachedSynced = false,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    // ── Part D: the post-timeout re-anchor is keyed on SCAN progress ──

    @Test
    fun `post-timeout re-anchor does NOT fire while the convoy scan is advancing`() {
        // CONVOY: the paced fetch deliberately freezes the cfheader frontier within
        // CF_CONVOY_WINDOW of the scan frontier, so "headers caught up + cfheaders
        // not advancing" is the DESIGNED steady state, not a deficit. Re-anchoring
        // there deletes FilterHeaderStore + CfScanLedgerStore and throws away the
        // whole descent. While the SCAN advances there is nothing to recover.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_ADVANCING,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `post-timeout re-anchor is suppressed while the abandonment valve owns the stall`() {
        // The valve's re-arm cycle IS productive work and it PINS the scan frontier
        // by construction (a gaveUp hole caps scannedThrough). Re-anchoring on top of
        // it destroys the ledger the valve is deciding on.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK,
            ),
        )
    }

    @Test
    fun `post-timeout re-anchor is re-enabled once the valve exceeds its cycle budget`() {
        assertEquals(
            PostTimeoutAction.REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1,
            ),
        )
    }

    // --- isFilterSyncHealthy: the false "Privacy degraded" regression ---

    @Test
    fun `already-synced wallet at the network tip is healthy even though cfTip did not advance`() {
        // THE BUG: an established wallet relaunching with its filter chain already
        // at the tip has nothing new to fetch, so cfTip never advances this session.
        // The advance-only check mislabeled it "stuck" and degraded it to bloom,
        // surfacing "Privacy degraded" on a wallet whose filters are complete.
        assertEquals(
            true,
            isFilterSyncHealthy(gap = 0L, cfAdvancedSinceStart = false, blocksCaughtUp = true),
        )
    }

    @Test
    fun `filters actively riding the chain are healthy`() {
        assertEquals(
            true,
            isFilterSyncHealthy(gap = 10L, cfAdvancedSinceStart = true, blocksCaughtUp = false),
        )
    }

    @Test
    fun `filters far behind the tip are not healthy`() {
        // Genuine CF-peer scarcity: blocks caught up (via bloom) but cfTip is far
        // behind — this MUST still be reported unhealthy so the honest degrade path
        // runs. The fix must not mask a real filter deficit.
        assertEquals(
            false,
            isFilterSyncHealthy(gap = 500L, cfAdvancedSinceStart = false, blocksCaughtUp = true),
        )
    }

    @Test
    fun `filters at the restore tip below the network tip are not healthy`() {
        // "Stuck at restore": cfTip==blockTip (gap small) but the block chain has
        // NOT reached the network tip, so we cannot conclude filters are complete —
        // keep monitoring, do not declare healthy.
        assertEquals(
            false,
            isFilterSyncHealthy(gap = 0L, cfAdvancedSinceStart = false, blocksCaughtUp = false),
        )
    }

    @Test
    fun `gap boundary is inclusive and one past it is unhealthy`() {
        assertEquals(
            true,
            isFilterSyncHealthy(gap = HEALTHY_CF_GAP_BLOCKS, cfAdvancedSinceStart = false, blocksCaughtUp = true),
        )
        assertEquals(
            false,
            isFilterSyncHealthy(gap = HEALTHY_CF_GAP_BLOCKS + 1, cfAdvancedSinceStart = true, blocksCaughtUp = true),
        )
    }

    @Test
    fun `convoy at the window top reads healthy — both frontiers ride together`() {
        // Convoy disposition of the HEALTHY check (spec Part D): unchanged. Under the
        // paced convoy the cfheader frontier sits at the window top right behind the
        // block-header frontier, so the gap stays inside HEALTHY_CF_GAP_BLOCKS and the
        // watchdog exits without running any destructive branch. That exit IS the
        // desired convoy disposition — no re-key needed.
        assertEquals(
            true,
            isFilterSyncHealthy(gap = 0L, cfAdvancedSinceStart = true, blocksCaughtUp = false),
        )
    }

    // ── shouldRecoverFrozenCf — the CF-wedge (cfheaders-frozen) recovery gate ──

    @Test
    fun `cfTip frozen past the window while headers climb triggers recovery`() {
        // THE WEDGE: cfheaders made progress (netMax > 0) then stuck in a continuity
        // re-anchor loop; block headers keep importing. This is exactly the case the
        // blocksCaughtUp short-circuit is blind to. The SCAN is frozen too — nothing
        // is draining — so the recovery is warranted.
        assertEquals(
            true,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `does not recover when the block tip climbs but the convoy SCAN is advancing`() {
        // THE CONVOY REGRESSION THIS TASK EXISTS FOR: during a healthy paced descent
        // the single connected filter-capable peer drops, so getCFChainTipHeight()
        // freezes while the buffered cfilters keep draining (scan frontier advancing)
        // and the B1 driver keeps the block tip climbing — i.e. exactly
        // blockClimbing && cfTip-frozen. The old keying then DELETED FilterHeaderStore
        // + CfScanLedgerStore and recreated the manager, wiping the whole descent, and
        // re-fired every session so a deep restore could loop forever. Scan progress is
        // the proof that the CF path is alive: do not fire.
        assertEquals(
            false,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS * 5,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
                scanStalledMs = SCAN_ADVANCING,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `frozen-cf recovery is suppressed while the abandonment valve owns the stall`() {
        // A gaveUp hole PINS the scan frontier by construction, so scan-frozen alone
        // would hand the valve's own stall straight to the most destructive branch —
        // deleting the very ledger whose rearmCycles/latch state the valve is deciding
        // on. Stand down while the valve is inside its budget.
        assertEquals(
            false,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS * 5,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK,
            ),
        )
    }

    @Test
    fun `frozen-cf recovery is re-enabled once the valve exceeds its cycle budget`() {
        // BOUNDED SUPPRESSION: an indefinitely re-arming hole must re-expose itself.
        assertEquals(
            true,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS * 5,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1,
            ),
        )
    }

    @Test
    fun `does not recover before the frozen window elapses`() {
        assertEquals(
            false,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS - 1,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `does not recover when headers are not climbing`() {
        // If block sync itself has stalled, cfTip-frozen isn't the cfheaders wedge —
        // a different failure; don't fire this recovery.
        assertEquals(
            false,
            shouldRecoverFrozenCf(
                blockClimbing = false,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS * 3,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `does not recover during the normal pre-CF phase (netMax still zero)`() {
        // Headers importing from birth before cfheaders has fetched anything is
        // NORMAL — cfTip legitimately 0 until headers climb above the frontier.
        // Requiring cfNetMax > 0 avoids a false recovery here.
        assertEquals(
            false,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS * 3,
                cfNetMax = 0,
                alreadyRecovered = false,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test
    fun `recovers at most once per session`() {
        assertEquals(
            false,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS * 5,
                cfNetMax = 23_779_855,
                alreadyRecovered = true,
                scanStalledMs = SCAN_FROZEN,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    // ── convoy window arithmetic (W_hdr >= CF_CONVOY_WINDOW) ──

    @Test fun `window is full exactly at W and open one below it`() {
        val scan = 1_000_000L
        assertEquals(true, isConvoyWindowFull(scan + CF_CONVOY_WINDOW_FALLBACK, scan))
        assertEquals(false, isConvoyWindowFull(scan + CF_CONVOY_WINDOW_FALLBACK - 1, scan))
    }

    @Test fun `window reads OPEN when the scan frontier is not armed`() {
        // LowestNeededHeight is 0 with no peer manager and 1 on a calloc'd ledger.
        // Measuring either against a mainnet tip scores the window permanently full,
        // which would suppress the watchdog's residual check forever (and, natively,
        // the block-header sync that must run FIRST). Not armed => not full.
        assertEquals(false, isConvoyWindowFull(23_900_000L, 0L))
        assertEquals(false, isConvoyWindowFull(23_900_000L, 1L))
    }

    @Test fun `window reads OPEN when the scan frontier sits ABOVE the block tip`() {
        // UNDERFLOW GUARD (mirrors the native gate): an abandonment watermark can sit
        // past a not-yet-synced tip. Subtracting first would read "permanently full".
        assertEquals(false, isConvoyWindowFull(1_000_000L, 1_000_050L))
    }

    // ── BOUNDED abandonment suppression (spec Part C, "BOUND THE SUPPRESSION") ──

    @Test fun `nothing pending means no suppression`() {
        assertEquals(false, isConvoySuppressed(0))
    }

    @Test fun `suppressed through the cycles the valve is entitled to`() {
        // N == rearmCycles + 1: N=1 original cycle, N=2 first re-arm, N=3 the deciding
        // cycle (CF_CONVOY_REARM_MAX + 1). The valve may abandon at N=3, so it owns the
        // stall right through there.
        assertEquals(true, isConvoySuppressed(1))
        assertEquals(true, isConvoySuppressed(2))
        assertEquals(true, isConvoySuppressed(CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK))
    }

    // ── fix-wave I-3: the bound is DERIVED from the native re-arm budget ──

    @Test fun `the suppression bound is derived from the re-arm budget, not pinned`() {
        // The OLD test here asserted `assertEquals(3, CONVOY_SUPPRESSION_MAX_CYCLES)`.
        // That pinned the KOTLIN mirror and detected NOTHING about native — the actual
        // risk — and it would have spuriously FAILED on anyone who correctly raised the
        // Kotlin copy to track a retuned header. Pin the DERIVATION instead.
        assertEquals(3, convoySuppressionMaxCycles(2))
        assertEquals(5, convoySuppressionMaxCycles(4))
        assertEquals(9, convoySuppressionMaxCycles(8))
        assertEquals(convoySuppressionMaxCycles(CF_CONVOY_REARM_MAX_FALLBACK),
            CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK)
    }

    @Test fun `raising the native re-arm budget widens the suppression window`() {
        // THE DRIFT THE SPEC ACTIVELY INVITES: its own tuning signal tells the operator
        // to RAISE CF_CONVOY_REARM_MAX in BRPeerManager.h when an abandoned height later
        // reconciles. With the value hand-mirrored, Kotlin kept releasing at 3 while the
        // valve was legitimately working cycles 3..N, so the tip-stall watchdog escalated
        // INTO a productive valve and tier 2 recreated the manager. Derived from the
        // native budget, the bound moves with it.
        val retuned = convoySuppressionMaxCycles(4)   // operator raised CF_CONVOY_REARM_MAX to 4
        assertEquals(true, isConvoySuppressed(3, retuned))
        assertEquals(true, isConvoySuppressed(4, retuned))
        assertEquals(true, isConvoySuppressed(5, retuned))
        assertEquals(false, isConvoySuppressed(6, retuned))
        // ...and every branch that keys on it moves with it too, not just the predicate.
        assertEquals(false, tier1(abandonmentPendingCycles = 5, suppressionMaxCycles = retuned))
        assertEquals(true, tier1(abandonmentPendingCycles = 6, suppressionMaxCycles = retuned))
        assertEquals(false, tier2(abandonmentPendingCycles = 5, suppressionMaxCycles = retuned))
        assertEquals(true, tier2(abandonmentPendingCycles = 6, suppressionMaxCycles = retuned))
        assertEquals(false, fast(abandonmentPendingCycles = 5, suppressionMaxCycles = retuned))
        assertEquals(true, fast(abandonmentPendingCycles = 6, suppressionMaxCycles = retuned))
        assertEquals(false, heal(abandonmentPendingCycles = 5, suppressionMaxCycles = retuned))
        assertEquals(true, heal(abandonmentPendingCycles = 6, suppressionMaxCycles = retuned))
    }

    @Test fun `the convoy window comes from the caller, not a Kotlin constant`() {
        // A Kotlin CF_CONVOY_WINDOW that drifted BELOW a retuned native one would read
        // "window not full", drop the tip-frozen conjunct and arm tier 1/tier 2 during a
        // HEALTHY paced descent. The window is a parameter so SyncService can pass the
        // value it read from the .so.
        val scan = 20_000_000L
        assertEquals(true, isConvoyWindowFull(scan + 4_000L, scan, window = 4_000L))
        assertEquals(false, isConvoyWindowFull(scan + 3_999L, scan, window = 4_000L))
        // ...and the same frontier pair reads NOT-full against the larger default,
        // which is exactly the misread a stale mirror would produce.
        assertEquals(false, isConvoyWindowFull(scan + 4_000L, scan))
    }

    @Test fun `suppression LIFTS once the valve re-arms past its budget`() {
        // THE TASK-5 REVIEW FINDING: the per-cycle offersReachedLivePeer latch is
        // cleared by ANY disconnect of the peer stamped on the hole, and a deciding
        // cycle is 5 offers over ~7.5 min — so on a churny fleet EVERY cycle can be
        // tainted and the valve re-arms INDEFINITELY. A bare `pending > 0` suppression
        // would then be permanently active and the backstop would stand down forever,
        // in exactly the case that needs it. Above the bound the watchdog escalates.
        assertEquals(false, isConvoySuppressed(CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1))
        assertEquals(false, isConvoySuppressed(9))
        assertEquals(false, isConvoySuppressed(255))
    }

    // ── tip-stall recovery, re-keyed on the CF SCAN frontier ──

    private fun tier1(
        peerCount: Int = 5,
        scanStalledMs: Long = TIP_STALL_TIMEOUT_MS,
        blockTipStalledMs: Long = TIP_STALL_TIMEOUT_MS,
        convoyWindowFull: Boolean = false,
        abandonmentPendingCycles: Int = 0,
        suppressionMaxCycles: Int = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK,
    ) = shouldRerequestHeadersOnStall(
        peerCount, scanStalledMs, blockTipStalledMs, convoyWindowFull, abandonmentPendingCycles,
        suppressionMaxCycles = suppressionMaxCycles,
    )

    private fun tier2(
        peerCount: Int = 5,
        scanStalledMs: Long = TIP_STALL_TIMEOUT_MS * 2,
        blockTipStalledMs: Long = TIP_STALL_TIMEOUT_MS * 2,
        convoyWindowFull: Boolean = false,
        tier1Fired: Boolean = true,
        abandonmentPendingCycles: Int = 0,
        suppressionMaxCycles: Int = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK,
    ) = shouldForceReconnectOnStall(
        peerCount, scanStalledMs, blockTipStalledMs, convoyWindowFull, tier1Fired, abandonmentPendingCycles,
        suppressionMaxCycles = suppressionMaxCycles,
    )

    private fun fast(
        peerCount: Int = 5,
        scanStalledMs: Long = 3 * 60 * 1000L,
        blockTipStalledMs: Long = 3 * 60 * 1000L,
        convoyWindowFull: Boolean = false,
        abandonmentPendingCycles: Int = 0,
        suppressionMaxCycles: Int = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK,
    ) = shouldFastRecoverOnStall(
        peerCount, scanStalledMs, blockTipStalledMs, convoyWindowFull, abandonmentPendingCycles,
        thresholdMs = 3 * 60 * 1000L,
        suppressionMaxCycles = suppressionMaxCycles,
    )

    @Test fun `re-requests headers when the SCAN frontier is frozen past the window with peers`() {
        // The idle-wallet wedge the tip-stall watchdog was built for still fires: at the
        // network tip with silent peers nothing is scanned either, so the scan frontier
        // freezes with it.
        assertEquals(true, tier1())
    }

    @Test fun `does not re-request before the stall window elapses`() {
        assertEquals(false, tier1(scanStalledMs = TIP_STALL_TIMEOUT_MS - 1, blockTipStalledMs = TIP_STALL_TIMEOUT_MS - 1))
    }

    @Test fun `does not re-request with zero peers`() {
        // 0 peers is the existing watchdogs' job; this gate only handles live-but-silent peers.
        assertEquals(false, tier1(peerCount = 0, scanStalledMs = TIP_STALL_TIMEOUT_MS * 3))
    }

    @Test fun `tier1 does NOT fire when the header tip is frozen at the window top but the SCAN advances`() {
        // THE CONVOY MISFIRE: the paced fetch holds the header frontier at
        // scanFrontier + CF_CONVOY_WINDOW, so getLastBlockHeight() is frozen BY DESIGN
        // for the whole time the scan climbs that window. Tier 1 issues an UNGATED
        // getheaders that blows straight past the convoy window; tier 2 recreates the
        // peer manager mid-descent. Neither may fire while the scan is draining.
        assertEquals(
            false,
            tier1(
                scanStalledMs = 0L,
                blockTipStalledMs = TIP_STALL_TIMEOUT_MS * 10,
                convoyWindowFull = true,
            ),
        )
    }

    @Test fun `tier1 does NOT fire when the SCAN advances even with the window open`() {
        assertEquals(
            false,
            tier1(scanStalledMs = 0L, blockTipStalledMs = TIP_STALL_TIMEOUT_MS * 10, convoyWindowFull = false),
        )
    }

    @Test fun `residual dead-branch check FIRES when the block tip is frozen, the window is FULL and the scan is frozen`() {
        // A dead-branch tip pinned at the window top: the header frontier cannot advance
        // (the peers' real chain is below our fork tip), the gate reads the window as
        // full, and the scan cannot climb past it. Both clocks are stopped — escalate.
        assertEquals(
            true,
            tier1(
                scanStalledMs = TIP_STALL_TIMEOUT_MS,
                blockTipStalledMs = TIP_STALL_TIMEOUT_MS,
                convoyWindowFull = true,
            ),
        )
    }

    @Test fun `does NOT escalate on a scan stall while the gated header frontier is still re-kicking`() {
        // Window full + block tip STILL advancing (the B1 driver just landed another
        // 2000-header batch) = the header layer is alive and the scan stall belongs to
        // the filter layer (the BIP158 watchdog / the B2 valve). An ungated getheaders
        // cannot help and only overshoots the convoy window.
        assertEquals(
            false,
            tier1(
                scanStalledMs = TIP_STALL_TIMEOUT_MS * 5,
                blockTipStalledMs = 0L,
                convoyWindowFull = true,
            ),
        )
    }

    @Test fun `tier1 SUPPRESSED while an abandonment is pending below the cycle bound`() {
        assertEquals(false, tier1(abandonmentPendingCycles = 1))
        assertEquals(false, tier1(abandonmentPendingCycles = 2))
        assertEquals(false, tier1(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK))
    }

    @Test fun `tier1 NOT suppressed once the pending cycle count exceeds the bound`() {
        assertEquals(true, tier1(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1))
    }

    @Test fun `tier2 forceReconnect only after tier1 fired and a full extra window`() {
        assertEquals(false, tier2(tier1Fired = false))
        assertEquals(
            false,
            tier2(
                scanStalledMs = TIP_STALL_TIMEOUT_MS * 2 - 1,
                blockTipStalledMs = TIP_STALL_TIMEOUT_MS * 2 - 1,
            ),
        )
        assertEquals(true, tier2())
    }

    @Test fun `tier2 needs peers`() {
        assertEquals(false, tier2(peerCount = 0, scanStalledMs = TIP_STALL_TIMEOUT_MS * 5))
    }

    @Test fun `tier2 manager-recreate does NOT fire while the convoy SCAN advances`() {
        // Recreating the peer manager mid-descent is the most expensive possible churn.
        assertEquals(
            false,
            tier2(scanStalledMs = 0L, blockTipStalledMs = TIP_STALL_TIMEOUT_MS * 10, convoyWindowFull = true),
        )
    }

    @Test fun `tier2 SUPPRESSED while an abandonment is pending below the cycle bound, released above it`() {
        assertEquals(false, tier2(abandonmentPendingCycles = 1))
        assertEquals(false, tier2(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK))
        assertEquals(true, tier2(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1))
    }

    @Test fun `FAST tier fires on a frozen scan and needs peers`() {
        assertEquals(true, fast())
        assertEquals(false, fast(peerCount = 0))
        assertEquals(false, fast(scanStalledMs = 0L, blockTipStalledMs = 0L))
    }

    @Test fun `FAST tier does NOT fire while the header tip is gated at the window top and the scan advances`() {
        assertEquals(
            false,
            fast(scanStalledMs = 0L, blockTipStalledMs = 60 * 60 * 1000L, convoyWindowFull = true),
        )
    }

    @Test fun `FAST tier SUPPRESSED while an abandonment is pending below the cycle bound, released above it`() {
        assertEquals(false, fast(abandonmentPendingCycles = 1))
        assertEquals(false, fast(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK))
        assertEquals(true, fast(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1))
    }

    // ── shouldHealCorruptFilterChain — the poisoned-persisted-chain clean-slate heal ──

    private fun heal(
        blocksCaughtUp: Boolean = true,
        peerCount: Int = 4,
        cfFrozenMs: Long = CF_CORRUPT_HEAL_MS,
        reanchored: Boolean = true,
        msSinceReanchor: Long = REANCHOR_GRACE_MS,
        healsSoFar: Int = 0,
        scanStalledMs: Long = SCAN_FROZEN,
        abandonmentPendingCycles: Int = 0,
        suppressionMaxCycles: Int = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK,
    ) = shouldHealCorruptFilterChain(
        blocksCaughtUp, peerCount, cfFrozenMs, reanchored, msSinceReanchor, healsSoFar,
        scanStalledMs, abandonmentPendingCycles,
        suppressionMaxCycles = suppressionMaxCycles,
    )

    @Test fun `heals when re-anchor already fired and grace elapsed but cfTip still frozen at tip`() {
        // THE POISONED-CHAIN CASE: headers at the network tip, filter peers connected,
        // the one-time re-anchor already ran + got its grace, yet cfTip is STILL frozen
        // — the wallet's own persisted filter chain can't extend (a prior build wrote
        // corrupt data). This is exactly the "stuck at a fixed block forever" wedge the
        // one-time re-anchor gives up on.
        assertEquals(true, heal())
    }

    @Test fun `does not heal before the re-anchor has been given its grace window`() {
        // The ordinary re-anchor must get its full rebuild grace before we escalate to
        // the expensive full-wipe re-sync — otherwise we'd nuke a re-anchor that was
        // about to succeed.
        assertEquals(false, heal(msSinceReanchor = REANCHOR_GRACE_MS - 1))
    }

    @Test fun `does not heal before the frozen window elapses`() {
        // A filter tip still creeping forward resets the frozen timer; only a truly
        // stuck tip reaches the threshold.
        assertEquals(false, heal(cfFrozenMs = CF_CORRUPT_HEAL_MS - 1))
    }

    @Test fun `does not heal until the ordinary one-time re-anchor has been tried`() {
        // The clean-slate wipe is the ESCALATION after the cheap re-anchor fails.
        // reanchored=false means the cheaper recovery hasn't run yet — don't skip it.
        assertEquals(false, heal(reanchored = false))
    }

    @Test fun `does not heal while block headers are still importing`() {
        // blocksCaughtUp=false means headers haven't reached the network tip yet, so a
        // lagging cfTip is legitimately waiting for headers — not corrupt.
        assertEquals(false, heal(blocksCaughtUp = false))
    }

    @Test fun `does not heal with zero filter peers`() {
        // With no peer to re-fetch a clean chain from, wiping would just re-sync into
        // the void. Wait for a peer.
        assertEquals(false, heal(peerCount = 0))
    }

    @Test fun `heal is bounded — stops after the per-session cap`() {
        // Each heal forces a full re-scan; a wallet that can't reach any healthy filter
        // peer must not loop full re-syncs forever. At the cap, give up gracefully.
        assertEquals(true, heal(healsSoFar = MAX_CF_CORRUPT_HEALS - 1))
        assertEquals(false, heal(healsSoFar = MAX_CF_CORRUPT_HEALS))
        assertEquals(false, heal(healsSoFar = MAX_CF_CORRUPT_HEALS + 1))
    }

    @Test fun `does NOT heal while the convoy SCAN is still advancing`() {
        // Same convoy misfire as shouldRecoverFrozenCf, one branch later: the heal
        // deletes FilterHeaderStore AND CfScanLedgerStore and force-reconnects. A
        // draining scan proves the CF path is alive — nothing to heal.
        assertEquals(false, heal(scanStalledMs = SCAN_ADVANCING))
    }

    @Test fun `heal is SUPPRESSED while an abandonment is pending below the bound, released above it`() {
        assertEquals(false, heal(abandonmentPendingCycles = 1))
        assertEquals(false, heal(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK))
        assertEquals(true, heal(abandonmentPendingCycles = CONVOY_SUPPRESSION_MAX_CYCLES_FALLBACK + 1))
    }

    // ── CF scan-frontier tracking: a frontier RE-INIT is not a stall ──
    //
    // Every destructive recovery in the app deletes CfScanLedgerStore and recreates the
    // manager, so native re-Inits the CF scan ledger at the floor and
    // getLowestNeededHeight() drops far BELOW the last observed value. A deep reorg
    // re-inits it too (BRPeerManager.c:4035), with no app-side branch involved at all.
    // A forward-only tracker never sees that as motion: the stall clock freezes and
    // grows without bound for the whole hours-long clean re-climb.

    private val FLOOR = 20_000_000L
    private val TIP_AT_START = 23_900_000L

    @Test fun `frontier tracker treats forward motion as progress and restarts the clock`() {
        val p = stepScanFrontier(prevFrontier = FLOOR, prevChangeMs = 1_000L, frontierNow = FLOOR + 500, nowMs = 9_000L)
        assertEquals(true, p.changed)
        assertEquals(FLOOR + 500, p.frontier)
        assertEquals(9_000L, p.lastChangeMs)
    }

    @Test fun `frontier tracker holds the clock when the frontier is unchanged`() {
        val p = stepScanFrontier(prevFrontier = FLOOR, prevChangeMs = 1_000L, frontierNow = FLOOR, nowMs = 9_000L)
        assertEquals(false, p.changed)
        assertEquals(FLOOR, p.frontier)
        assertEquals(1_000L, p.lastChangeMs)
    }

    @Test fun `frontier REGRESSION to a real floor is a re-init — it restarts the clock, it is not a stall`() {
        // THE BUG: after a corrupt-heal / frozen-CF recovery / re-anchor wipes
        // CfScanLedgerStore, or after a deep reorg, the frontier re-inits far below the
        // last observed value. Forward-only tracking freezes the clock there.
        val p = stepScanFrontier(
            prevFrontier = 23_890_000L, prevChangeMs = 1_000L, frontierNow = FLOOR, nowMs = 9_000L,
        )
        assertEquals(true, p.changed)
        assertEquals(FLOOR, p.frontier)
        assertEquals(9_000L, p.lastChangeMs)
    }

    @Test fun `a transient UNARMED reading is ignored — it neither restarts nor freezes the clock`() {
        // getLowestNeededHeight() reads 0 while the peer manager is absent (the window
        // between forceReconnect and startSync) and 1 on a calloc'd ledger. Adopting
        // that as a re-init would let a flapping read hold the stall clock at zero and
        // silently disable the backstop, so it must be dropped, not adopted.
        for (transient in longArrayOf(0L, 1L)) {
            val p = stepScanFrontier(
                prevFrontier = 23_890_000L, prevChangeMs = 1_000L, frontierNow = transient, nowMs = 9_000L,
            )
            assertEquals(false, p.changed)
            assertEquals(23_890_000L, p.frontier)
            assertEquals(1_000L, p.lastChangeMs)
        }
    }

    // ── TipStallState: signal selection, the tier-1 latch, the stall clocks ──

    @Test fun `tip-stall clock restarts and the tier-1 latch clears on a frontier re-init`() {
        // THE HARASSMENT BUG, at the level it actually lives: the tip-stall watchdog is
        // a SEPARATE coroutine with separate state from the BIP158 loop, so the
        // post-recovery resets in the BIP158 branches do not reach it. Without this,
        // scanStalledMs grows without bound across the whole clean re-climb and
        // tier1Fired can never clear.
        val armed = initialTipStallState(tip = TIP_AT_START, scan = 23_890_000L, nowMs = 0L)
            .copy(tier1Fired = true)
        val after = armed.step(tipNow = TIP_AT_START, scanNow = FLOOR, nowMs = 60 * 60 * 1000L)

        assertEquals(true, after.progressed)
        assertEquals(false, after.tier1Fired)
        assertEquals(0L, after.scanStalledMs(nowMs = 60 * 60 * 1000L))
        assertEquals(FLOOR, after.lastScan)
    }

    @Test fun `a re-init does NOT leave tier2 free to recreate the manager every hour`() {
        // End-to-end over the pure layer: re-init, then a full tier-2 window of the
        // clean re-climb making steady progress. Neither tier may fire.
        var s = initialTipStallState(tip = TIP_AT_START, scan = 23_890_000L, nowMs = 0L).copy(tier1Fired = true)
        var t = 60 * 60 * 1000L
        s = s.step(tipNow = TIP_AT_START, scanNow = FLOOR, nowMs = t)   // the re-init
        // ...then climb, one poll per minute, for well past 2x TIP_STALL_TIMEOUT_MS.
        repeat(90) {
            t += 60_000L
            s = s.step(tipNow = TIP_AT_START, scanNow = FLOOR + (it + 1) * 20L, nowMs = t)
        }
        assertEquals(
            false,
            shouldRerequestHeadersOnStall(
                peerCount = 6,
                scanStalledMs = s.scanStalledMs(t),
                blockTipStalledMs = s.blockTipStalledMs(t),
                convoyWindowFull = false,
                abandonmentPendingCycles = 0,
            ),
        )
        assertEquals(
            false,
            shouldForceReconnectOnStall(
                peerCount = 6,
                scanStalledMs = s.scanStalledMs(t),
                blockTipStalledMs = s.blockTipStalledMs(t),
                convoyWindowFull = false,
                tier1Fired = true,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test fun `a genuinely frozen frontier still accumulates the stall clock and keeps the latch`() {
        // The backstop must survive the fix: no motion at all is still a stall.
        var s = initialTipStallState(tip = TIP_AT_START, scan = FLOOR, nowMs = 0L).copy(tier1Fired = true)
        s = s.step(tipNow = TIP_AT_START, scanNow = FLOOR, nowMs = TIP_STALL_TIMEOUT_MS)
        assertEquals(false, s.progressed)
        assertEquals(true, s.tier1Fired)
        assertEquals(TIP_STALL_TIMEOUT_MS, s.scanStalledMs(TIP_STALL_TIMEOUT_MS))
        assertEquals(
            true,
            shouldRerequestHeadersOnStall(
                peerCount = 6,
                scanStalledMs = s.scanStalledMs(TIP_STALL_TIMEOUT_MS),
                blockTipStalledMs = s.blockTipStalledMs(TIP_STALL_TIMEOUT_MS),
                convoyWindowFull = false,
                abandonmentPendingCycles = 0,
            ),
        )
    }

    @Test fun `while the scan is armed a block-tip advance is NOT progress`() {
        // The convoy gate re-kicks 2000 headers every time the scan climbs out of the
        // window, so a tip advance must not clear the tier-1 latch — tier 2 requires
        // that latch and could otherwise never escalate a real scan wedge.
        var s = initialTipStallState(tip = TIP_AT_START, scan = FLOOR, nowMs = 0L).copy(tier1Fired = true)
        s = s.step(tipNow = TIP_AT_START + 2000, scanNow = FLOOR, nowMs = 60_000L)
        assertEquals(false, s.progressed)
        assertEquals(true, s.tier1Fired)
        assertEquals(60_000L, s.scanStalledMs(60_000L))       // scan clock still running
        assertEquals(0L, s.blockTipStalledMs(60_000L))        // tip clock reset
    }

    @Test fun `while the scan is UNARMED the block tip is the authoritative signal`() {
        // Legacy keying for the window before the CF scan ledger is armed: a constant
        // 0/1 frontier must not read as "frozen forever" and escalate on a wallet that
        // is simply still coming up.
        var s = initialTipStallState(tip = TIP_AT_START, scan = 0L, nowMs = 0L).copy(tier1Fired = true)
        s = s.step(tipNow = TIP_AT_START + 1, scanNow = 0L, nowMs = 60_000L)
        assertEquals(false, s.scanArmed)
        assertEquals(true, s.progressed)
        assertEquals(false, s.tier1Fired)
        assertEquals(0L, s.scanStalledMs(60_000L))

        // ...and a frozen tip while unarmed DOES still arm the watchdog.
        val frozen = s.step(tipNow = TIP_AT_START + 1, scanNow = 0L, nowMs = 60_000L + TIP_STALL_TIMEOUT_MS)
        assertEquals(TIP_STALL_TIMEOUT_MS, frozen.scanStalledMs(60_000L + TIP_STALL_TIMEOUT_MS))
    }

    @Test fun `arming the ledger mid-session hands the signal over without a false stall`() {
        var s = initialTipStallState(tip = TIP_AT_START, scan = 0L, nowMs = 0L)
        s = s.step(tipNow = TIP_AT_START, scanNow = FLOOR, nowMs = 30_000L)
        assertEquals(true, s.scanArmed)
        assertEquals(true, s.progressed)
        assertEquals(0L, s.scanStalledMs(30_000L))
    }

    @Test fun `heal cooldown is wider than the freeze threshold to survive reconnect latency`() {
        // Regression guard for the heal-cascade finding: a heal's forceReconnect drops
        // all peers, so cfTip reads 0 (== the reset cfNetMax) until the re-fetch
        // reconnects and lands its first batch — during which the frozen timer keeps
        // growing. The re-heal cooldown MUST exceed the frozen-detection threshold, or
        // reconnect latency is misread as freeze and burns the whole heal budget.
        assertEquals(true, CF_CORRUPT_HEAL_COOLDOWN_MS > CF_CORRUPT_HEAL_MS)
    }
}
