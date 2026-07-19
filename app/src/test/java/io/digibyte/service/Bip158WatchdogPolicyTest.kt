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

    @Test
    fun `synced wallet that has not re-anchored attempts a re-anchor`() {
        assertEquals(
            PostTimeoutAction.REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
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
            ),
        )
        assertEquals(
            PostTimeoutAction.AWAIT_REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = REANCHOR_GRACE_MS - 1,
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

    // ── shouldRecoverFrozenCf — the CF-wedge (cfheaders-frozen) recovery gate ──

    @Test
    fun `cfTip frozen past the window while headers climb triggers recovery`() {
        // THE WEDGE: cfheaders made progress (netMax > 0) then stuck in a continuity
        // re-anchor loop; block headers keep importing. This is exactly the case the
        // blocksCaughtUp short-circuit is blind to.
        assertEquals(
            true,
            shouldRecoverFrozenCf(
                blockClimbing = true,
                cfFrozenMs = CF_FROZEN_RECOVERY_MS,
                cfNetMax = 23_779_855,
                alreadyRecovered = false,
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
            ),
        )
    }

    // ── tip-stall recovery (frozen BLOCK-header tip — the "no confirms for days") ──

    @Test fun `re-requests headers when block tip frozen past the window with peers`() {
        assertEquals(true, shouldRerequestHeadersOnStall(peerCount = 5, tipStalledMs = TIP_STALL_TIMEOUT_MS))
    }

    @Test fun `does not re-request before the stall window elapses`() {
        assertEquals(false, shouldRerequestHeadersOnStall(peerCount = 5, tipStalledMs = TIP_STALL_TIMEOUT_MS - 1))
    }

    @Test fun `does not re-request with zero peers`() {
        // 0 peers is the existing watchdogs' job; this gate only handles live-but-silent peers.
        assertEquals(false, shouldRerequestHeadersOnStall(peerCount = 0, tipStalledMs = TIP_STALL_TIMEOUT_MS * 3))
    }

    @Test fun `tier2 forceReconnect only after tier1 fired and a full extra window`() {
        assertEquals(false, shouldForceReconnectOnStall(5, TIP_STALL_TIMEOUT_MS * 2, tier1Fired = false))
        assertEquals(false, shouldForceReconnectOnStall(5, TIP_STALL_TIMEOUT_MS * 2 - 1, tier1Fired = true))
        assertEquals(true, shouldForceReconnectOnStall(5, TIP_STALL_TIMEOUT_MS * 2, tier1Fired = true))
    }

    @Test fun `tier2 needs peers`() {
        assertEquals(false, shouldForceReconnectOnStall(0, TIP_STALL_TIMEOUT_MS * 5, tier1Fired = true))
    }
}
