package app.aroundtheblock.wallet.core.hub

import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A Hub socket frame is held to a cap of 64 KiB of payload: a frame over it is not parsed or
 * passed on, and the socket it came on is closed (1009, message too big) and torn down, so nothing
 * more is read from it. A chat message is at most 2,000 characters (ChatView), far below the cap.
 *
 * OkHttp hands a frame over only once it is wholly in memory, so the protection is that an
 * oversized frame is not trusted and the connection that sent it ends.
 *
 * Driven through the real [HubWebSocket.connect]: the listener it gives the client is captured and
 * fed frames directly. No socket is opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubSocketFrameCapTest {

    private val cap = 64 * 1024

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    @Test fun `a frame of exactly the cap is read`() = runTest {
        val hub = open()
        val received = collect(hub)
        val frame = messageFrame(cap)
        assertEquals(cap, frame.encodeToByteArray().size)

        hub.listener.onMessage(hub.socket, frame)
        runCurrent()

        assertEquals(1, received.size)
        assertTrue(received.single() is WebSocketEvent.Message)
        assertNull("the socket was closed", hub.socket.closeCode)
        assertFalse("the socket was torn down", hub.socket.cancelled)
        assertEquals(ConnectionState.CONNECTED, hub.hub.connectionState.value)
    }

    @Test fun `a frame one byte over the cap is refused and its socket closed`() = runTest {
        val hub = open()
        val received = collect(hub)

        hub.listener.onMessage(hub.socket, messageFrame(cap + 1))
        runCurrent()

        assertTrue("a frame over the cap was passed on: $received", received.isEmpty())
        assertEquals("the socket was not closed as message-too-big", 1009, hub.socket.closeCode)
        assertTrue("the socket was not torn down", hub.socket.cancelled)
        assertEquals(ConnectionState.DISCONNECTED, hub.hub.connectionState.value)
    }

    /** UTF-8 payload bytes are what is counted: a frame of two-byte characters is over the cap at about half as many characters. */
    @Test fun `the cap counts bytes, not characters`() = runTest {
        val hub = open()
        val received = collect(hub)
        val frame = messageFrame(cap + 1, fill = "é")
        assertTrue("the frame is not shorter in characters than the cap", frame.length < cap)

        hub.listener.onMessage(hub.socket, frame)
        runCurrent()

        assertTrue("a frame over the cap in bytes was passed on", received.isEmpty())
        assertEquals(1009, hub.socket.closeCode)
    }

    @Test fun `nothing more is passed on from a socket that sent a frame over the cap`() = runTest {
        val hub = open()
        val received = collect(hub)

        hub.listener.onMessage(hub.socket, messageFrame(cap + 1))
        hub.listener.onMessage(hub.socket, messageFrame(200))
        runCurrent()

        assertTrue("a frame read after the refusal was passed on: $received", received.isEmpty())
    }

    @Test fun `a binary frame over the cap closes the socket too`() = runTest {
        val hub = open()

        hub.listener.onMessage(hub.socket, ByteArray(cap + 1).toByteString())

        assertEquals(1009, hub.socket.closeCode)
        assertTrue(hub.socket.cancelled)
    }

    /** GUARD: the Hub speaks text; a binary frame within the cap is ignored, as it always was. */
    @Test fun `a binary frame within the cap is ignored`() = runTest {
        val hub = open()
        val received = collect(hub)

        hub.listener.onMessage(hub.socket, ByteArray(16).toByteString())
        runCurrent()

        assertTrue(received.isEmpty())
        assertNull(hub.socket.closeCode)
        assertFalse(hub.socket.cancelled)
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private class Opened(val hub: HubWebSocket, val socket: RecordingSocket, val listener: WebSocketListener)

    /** A connected Hub socket: [HubWebSocket.connect] on a client that captures its listener. */
    private fun open(): Opened {
        val listener = slot<WebSocketListener>()
        val socket = RecordingSocket()
        val client = mockk<OkHttpClient>()
        every { client.newWebSocket(any(), capture(listener)) } returns socket
        val digiScope = mockk<DigiScopeClient>()
        every { digiScope.getToken() } returns "token"

        val hub = HubWebSocket(client, digiScope)
        hub.connect()
        val handshake = Response.Builder()
            .request(socket.request())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        listener.captured.onOpen(socket, handshake)
        return Opened(hub, socket, listener.captured)
    }

    private fun TestScope.collect(opened: Opened): List<WebSocketEvent> {
        val received = mutableListOf<WebSocketEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            opened.hub.messages.collect { received += it }
        }
        return received
    }

    /** A chat message frame whose UTF-8 payload is exactly [bytes] long, its content filled with [fill]. */
    private fun messageFrame(bytes: Int, fill: String = "x"): String {
        val head = """{"type":"message","channelId":1,"id":7,"content":""""
        val tail = """","from":{"handle":"h","address":"Dexample"},"timestamp":1,"signature":""}"""
        var remaining = bytes - (head + tail).encodeToByteArray().size
        require(remaining >= 0)
        val fillBytes = fill.encodeToByteArray().size
        val content = StringBuilder()
        while (remaining >= fillBytes) { content.append(fill); remaining -= fillBytes }
        repeat(remaining) { content.append('x') }
        return head + content + tail
    }

    /** The socket the client hands back, recording how the Hub ended it. */
    private class RecordingSocket : WebSocket {
        var closeCode: Int? = null
        var cancelled = false
        override fun request(): Request = Request.Builder().url(HubWebSocket.WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closeCode = code; return true }
        override fun cancel() { cancelled = true }
    }
}
