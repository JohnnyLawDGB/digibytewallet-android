package io.digibyte.core.tor

import android.content.Context
import android.util.Log
import io.digibyte.core.InMemoryPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The announced clearnet fallback belongs to one period of the Tor setting being on. Turning the
 * setting off ends it, so turning Tor back on starts from "wait for Tor" (no direct app traffic
 * before a new banner). The sync service's own degradation paths leave the setting on, so they
 * keep the announcement.
 */
class TorSettingOffEndsFallbackTest {

    private val prefs = InMemoryPrefs()
    private lateinit var tor: TorManager

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
        tor = TorManager(ctx)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `turning Tor off ends the announced fallback`() {
        tor.isEnabled = true
        tor.announceClearnetFallback()
        assertTrue(tor.clearnetFallbackAnnounced.value)
        tor.isEnabled = false
        assertFalse(tor.clearnetFallbackAnnounced.value)
        tor.isEnabled = true
        assertEquals("re-enabled Tor, still bootstrapping: app traffic waits", TorRoute.Blocked, tor.route())
    }

    @Test fun `a fallback announced while the setting stays on is kept`() {
        tor.isEnabled = true
        tor.announceClearnetFallback()
        tor.stop()                      // the sync service's degradation paths stop the daemon, setting on
        tor.isEnabled = true
        assertTrue(tor.clearnetFallbackAnnounced.value)
        assertEquals(TorRoute.Direct, tor.route())
    }

    @Test fun `the setting is observable`() {
        tor.isEnabled = true
        assertTrue(tor.enabled.value)
        tor.isEnabled = false
        assertFalse(tor.enabled.value)
    }
}
