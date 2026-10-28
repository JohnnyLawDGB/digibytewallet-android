package app.aroundtheblock.wallet.core.hub

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the server sends with a chat message (hub-service.js `_shapeMessage`, hub-ws.js broadcasts)
 * is read the same way from the REST history and the socket: `attachment: { url, width, height }
 * | null` and `preview: { url, title, description, site_name, image_url } | null`, plus the socket's
 * `preview` (`{ messageId, preview }`), `deleted` (`{ id }`) and coded `error` events. The frames
 * and rows below are copied from the server's own shapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubChatMediaParsingTest {

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val file = "0123456789abcdef0123456789abcdef.png"
    private val attachmentJson = """{"url":"/api/hub/media/$file","width":640,"height":480}"""
    private val previewJson =
        """{"url":"https://example.org/post","title":"A title","description":"Some words",""" +
            """"site_name":"Example","image_url":"https://example.org/card.jpg"}"""

    // ── REST ───────────────────────────────────────────────────────────────────

    private fun page(vararg rows: String): List<ChatMessage> {
        val server = ScriptedServer { ScriptedServer.Answer(200, """{"messages":[${rows.joinToString(",")}]}""") }
        val hub = DigiScopeClient(server.client(), mockk<Context>(relaxed = true)).apply { setToken("token") }
        return runBlocking { hub.getMessages(1) }
    }

    /** A REST row as `_shapeMessage` returns it: the message row, its author flat on it, then the two parts. */
    private fun row(id: Int, content: String, attachment: String, preview: String) =
        """{"id":$id,"channel_id":1,"user_id":42,"content":"$content","signature":"","deleted":0,""" +
            """"created_at":"2026-10-28 12:00:00","handle":"alice","address":"Dalice",""" +
            """"attachment":$attachment,"preview":$preview}"""

    @Test fun `a REST message with an image and a preview carries both`() {
        val message = page(row(7, "look https://example.org/post", attachmentJson, previewJson)).single()

        assertEquals(ChatAttachment("/api/hub/media/$file", 640, 480), message.attachment)
        assertEquals(
            LinkPreview("https://example.org/post", "A title", "Some words", "Example", "https://example.org/card.jpg"),
            message.preview,
        )
    }

    @Test fun `a REST message with neither carries neither`() {
        val message = page(row(7, "gm", "null", "null")).single()

        assertNull(message.attachment)
        assertNull(message.preview)
        assertEquals("gm", message.content)
    }

    @Test fun `an image-only REST message reads as empty text with its image`() {
        val message = page(row(8, "", attachmentJson, "null")).single()

        assertEquals("", message.content)
        assertEquals(640, message.attachment?.width)
    }

    /** The REST row has no `from`: the author is on the row itself (`handle`, `address`, `user_id`). */
    @Test fun `a REST message's author is read from the row`() {
        val message = page(row(7, "gm", "null", "null")).single()

        assertEquals(UserInfo("alice", "Dalice", 42), message.from)
    }

    /** JSON null is not the text "null" (Android's org.json `optString` reads it as "null"). */
    @Test fun `a preview whose fields are JSON null reads them as absent`() {
        val preview = """{"url":"https://example.org","title":null,"description":null,"site_name":null,"image_url":null}"""
        val message = page(row(7, "https://example.org", "null", preview)).single()

        assertEquals(LinkPreview("https://example.org", null, null, null, null), message.preview)
    }

    @Test fun `a preview or an attachment with no url is no preview or attachment`() {
        val message = page(row(7, "gm", """{"width":1,"height":1}""", """{"title":"t"}""")).single()

        assertNull(message.attachment)
        assertNull(message.preview)
    }

    // ── the socket ─────────────────────────────────────────────────────────────

    /** A socket `message` frame as hub-ws.js `broadcastSavedMessage` sends it. */
    private fun messageFrame(id: Int, content: String, attachment: String, preview: String) =
        """{"type":"message","channelId":1,"id":$id,"content":"$content","attachment":$attachment,""" +
            """"preview":$preview,"from":{"handle":"alice","address":"Dalice","userId":42},""" +
            """"timestamp":1730000000000,"signature":""}"""

    @Test fun `a socket message with an image and a preview carries both`() = runTest {
        val (listener, socket, received) = open()

        listener.onMessage(socket, messageFrame(7, "", attachmentJson, previewJson))
        runCurrent()

        val message = (received.single() as WebSocketEvent.Message).message
        assertEquals("", message.content)
        assertEquals(ChatAttachment("/api/hub/media/$file", 640, 480), message.attachment)
        assertEquals("https://example.org/card.jpg", message.preview?.imageUrl)
        assertEquals(UserInfo("alice", "Dalice", 42), message.from)
    }

    @Test fun `a socket message with neither carries neither`() = runTest {
        val (listener, socket, received) = open()

        listener.onMessage(socket, messageFrame(7, "gm", "null", "null"))
        runCurrent()

        val message = (received.single() as WebSocketEvent.Message).message
        assertNull(message.attachment)
        assertNull(message.preview)
    }

    /** hub-ws.js `broadcastPreview`: the preview is nested, under `preview`, beside `messageId`. */
    @Test fun `a preview event names its message and carries the preview`() = runTest {
        val (listener, socket, received) = open()

        listener.onMessage(socket, """{"type":"preview","channelId":1,"messageId":7,"preview":$previewJson}""")
        runCurrent()

        val event = received.single() as WebSocketEvent.Preview
        assertEquals(1, event.channelId)
        assertEquals(7, event.messageId)
        assertEquals("A title", event.preview?.title)
    }

    /** hub-ws.js `broadcastDeleted`: `{ type: 'deleted', channelId, id }`. */
    @Test fun `a deleted event names the message`() = runTest {
        val (listener, socket, received) = open()

        listener.onMessage(socket, """{"type":"deleted","channelId":1,"id":7}""")
        runCurrent()

        assertEquals(WebSocketEvent.Deleted(1, 7), received.single())
    }

    @Test fun `a refused post's error keeps the server's code and words as they are`() = runTest {
        val (listener, socket, received) = open()
        val words = "Posting in chat needs Trust Level 2. Link a quality X or Telegram account on My Account (/my-account) to get there."

        listener.onMessage(socket, JSONObject().put("type", "error").put("code", "TRUST_L2_REQUIRED").put("message", words).toString())
        listener.onMessage(socket, """{"type":"error","message":"Rate limit exceeded"}""")
        runCurrent()

        assertEquals(
            listOf(WebSocketEvent.Error(words, HubJson.TRUST_L2_REQUIRED), WebSocketEvent.Error("Rate limit exceeded", null)),
            received,
        )
    }

    // ── what is sent ───────────────────────────────────────────────────────────

    /** hub-ws.js `sendMessage` reads `data.attachmentId`, camelCase. */
    @Test fun `an image post's frame carries attachmentId, and a text post's frame does not`() = runTest {
        val opened = open()
        val socket = opened.socket

        opened.hub.sendMessage(1, "", "sig", attachmentId = 42)
        opened.hub.sendMessage(1, "gm", "sig")

        val image = JSONObject(socket.sent[1])
        assertEquals("message", image.getString("type"))
        assertEquals(42, image.getInt("attachmentId"))
        assertEquals("", image.getString("content"))
        assertFalse(image.has("attachment_id"))
        val text = JSONObject(socket.sent[2])
        assertFalse("a text post carries an attachment key: $text", text.has("attachmentId"))
        assertEquals(setOf("type", "channelId", "content", "signature"), text.keys().asSequence().toSet())
    }

    // ── where an image is loaded from ──────────────────────────────────────────

    @Test fun `an attachment path is loaded from the API host`() {
        assertEquals("https://api.digiscope.me/api/hub/media/$file", HubMedia.imageUrl(ChatAttachment("/api/hub/media/$file", 1, 1)))
    }

    @Test fun `anything but a Hub media file name loads nothing`() {
        val refused = listOf(
            "https://evil.example/api/hub/media/$file",
            "//evil.example/api/hub/media/$file",
            "/api/hub/media/../../user/profile",
            "/api/hub/media/$file?x=1",
            "/api/hub/media/0123456789abcdef0123456789abcdef.svg",
            "/api/hub/other/$file",
            "api/hub/media/$file",
            "",
        )
        for (path in refused) assertNull(path, HubMedia.imageUrl(ChatAttachment(path, 1, 1)))
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private data class Opened(
        val listener: WebSocketListener,
        val socket: RecordingSocket,
        val received: List<WebSocketEvent>,
        val hub: HubWebSocket,
    )

    /** A connected Hub socket (through the real [HubWebSocket.connect]) and what it passes on. */
    private fun TestScope.open(): Opened {
        val listener = slot<WebSocketListener>()
        val socket = RecordingSocket()
        val client = mockk<OkHttpClient>()
        every { client.newWebSocket(any(), capture(listener)) } returns socket
        val digiScope = mockk<DigiScopeClient>()
        every { digiScope.getToken() } returns "token"
        val hub = HubWebSocket(client, digiScope)
        hub.connect()
        val handshake = Response.Builder()
            .request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
        listener.captured.onOpen(socket, handshake)
        val received = mutableListOf<WebSocketEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.messages.collect { received += it } }
        assertTrue("the join was not sent", socket.sent.firstOrNull()?.contains("\"join\"") == true)
        return Opened(listener.captured, socket, received, hub)
    }

    private class RecordingSocket : WebSocket {
        val sent = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url(HubWebSocket.WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = sent.add(text)
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }
}
