package app.aroundtheblock.wallet.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.tor.TorManager
import app.aroundtheblock.wallet.core.tor.TorRoute
import app.aroundtheblock.wallet.core.tor.TorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HUB-WS-TOR, as the app wires it: the Hub socket AppModule provides follows the real [TorManager].
 * A change of its route — the user's Tor setting turned on or off, Tor connecting, the clearnet
 * fallback being announced — closes the socket opened on the old route, on the thread that made
 * the change and before the change returns; and while the setting is on and Tor is not connected
 * no socket is started. The client records what is opened on it; no socket is opened.
 */
class HubSocketRouteChangeTest {

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** The user's Tor setting, as the preferences file holds it. */
    private var torSetting = false

    private val tor: TorManager = run {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean("tor_enabled", any()) } answers { torSetting = secondArg(); editor }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean("tor_enabled", any()) } answers { torSetting }
        every { prefs.edit() } returns editor
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationContext } returns context
        TorManager(context)
    }

    private val sockets = mutableListOf<RecordingSocket>()
    private val client = mockk<OkHttpClient>().also { client ->
        every { client.newWebSocket(any(), any()) } answers {
            RecordingSocket().also { sockets += it }
        }
    }
    private val digiScope = mockk<DigiScopeClient>().also { every { it.getToken() } returns "token" }
    private val hub: HubWebSocket = AppModule.provideHubWebSocket(client, digiScope, tor)

    @Test fun `turning Tor on closes the socket opened direct and nothing is started until Tor connects`() {
        startSocket()

        tor.isEnabled = true               // Settings → Tor on; Tor has not connected

        assertEquals(TorRoute.Blocked, tor.route())
        assertEnded(sockets[0])
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)

        hub.connect()                      // the chat screen's retry, while Tor starts
        hub.connect()
        assertEquals("a socket was started while Tor was on and not connected", 1, sockets.size)

        torConnects(9050)
        hub.connect()
        assertEquals("the retry opened no socket once Tor was connected", 2, sockets.size)
        assertFalse(sockets[1].ended)
    }

    @Test fun `turning Tor off closes the socket opened through Tor`() {
        tor.isEnabled = true
        torConnects(9050)
        startSocket()

        // What Settings does on Tor off (SettingsViewModel.setTorEnabled): the setting, then the daemon.
        tor.isEnabled = false
        // The daemon is still up, so the route is still Tor's and the socket is still on it.
        assertEquals(TorRoute.Socks(9050), tor.route())
        assertFalse("the socket was closed though the route had not changed yet", sockets[0].ended)

        tor.stop()

        assertEquals(TorRoute.Direct, tor.route())
        assertEnded(sockets[0])
    }

    @Test fun `Tor connecting closes the socket opened direct after the fallback was announced`() {
        tor.isEnabled = true
        tor.announceClearnetFallback()     // the "Tor unavailable" banner is up: direct is allowed
        startSocket()

        torConnects(9050)

        assertEquals(TorRoute.Socks(9050), tor.route())
        assertEnded(sockets[0])
    }

    @Test fun `Tor dropping closes the socket opened through it`() {
        tor.isEnabled = true
        torConnects(9050)
        startSocket()

        torState().value = TorState.Failed("gone")

        assertEquals(TorRoute.Blocked, tor.route())
        assertEnded(sockets[0])
        hub.connect()
        assertEquals("a socket was started while Tor was on and not connected", 1, sockets.size)
    }

    @Test fun `GUARD a Tor state change that leaves the route alone closes nothing`() {
        startSocket()                   // Tor off: direct

        torState().value = TorState.Starting
        torState().value = TorState.Disabled

        assertFalse("the socket was closed though the route never changed", sockets[0].ended)
        assertEquals(ConnectionState.RECONNECTING, hub.connectionState.value)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Tor's state as the daemon's events set it; the manager keeps it private, and no daemon runs here. */
    @Suppress("UNCHECKED_CAST")
    private fun torState(): MutableStateFlow<TorState> =
        TorManager::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(tor) as MutableStateFlow<TorState>

    private fun torConnects(port: Int) { torState().value = TorState.Connected(port) }

    /**
     * Starts a socket. It is left opening: a socket is the old route's from the moment it is
     * started, and the app's unit tests have no JSON to build the join an opening sends.
     */
    private fun startSocket() {
        hub.connect()
        assertEquals(ConnectionState.RECONNECTING, hub.connectionState.value)
    }

    private fun assertEnded(socket: RecordingSocket) {
        assertEquals("the socket opened on the old route was not closed", 1000, socket.closeCode)
        assertTrue("the socket opened on the old route was closed but not cancelled", socket.cancelled)
    }

    private class RecordingSocket : WebSocket {
        var closeCode: Int? = null
        var cancelled = false
        val ended get() = closeCode != null || cancelled
        override fun request(): Request = Request.Builder().url(HubWebSocket.WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closeCode = code; return true }
        override fun cancel() { cancelled = true }
    }
}
