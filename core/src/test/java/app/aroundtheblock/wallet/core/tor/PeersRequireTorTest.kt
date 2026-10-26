package app.aroundtheblock.wallet.core.tor

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.InMemoryPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 4.1.2 row 2 (N3): the peer-to-peer core reaches peers through Tor or not at all while the
 * user's Tor setting is on and no clearnet fallback has been announced — the same rule as
 * [TorRoute] for app traffic. The value reaches the core synchronously on every change: the
 * fallback path dials direct right after announcing, so a late value would refuse those dials,
 * and a late "on" would let a direct dial through.
 */
class PeersRequireTorTest {

    private val prefs = InMemoryPrefs()
    private lateinit var tor: TorManager
    private val seen = mutableListOf<Boolean>()

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

    @Test fun `binding hands over the current value at once`() {
        tor.isEnabled = true
        tor.bindPeersRequireTor { seen += it }
        assertEquals(listOf(true), seen)
    }

    @Test fun `the setting on requires Tor until a fallback is announced`() {
        tor.bindPeersRequireTor { seen += it }
        tor.isEnabled = true
        tor.announceClearnetFallback()
        assertEquals(listOf(false, true, false), seen)
    }

    @Test fun `turning the setting off releases it, turning it back on requires it again`() {
        tor.isEnabled = true
        tor.announceClearnetFallback()
        tor.bindPeersRequireTor { seen += it }
        tor.isEnabled = false
        tor.isEnabled = true
        assertEquals(listOf(false, false, true), seen)
    }

    @Test fun `off with no fallback never requires Tor`() {
        tor.bindPeersRequireTor { seen += it }
        tor.isEnabled = false
        assertEquals(listOf(false, false), seen)
    }
}
