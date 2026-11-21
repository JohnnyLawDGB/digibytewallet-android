package app.aroundtheblock.wallet.core.hub

import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.tor.TorRoute
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * HUB-WS-TOR: no Hub socket outlives the route it was opened on, and two never run at once.
 *  - a route change (Tor turned on or off, direct to Tor or back, a new Tor port) closes the socket
 *    opened on the old route, when the change is heard and, failing that, before the next frame;
 *  - a socket that opens after the route changed under its handshake is closed and never joined;
 *  - connect() ends the socket there is before it starts another, and the old one's late end does
 *    not speak for the new one;
 *  - nothing is started while Tor is on and not connected.
 * Driven through the real [HubWebSocket.connect] on a client that records what is opened on it and
 * in what order; no socket is opened.
 */
class HubRouteChangeTest {

    private var route: TorRoute = TorRoute.Direct

    /** What happened, in order: "open N" when socket N is started, "close N" / "cancel N" when it is ended. */
    private val events = mutableListOf<String>()
    private val sockets = mutableListOf<RecordingSocket>()
    private val listeners = mutableListOf<WebSocketListener>()

    private val client = mockk<OkHttpClient>().also { client ->
        client.derivesItselfWithoutRedirects()
        every { client.newWebSocket(any(), any()) } answers {
            val socket = RecordingSocket(sockets.size + 1)
            events += "open ${socket.n}"
            sockets += socket
            listeners += secondArg<WebSocketListener>()
            socket
        }
    }
    private val digiScope = mockk<DigiScopeClient>().also { every { it.getToken() } returns "token" }
    private val hub = HubWebSocket(client, digiScope, route = { route })

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    // ── a route change closes the socket opened on the old route ─────────────────────────────

    @Test fun `turning Tor on closes the socket opened direct`() {
        connectAndOpen()
        route = TorRoute.Blocked

        assertTrue("the route change closed nothing", hub.closeIfRouteChanged())

        assertEnded(sockets[0])
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
        hub.sendMessage(1, "gm", "sig")
        assertEquals("a frame went out on the socket of the old route", listOf(JOIN), sockets[0].sent)
    }

    @Test fun `turning Tor off closes the socket opened through Tor`() {
        route = TorRoute.Socks(9050)
        connectAndOpen()
        route = TorRoute.Direct

        assertTrue(hub.closeIfRouteChanged())
        assertEnded(sockets[0])
    }

    @Test fun `a new Tor port closes the socket opened on the old one`() {
        route = TorRoute.Socks(9050)
        connectAndOpen()
        route = TorRoute.Socks(9051)

        assertTrue(hub.closeIfRouteChanged())
        assertEnded(sockets[0])
    }

    @Test fun `a socket still opening is ended by a route change`() {
        hub.connect()                      // started, not open yet
        route = TorRoute.Blocked

        assertTrue(hub.closeIfRouteChanged())
        assertEnded(sockets[0])
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    @Test fun `the route the Tor state holder reports is followed`() {
        val routes = MutableStateFlow<TorRoute>(TorRoute.Direct)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            hub.closeOnRouteChange(routes, scope)
            connectAndOpen()
            assertFalse("the socket was closed with no route change", sockets[0].ended)

            route = TorRoute.Blocked
            routes.value = TorRoute.Blocked

            assertEnded(sockets[0])
            assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
        } finally {
            scope.cancel()
        }
    }

    /** A route passed through and left again, which the holder's own answer no longer shows, still closes the socket. */
    @Test fun `a route that came and went still closes the socket`() {
        connectAndOpen()
        // The route is Direct again by now, but it was Tor's in between.
        assertTrue(hub.closeIfRouteChanged(now = TorRoute.Socks(9050)))
        assertEnded(sockets[0])
    }

    @Test fun `GUARD the same route closes nothing`() {
        route = TorRoute.Socks(9050)
        connectAndOpen()

        assertFalse(hub.closeIfRouteChanged())
        assertFalse(hub.closeIfRouteChanged(now = TorRoute.Socks(9050)))
        hub.sendMessage(1, "gm", "sig")

        assertFalse("a socket on the route in force was closed", sockets[0].ended)
        assertEquals(2, sockets[0].sent.size)
        assertEquals(ConnectionState.CONNECTED, hub.connectionState.value)
    }

