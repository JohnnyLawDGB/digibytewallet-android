package io.digibyte.ui.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [shouldWakePeers] — the active-screen readiness
 * decision layered on the wallet's 5s poll. It gently wakes a dormant
 * (0-peer) SPV manager only while the user is actively looking at the screen
 * and Tor isn't mid-bootstrap.
 *
 * Why each guard exists:
 *  - peerCount == 0 only: startSync early-returns when peers > 0 (it can't top
 *    up a degraded count), and the C core self-heals nonzero counts via its
 *    disconnect callbacks — so a nudge above 0 is pointless.
 *  - isScreenActive: honors "active on screen, not background" — the poll's
 *    ViewModel can outlive the resumed Activity, so liveness != active.
 *  - !torComingUp: dialing direct while the SOCKS proxy is still wiring up
 *    would leak the wallet's IP.
 */
class ForegroundReadinessPolicyTest {

    @Test
    fun `active screen with zero peers and tor settled wakes the manager`() {
        assertTrue(
            shouldWakePeers(isScreenActive = true, torComingUp = false, peerCount = 0),
        )
    }

    @Test
    fun `nonzero peer count never nudges`() {
        // startSync no-ops at peers > 0 and the C core self-heals; don't bother.
        assertFalse(
            shouldWakePeers(isScreenActive = true, torComingUp = false, peerCount = 1),
        )
        assertFalse(
            shouldWakePeers(isScreenActive = true, torComingUp = false, peerCount = 5),
        )
    }

    @Test
    fun `backgrounded or off-screen never dials`() {
        // "active on screen, not background" — the whole point of the gate.
        assertFalse(
            shouldWakePeers(isScreenActive = false, torComingUp = false, peerCount = 0),
        )
    }

    @Test
    fun `tor bootstrapping suppresses the wake to avoid an IP leak`() {
        assertFalse(
            shouldWakePeers(isScreenActive = true, torComingUp = true, peerCount = 0),
        )
    }
}
