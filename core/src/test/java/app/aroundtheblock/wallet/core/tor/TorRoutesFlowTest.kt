package app.aroundtheblock.wallet.core.tor

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.InMemoryPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * HUB-WS-TOR: [TorManager.routes] reports the route [TorManager.route] answers, at every change of
 * it — the setting, Tor's state and the announced fallback each move it — so a long-lived
 * connection that follows it is told whenever the route it was opened on is no longer the route.
 */
class TorRoutesFlowTest {

    private lateinit var tor: TorManager
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val seen = mutableListOf<TorRoute>()

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns InMemoryPrefs()
        tor = TorManager(ctx)
        scope.launch { tor.routes.collect { seen += it } }
    }

    @After fun tearDown() {
        scope.cancel()
        unmockkStatic(Log::class)
    }

    private fun assertReported(expected: TorRoute) {
        assertEquals("the route itself", expected, tor.route())
        assertEquals("the route last reported, of $seen", expected, seen.last())
    }

    @Test fun `the route in force is reported first`() {
        assertEquals(listOf<TorRoute>(TorRoute.Direct), seen)
    }

    @Test fun `each change of the route is reported as it is made`() {
        tor.isEnabled = true
        assertReported(TorRoute.Blocked)

        torState().value = TorState.Connected(9050)
        assertReported(TorRoute.Socks(9050))

        torState().value = TorState.Connected(9051)
        assertReported(TorRoute.Socks(9051))

        torState().value = TorState.Failed("gone")
        assertReported(TorRoute.Blocked)

        tor.announceClearnetFallback()
        assertReported(TorRoute.Direct)

        tor.isEnabled = false
        tor.isEnabled = true               // off ends the announced fallback: back to waiting for Tor
        assertReported(TorRoute.Blocked)

        tor.isEnabled = false
        assertReported(TorRoute.Direct)
    }

    @Test fun `GUARD a change that leaves the route alone is not reported`() {
        torState().value = TorState.Starting
        torState().value = TorState.Connecting
        torState().value = TorState.Disabled
        assertEquals(listOf<TorRoute>(TorRoute.Direct), seen)
    }

    /** Tor's state as the daemon's events set it; the manager keeps it private, and no daemon runs here. */
    @Suppress("UNCHECKED_CAST")
    private fun torState(): MutableStateFlow<TorState> =
        TorManager::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(tor) as MutableStateFlow<TorState>
}
