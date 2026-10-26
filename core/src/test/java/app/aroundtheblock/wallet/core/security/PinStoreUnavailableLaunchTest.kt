package app.aroundtheblock.wallet.core.security

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Launch never throws on a PIN store that cannot be opened (4.1.2 row 23, review R1).
 *
 * Row 23 turned a store that fails to open into [UnavailablePinStore], whose every read throws, and
 * gave the app a start route for it. But MainActivity.onCreate asks whether a wipe is owed before the
 * first screen, and two launch prompts wait on hasPin(): each threw on that store, so the app still
 * crash-looped before the store-unavailable screen could show. These are the questions launch asks.
 */
class PinStoreUnavailableLaunchTest {

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val pins = PinManager(UnavailablePinStore)

    @Test fun `launch asks whether a wipe is owed without throwing, and owes none it cannot read`() {
        val owed = try { pins.wipeOwedAtLaunch() } catch (t: Throwable) {
            fail("asking at launch whether a wipe is owed threw on an unavailable store: $t"); return
        }
        assertFalse("a wipe was reported owed from a store that cannot be read", owed)
    }

    @Test fun `a prompt waiting for a PIN never throws, and sees none on an unavailable store`() {
        val known = try { pins.pinKnownToExist() } catch (t: Throwable) {
            fail("waiting for a PIN threw on an unavailable store: $t"); return
        }
        assertFalse(known)
    }

    @Test fun `the lock treats an unreadable store as PIN-protected`() {
        assertTrue("an unavailable store left the wallet unlocked-by-default", pins.pinAssumedForLock())
    }
}