    /** The reported route can be stale: equal to the opening route while the route in force has moved on. */
    @Test fun `a stale report of the old route does not keep the socket when the route in force changed`() {
        connectAndOpen()
        route = TorRoute.Blocked

        assertTrue("a stale report kept the socket of the old route", hub.closeIfRouteChanged(now = TorRoute.Direct))
        assertEnded(sockets[0])
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    // ── a change nobody reported ──────────────────────────────────────────────────────────────

    @Test fun `no frame goes out on a socket whose route changed unheard`() {
        connectAndOpen()
        route = TorRoute.Socks(9050)       // nothing told the socket

        hub.sendMessage(1, "gm", "sig")
        hub.sendTyping(1)

        assertEquals("a frame went out on the socket of the old route", listOf(JOIN), sockets[0].sent)
        assertEnded(sockets[0])
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    @Test fun `a socket that opens after the route changed is closed and never joined`() {
        hub.connect()                      // the handshake starts on the direct route
        route = TorRoute.Blocked           // Tor is turned on under it, unheard

        listeners[0].onOpen(sockets[0], switchingProtocols(sockets[0]))

        assertTrue("the socket joined the room on a route no longer in force: ${sockets[0].sent}", sockets[0].sent.isEmpty())
        assertEnded(sockets[0])
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
        assertEquals("our own close was counted as a refusal", HubWebSocket.RECONNECT_FIRST_MS, hub.reconnectDelayMs())
    }

    // ── one socket at a time ──────────────────────────────────────────────────────────────────

    @Test fun `connect while a socket is open ends it before the next is started`() {
        connectAndOpen()

        hub.connect()

        assertEquals(listOf("open 1", "close 1", "cancel 1", "open 2"), events)
        listeners[1].onOpen(sockets[1], switchingProtocols(sockets[1]))
        hub.sendMessage(1, "gm", "sig")
        assertEquals("the replaced socket was sent a frame", listOf(JOIN), sockets[0].sent)
        assertEquals(2, sockets[1].sent.size)
    }

    @Test fun `connect while a socket is still opening ends it before the next is started`() {
        assertTrue(hub.connect())
        assertTrue(hub.connect())
        assertEquals(listOf("open 1", "close 1", "cancel 1", "open 2"), events)
    }

    @Test fun `the late end of a replaced socket does not speak for the new one`() {
        connectAndOpen()
        hub.connect()
        listeners[1].onOpen(sockets[1], switchingProtocols(sockets[1]))

        // The cancelled socket's end arrives after its successor opened.
        listeners[0].onFailure(sockets[0], IOException("Canceled"), null)
        listeners[0].onClosed(sockets[0], 1000, "")

        assertEquals("the replaced socket's end marked the new one down", ConnectionState.CONNECTED, hub.connectionState.value)
        assertEquals("the replaced socket's end was counted as a refusal", HubWebSocket.RECONNECT_FIRST_MS, hub.reconnectDelayMs())
        assertFalse(sockets[1].ended)
    }

    @Test fun `a replaced socket that opens late is closed and never joined`() {
        hub.connect()
        hub.connect()

        listeners[0].onOpen(sockets[0], switchingProtocols(sockets[0]))

        assertTrue("the replaced socket joined the room: ${sockets[0].sent}", sockets[0].sent.isEmpty())
        assertEquals("the replaced socket's opening changed the new one's state", ConnectionState.RECONNECTING, hub.connectionState.value)
    }

    // ── disconnect() ends the socket as a route change or a new connect() does ───────────────

    @Test fun `a socket still opening is down at disconnect and not left for a route change`() {
        hub.connect()                      // started, not open yet
        hub.disconnect()
        route = TorRoute.Blocked
        hub.closeIfRouteChanged()

        assertEquals("disconnect() left the opening socket up on the old route", listOf("open 1", "close 1", "cancel 1"), events)
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    @Test fun `disconnect then connect - the old socket is down before the next is started`() {
        connectAndOpen()

        hub.disconnect()
        hub.connect()

        assertEquals(listOf("open 1", "close 1", "cancel 1", "open 2"), events)
        assertEquals("our own close was counted as a refusal", HubWebSocket.RECONNECT_FIRST_MS, hub.reconnectDelayMs())
    }

    // ── nothing is started while Tor is on and not connected ─────────────────────────────────

    @Test fun `with Tor on and not connected connect starts nothing`() {
        route = TorRoute.Blocked

        assertFalse("connect() said it started a socket", hub.connect())

        assertTrue("a socket was started while Tor was on and not connected: $events", events.isEmpty())
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
        assertEquals("a connect that started nothing was counted as a refusal", HubWebSocket.RECONNECT_FIRST_MS, hub.reconnectDelayMs())
    }

    @Test fun `with Tor on and not connected connect still ends the socket there is`() {
        connectAndOpen()
        route = TorRoute.Blocked

        hub.connect()

        assertEquals(listOf("open 1", "close 1", "cancel 1"), events)
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    @Test fun `once Tor is connected the retry opens the next socket on Tor and joins`() {
        connectAndOpen()
        route = TorRoute.Blocked
        hub.closeIfRouteChanged()
        hub.connect()                      // the retry while Tor starts: nothing
        route = TorRoute.Socks(9050)

        hub.connect()
        listeners[1].onOpen(sockets[1], switchingProtocols(sockets[1]))

        assertEquals(listOf("open 1", "close 1", "cancel 1", "open 2"), events)
        assertEquals(listOf(JOIN), sockets[1].sent)
        assertFalse(hub.closeIfRouteChanged())
        assertEquals(ConnectionState.CONNECTED, hub.connectionState.value)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private fun connectAndOpen() {
        hub.connect()
        val socket = sockets.last()
        listeners.last().onOpen(socket, switchingProtocols(socket))
        assertEquals(listOf(JOIN), socket.sent)
        assertEquals(ConnectionState.CONNECTED, hub.connectionState.value)
    }

    private fun assertEnded(socket: RecordingSocket) {
        assertEquals("socket ${socket.n} was not closed", 1000, socket.closeCode)
        assertTrue("socket ${socket.n} was closed but not cancelled: it stays up until the server answers", socket.cancelled)
    }

    private fun switchingProtocols(socket: WebSocket): Response =
        Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()

    private inner class RecordingSocket(val n: Int) : WebSocket {
        val sent = mutableListOf<String>()
        var closeCode: Int? = null
        var cancelled = false
        val ended get() = closeCode != null || cancelled
        override fun request(): Request = Request.Builder().url(HubWebSocket.WS_URL).build()
        override fun queueSize(): Long = 0L
        /** Recorded even after the end, so a frame handed to an ended socket is seen. */
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            if (closeCode == null) { closeCode = code; events += "close $n" }
            return true
        }
        override fun cancel() {
            if (!cancelled) { cancelled = true; events += "cancel $n" }
        }
    }

    private companion object {
        val JOIN: String = JSONObject().put("type", "join").put("channelId", HubWebSocket.CHAT_CHANNEL_ID).toString()
    }
}
