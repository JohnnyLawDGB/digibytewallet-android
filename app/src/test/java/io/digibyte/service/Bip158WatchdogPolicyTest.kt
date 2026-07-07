package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [decidePostTimeoutAction] — the BIP158 watchdog's
 * post-timeout branch that chooses between re-anchoring the filter chain,
 * waiting for a just-re-anchored chain to rebuild, or degrading to bloom.
 *
 * Regression guard for the deep-deficit recovery bug: a re-anchor frees the
 * compact-filter chain, so getCFChainTipHeight() reads 0 until the first
 * cfheaders response lazily rebuilds it. The old one-shot logic gave that
 * rebuild a single 15s poll before degrading to bloom, so a slow/Tor
 * round-trip or a momentarily-absent filter peer abandoned a re-anchor that
 * would have succeeded. AWAIT_REANCHOR within REANCHOR_GRACE_MS is the fix.
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
    fun `never-synced wallet skips the re-anchor and degrades to bloom`() {
        // The re-anchor skips the historical [cfTip, floor] gap on the has_synced
        // guarantee that bloom already scanned it. Absent that, don't re-anchor.
        assertEquals(
            PostTimeoutAction.FALLBACK_BLOOM,
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
    fun `re-anchored chain that never rebuilt within grace degrades to bloom`() {
        // Bounded: if the first cfheaders append never lands, bloom is the safe
        // floor — the wallet still syncs and the privacy banner is surfaced.
        assertEquals(
            PostTimeoutAction.FALLBACK_BLOOM,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = REANCHOR_GRACE_MS,
            ),
        )
    }

    @Test
    fun `DigiDollar wallet stays on filters instead of the P2TR-blind bloom degrade`() {
        // issue #19: bloom never matches P2TR, so degrading would silently miss
        // DD receives. Once re-anchor is exhausted, a DD wallet keeps filters.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS_DD,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = REANCHOR_GRACE_MS, // grace expired → would be bloom
                hasDigiDollarBalance = true,
            ),
        )
        // never-synced DD wallet: still prefer filters over blinding bloom.
        assertEquals(
            PostTimeoutAction.STAY_ON_FILTERS_DD,
            decidePostTimeoutAction(
                hasReachedSynced = false,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                hasDigiDollarBalance = true,
            ),
        )
    }

    @Test
    fun `re-anchor still takes precedence over the DD guard`() {
        // Re-anchor also stays on filters and can fix a real deficit, so try it
        // first even with a DD balance.
        assertEquals(
            PostTimeoutAction.REANCHOR,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
                hasDigiDollarBalance = true,
            ),
        )
    }

    @Test
    fun `no DigiDollar balance keeps the original bloom degrade`() {
        assertEquals(
            PostTimeoutAction.FALLBACK_BLOOM,
            decidePostTimeoutAction(
                hasReachedSynced = true,
                reanchoredThisSession = true,
                msSinceReanchor = REANCHOR_GRACE_MS,
                hasDigiDollarBalance = false,
            ),
        )
    }

    @Test
    fun `grace timer is ignored before any re-anchor`() {
        // reanchoredThisSession=false must never yield AWAIT regardless of the
        // (meaningless) timer value — a never-synced wallet still falls back.
        assertEquals(
            PostTimeoutAction.FALLBACK_BLOOM,
            decidePostTimeoutAction(
                hasReachedSynced = false,
                reanchoredThisSession = false,
                msSinceReanchor = 0L,
            ),
        )
    }
}
