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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 4.1.2 row 8 (N4): a Hub socket sends nothing once its session is gone. Logout forgets the token, but
 * the socket opened with it stayed up and every join / leave / message / typing frame still went out.
 * Now a frame goes out only while a token is held; without one the socket is closed instead.
 * Driven through the real [HubWebSocket.connect], as HubSocketFrameCapTest does; no socket is opened.
 */
class HubSessionEndTest {

    private var token: String? = "token"

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    @Test fun `with a session, frames go out`() {
        val (hub, socket) = open()
        hub.joinChannel(1)
        hub.sendMessage(1, "gm", "sig")
        assertEquals(2, socket.sent.size)
    }

    @Test fun `after logout no frame goes out and the socket is closed`() {
        val (hub, socket) = open()
        token = null                                    // DigiScopeClient.logout() forgot it
        hub.joinChannel(1)
        hub.sendMessage(1, "gm", "sig")
        hub.sendTyping(1)
        hub.leaveChannel(1)
        assertTrue("frames went out with no session: ${socket.sent}", socket.sent.isEmpty())
        assertEquals("the socket was not closed", 1000, socket.closeCode)
        assertEquals(ConnectionState.DISCONNECTED, hub.connectionState.value)
    }

    private fun open(): Pair<HubWebSocket, RecordingSocket> {
        val listener = slot<WebSocketListener>()
        val socket = RecordingSocket()
        val client = mockk<OkHttpClient>()
        every { client.newWebSocket(any(), capture(listener)) } returns socket
        val digiScope = mockk<DigiScopeClient>()
        every { digiScope.getToken() } answers { token }
        val hub = HubWebSocket(client, digiScope)
        hub.connect()
        listener.captured.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        // The join sent on open is HubJoinOnOpenTest's subject; these tests count what follows it.
        socket.sent.clear()
        return hub to socket
    }

    private class RecordingSocket : WebSocket {
        val sent = mutableListOf<String>()
        var closeCode: Int? = null
        override fun request(): Request = Request.Builder().url(HubWebSocket.WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closeCode = code; return true }
        override fun cancel() {}
    }
}
