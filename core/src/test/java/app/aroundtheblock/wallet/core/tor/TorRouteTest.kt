package app.aroundtheblock.wallet.core.tor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TorRoute.of]: while the user's Tor setting is on, nothing goes direct until the clearnet
 * fallback has been announced; a connected Tor is always used.
 */
class TorRouteTest {

    @Test fun `Tor setting off goes direct`() {
        assertEquals(TorRoute.Direct, TorRoute.of(torEnabled = false, socksPort = null, clearnetFallbackAnnounced = false))
        assertEquals(TorRoute.Direct, TorRoute.of(torEnabled = false, socksPort = null, clearnetFallbackAnnounced = true))
    }

    @Test fun `Tor connected goes through its SOCKS port`() {
        assertEquals(TorRoute.Socks(9150), TorRoute.of(torEnabled = true, socksPort = 9150, clearnetFallbackAnnounced = false))
        // A stale announcement never outranks a connected Tor.
        assertEquals(TorRoute.Socks(9150), TorRoute.of(torEnabled = true, socksPort = 9150, clearnetFallbackAnnounced = true))
    }

    @Test fun `Tor on and not connected is blocked until the fallback is announced`() {
        assertEquals(TorRoute.Blocked, TorRoute.of(torEnabled = true, socksPort = null, clearnetFallbackAnnounced = false))
        assertEquals(TorRoute.Direct, TorRoute.of(torEnabled = true, socksPort = null, clearnetFallbackAnnounced = true))
    }

    @Test fun `waiting for Tor is reported as a network failure`() {
        val e: java.io.IOException = TorNotReadyException("waiting")
        assertEquals("waiting", e.message)
    }
}
