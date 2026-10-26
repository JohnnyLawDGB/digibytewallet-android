package app.aroundtheblock.wallet.core.hub

import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.EOFException

/**
 * After a refusal the Hub socket waits longer before the next try, instead of trying every 5 s for
 * as long as the server refuses. A refusal is a connection that failed, or that ended within a
 * minute of opening (the server opens the socket and then closes it when it does not accept the
 * session), or one cut for a frame over the size cap. Each doubles the wait, from 5 s up to
 * 5 minutes; a connection that held for a minute starts the wait over. A server's close is
 * answered at once, so the connection is down then and not when the server gives up on it.
 *
 * Driven through the real [HubWebSocket.connect]: the listener it gives the client is captured and
 * fed the connection's events; the clock is the test's. No socket is opened.
 */
class HubReconnectBackoffTest {

    private var now = 1_000_000L
    private val sockets = mutableListOf<RecordingSocket>()
    private val listeners = mutableListOf<WebSocketListener>()
    private lateinit var hub: HubWebSocket

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        val listener = slot<WebSocketListener>()
        val client = mockk<OkHttpClient>()
        every { client.newWebSocket(any(), capture(listener)) } answers {
            listeners += listener.captured
            RecordingSocket().also { sockets += it }
        }
        val digiScope = mockk<DigiScopeClient>()
        every { digiScope.getToken() } returns "token"
        hub = HubWebSocket(client, digiScope, nowMs = { now })
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private val socket get() = sockets.last()
    private val listener get() = listeners.last()

    private fun open() {
        listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("").build())
    }

    private fun refusedBeforeOpening() {
        hub.connect()
        listener.onFailure(socket, EOFException(), null)
    }

    @Test fun `with nothing refused the socket tries again after 5 seconds`() {
        assertEquals(5_000L, hub.reconnectDelayMs())
    }

    @Test fun `each refusal doubles the wait, up to 5 minutes`() {
        val waits = (1..8).map { refusedBeforeOpening(); hub.reconnectDelayMs() }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), waits)
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    @Test fun `a connection the server closes soon after opening is a refusal, and its close is answered at once`() {
        hub.connect()
        open()
        assertEquals(ConnectionState.CONNECTED, hub.connectionState.value)
        now += 300
        listener.onClosing(socket, 4001, "Invalid or missing authentication")

        assertEquals("the connection is still taken for up", ConnectionState.DISCONNECTED, hub.connectionState.value)
        assertEquals("the server's close was not answered", 1000, socket.closeCode)
        assertEquals(10_000L, hub.reconnectDelayMs())

        // The close completing is the same connection ending: it is not counted again.
        listener.onClosed(socket, 4001, "Invalid or missing authentication")
        assertEquals(10_000L, hub.reconnectDelayMs())
    }

    @Test fun `a refusal after the socket opened keeps doubling the wait`() {
        repeat(3) {
            hub.connect()
            open()
            now += 1_000
            listener.onFailure(socket, EOFException(), null)
        }
        assertEquals(40_000L, hub.reconnectDelayMs())
    }

    @Test fun `a connection that held for a minute starts the wait over`() {
        repeat(3) { refusedBeforeOpening() }
        assertEquals(40_000L, hub.reconnectDelayMs())

        hub.connect()
        open()
        now += 60_000
        listener.onFailure(socket, EOFException(), null)

        assertEquals(5_000L, hub.reconnectDelayMs())
    }

    @Test fun `a connection cut for a frame over the cap is a refusal`() {
        hub.connect()
        open()
        now += 120_000 // held long enough: only the frame makes it a refusal
        listener.onMessage(socket, "x".repeat((HubWebSocket.MAX_FRAME_BYTES + 1).toInt()))

        assertEquals(HubWebSocket.CLOSE_MESSAGE_TOO_BIG, socket.closeCode)
        assertEquals(10_000L, hub.reconnectDelayMs())
    }

    @Test fun `the user's own disconnect is not a refusal`() {
        repeat(2) { refusedBeforeOpening() }
        hub.connect()
        open()
        now += 1_000
        hub.disconnect()
        listener.onClosing(socket, 1000, "User disconnect")
        listener.onClosed(socket, 1000, "User disconnect")

        assertEquals(20_000L, hub.reconnectDelayMs())
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    @Test fun `the wait is the same however often it is asked`() {
        refusedBeforeOpening()
        repeat(3) { assertEquals(10_000L, hub.reconnectDelayMs()) }
        assertNull("the socket closed itself", sockets.first().closeCode)
    }

    /** The socket the client hands back, recording how it was ended. */
    private class RecordingSocket : WebSocket {
        var closeCode: Int? = null
        override fun request(): Request = Request.Builder().url(HubWebSocket.WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { if (closeCode == null) closeCode = code; return true }
        override fun cancel() = Unit
    }
}
