package app.aroundtheblock.wallet.core.security

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 4.1.2 row 23: a PIN store that cannot be opened (a Keystore or keyset fault) no longer crashes the
 * app at injection; it becomes a store that refuses everything, so nothing reads it as "no PIN".
 */
class PinStoreUnavailableTest {

    @Before fun log() { mockkStatic(Log::class); every { Log.e(any(), any<String>()) } returns 0 }
    @After fun unlog() = unmockkStatic(Log::class)

    @Test fun `a store that throws on open becomes the unavailable store`() {
        assertSame(UnavailablePinStore, PinManager.openStore { throw java.security.GeneralSecurityException("keyset") })
    }

    @Test fun `a manager over it reports the store unavailable and answers nothing`() {
        val pins = PinManager(UnavailablePinStore)
        assertFalse(pins.storeAvailable)
        try {
            pins.hasPin()
            fail("an unreadable store answered whether a PIN is set")
        } catch (_: IllegalStateException) {
        }
    }
}
