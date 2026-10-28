package app.aroundtheblock.wallet.ui.hub

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.aroundtheblock.wallet.core.db.dao.CachedMessageDao
import app.aroundtheblock.wallet.core.db.entity.CachedMessageEntity
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.digiscope.DigiScopeProfile
import app.aroundtheblock.wallet.core.digiscope.HubImageUpload
import app.aroundtheblock.wallet.core.digiscope.HubReportResult
import app.aroundtheblock.wallet.core.hub.ChatMessage
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubJson
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.hub.LinkPreview
import app.aroundtheblock.wallet.core.hub.UserInfo
import app.aroundtheblock.wallet.core.hub.WebSocketEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The chat's handling of what the server sends and what it posts, through the real view model:
 * the preview and deleted events, the newest page on every reconnect, history loads that survive
 * a failure and never overlap, the posting gate, image posts and reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaViewModelTest {

    private val client = mockk<DigiScopeClient>(relaxed = true)
    private val socket = mockk<HubWebSocket>(relaxed = true)
    private val dao = mockk<CachedMessageDao>(relaxed = true)
    private val identity = mockk<HubIdentity>()
    private val reader = mockk<HubImageReader>()
    private val events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 16)
    private val connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val signed = mutableListOf<String>()
    private val opened = mutableListOf<ChatViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { client.isLoggedIn() } returns true
        coEvery { client.getMessages(any(), any(), any()) } returns emptyList()
        coEvery { client.getProfile() } returns DigiScopeProfile("me", "Dme", 0, userId = 5, trustLevel = 2, canPost = true)
        every { socket.connectionState } returns connection
        every { socket.messages } returns events
        every { socket.reconnectDelayMs() } returns 5_000L
        every { dao.getMessagesForChannel(any(), any()) } returns flowOf(emptyList<CachedMessageEntity>())
        every { identity.address() } returns "Dme"
        every { identity.sign(any()) } answers { signed += firstArg<String>(); "Dme|sig" }
    }

    @After fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun chat() = ChatViewModel(client, socket, dao, identity, reader).also { opened += it }

    private fun message(id: Int, ts: Long, content: String = "m$id", preview: LinkPreview? = null) =
        ChatMessage(id, content, UserInfo("h", "Dother", 9), ts, "", preview = preview)

    // ── preview and deleted events ─────────────────────────────────────────────

    @Test fun `a preview event fills in its message in place`() {
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 100), message(2, 200, "see https://x.example"))
        val chat = chat()
        val preview = LinkPreview("https://x.example", "X", null, null, null)

        events.tryEmit(WebSocketEvent.Preview(HubWebSocket.CHAT_CHANNEL_ID, 2, preview))

        assertEquals(listOf(1, 2), chat.currentMessages.value.map { it.id })
        assertEquals(preview, chat.currentMessages.value[1].preview)
        assertNull(chat.currentMessages.value[0].preview)
        assertEquals("see https://x.example", chat.currentMessages.value[1].content)
    }

    @Test fun `a deleted event takes the message off the screen and out of the cache`() {
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 100), message(2, 200))
        val chat = chat()

        events.tryEmit(WebSocketEvent.Deleted(HubWebSocket.CHAT_CHANNEL_ID, 2))

        assertEquals(listOf(1), chat.currentMessages.value.map { it.id })
        coVerify(exactly = 1) { dao.deleteById(2) }
    }

    @Test fun `a message deleted while shown from the cache does not come back from it`() {
        // Offline: no network page, so the cache is shown; the deleted row is gone from it.
        val rows = mutableListOf(
            CachedMessageEntity(1, HubWebSocket.CHAT_CHANNEL_ID, "a", "h", "D", 100, ""),
            CachedMessageEntity(2, HubWebSocket.CHAT_CHANNEL_ID, "b", "h", "D", 200, ""),
        )
        every { dao.getMessagesForChannel(any(), any()) } answers { flowOf(rows.toList()) }
        coEvery { dao.deleteById(any()) } answers { rows.removeAll { it.id == firstArg<Int>() } }
        chat().also { events.tryEmit(WebSocketEvent.Deleted(HubWebSocket.CHAT_CHANNEL_ID, 2)) }

        val again = chat()

        assertEquals(listOf(1), again.currentMessages.value.map { it.id })
    }

    // ── deletes ────────────────────────────────────────────────────────────────

    @Test fun `a page already on its way when a delete arrives does not bring the message back`() {
        val page = CompletableDeferred<List<ChatMessage>>()
        coEvery { client.getMessages(any(), null, any()) } coAnswers { page.await() }
        val inserted = mutableListOf<Int>()
        coEvery { dao.insertAll(any()) } answers { inserted += firstArg<List<CachedMessageEntity>>().map { it.id } }
        val chat = chat() // the newest page is now on its way

        events.tryEmit(WebSocketEvent.Deleted(HubWebSocket.CHAT_CHANNEL_ID, 2))
        page.complete(listOf(message(1, 1_000_000), message(2, 1_005_000)))

        assertEquals(listOf(1), chat.currentMessages.value.map { it.id })
        assertFalse("the deleted message was cached again", 2 in inserted)
        coVerify { dao.deleteById(2) }
    }

    @Test fun `a deleted message that is posted again over the socket is not shown`() {
        val chat = chat()
        events.tryEmit(WebSocketEvent.Deleted(HubWebSocket.CHAT_CHANNEL_ID, 2))
        events.tryEmit(WebSocketEvent.Message(HubWebSocket.CHAT_CHANNEL_ID, message(2, 1_005_000)))
        assertTrue(chat.currentMessages.value.isEmpty())
    }

    @Test fun `a message deleted while the socket was down is dropped by the next newest page`() {
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 1_000_000), message(2, 1_005_000))
        val chat = chat()
        assertEquals(listOf(1, 2), chat.currentMessages.value.map { it.id })
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 1_000_000))

        connection.value = ConnectionState.CONNECTED

        assertEquals(listOf(1), chat.currentMessages.value.map { it.id })
        coVerify(exactly = 1) { dao.deleteById(2) }
    }

    @Test fun `messages older than the newest page are not taken for deleted`() {
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 1_000_000), message(2, 1_005_000))
        val chat = chat()
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(2, 1_005_000), message(3, 1_010_000))

        connection.value = ConnectionState.CONNECTED

        assertEquals(listOf(1, 2, 3), chat.currentMessages.value.map { it.id })
        coVerify(exactly = 0) { dao.deleteById(any()) }
    }

    @Test fun `a message that arrives while the newest page is on its way is not taken for deleted`() {
        val page = CompletableDeferred<List<ChatMessage>>()
        coEvery { client.getMessages(any(), null, any()) } coAnswers { page.await() }
        val chat = chat()

        events.tryEmit(WebSocketEvent.Message(HubWebSocket.CHAT_CHANNEL_ID, message(3, 1_010_000)))
        page.complete(listOf(message(1, 1_000_000), message(2, 1_005_000)))

        assertEquals(listOf(1, 2, 3), chat.currentMessages.value.map { it.id })
    }

    // ── paging ─────────────────────────────────────────────────────────────────

    /**
     * The server keeps whole seconds and pages with `created_at < before`. Messages 1 and 2 were
     * posted in the same second; the newest page held only 2. The older page must be asked for
     * from one second past 2, so 1 comes back; asked for from 2 itself, 1 was skipped for good.
     */
    @Test fun `two messages in the same second split across pages are both shown`() {
        val second = 1_700_000_000_000L
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(2, second), message(3, second + 60_000))
        coEvery { client.getMessages(any(), second + 1_000, any()) } returns listOf(message(1, second), message(2, second))
        coEvery { client.getMessages(any(), second, any()) } returns emptyList()
        val chat = chat()

        chat.loadMoreHistory()

        assertEquals(listOf(1, 2, 3), chat.currentMessages.value.map { it.id }.sorted())
    }

    @Test fun `an older page with nothing new ends the paging instead of asking again forever`() {
        val second = 1_700_000_000_000L
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, second), message(2, second))
        coEvery { client.getMessages(any(), second + 1_000, any()) } returns listOf(message(1, second), message(2, second))
        val chat = chat()

        chat.loadMoreHistory()
        chat.loadMoreHistory()
        chat.loadMoreHistory()

        coVerify(exactly = 1) { client.getMessages(any(), second + 1_000, any()) }
    }

    // ── not connected ──────────────────────────────────────────────────────────

    @Test fun `with the socket down a text post is not signed, sent or cleared, and the user is told`() {
        val chat = chat()
        var cleared = 0

        chat.sendMessage("gm") { cleared++ }

        assertTrue(signed.isEmpty())
        verify(exactly = 0) { socket.sendMessage(any(), any(), any(), any()) }
        assertEquals(0, cleared)
        assertEquals(ChatNotice.NOT_CONNECTED, chat.notice.value)
    }

    @Test fun `with the socket down an image is not uploaded and stays attached`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Read(PickedImage(byteArrayOf(1), "image/png"))
        val chat = chat()
        var cleared = 0

        chat.attachImage(uri)
        chat.sendMessage("look") { cleared++ }

        coVerify(exactly = 0) { reader.read(any()) }
        coVerify(exactly = 0) { client.uploadImage(any(), any()) }
        assertEquals(uri, chat.pendingImage.value)
        assertEquals(0, cleared)
        assertEquals(ChatNotice.NOT_CONNECTED, chat.notice.value)
    }

    @Test fun `a socket that drops during the upload sends nothing and keeps the post`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Read(PickedImage(byteArrayOf(1), "image/png"))
        coEvery { client.uploadImage(any(), any()) } coAnswers {
            connection.value = ConnectionState.DISCONNECTED
            HubImageUpload.Uploaded(42, "/api/hub/media/x.png", 1, 1)
        }
        val chat = chat()
        connection.value = ConnectionState.CONNECTED
        var cleared = 0

        chat.attachImage(uri)
        chat.sendMessage("look") { cleared++ }

        verify(exactly = 0) { socket.sendMessage(any(), any(), any(), any()) }
        assertEquals(uri, chat.pendingImage.value)
        assertEquals(0, cleared)
        assertEquals(ChatNotice.NOT_CONNECTED, chat.notice.value)
    }

    // ── reconnect and history ──────────────────────────────────────────────────

    @Test fun `each reconnect fetches the newest page and merges it by id`() {
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 100), message(2, 200))
        val chat = chat()
        chat.loadMoreHistory() // the older page: empty
        val before = mutableListOf<Long?>()
        coEvery { client.getMessages(any(), captureNullable(before), any()) } returns
            listOf(message(2, 200), message(3, 300), message(4, 400))

        connection.value = ConnectionState.CONNECTED
        connection.value = ConnectionState.DISCONNECTED
        connection.value = ConnectionState.CONNECTED

        assertEquals("each reconnect asks for the newest page", listOf<Long?>(null, null), before)
        assertEquals(listOf(1, 2, 3, 4), chat.currentMessages.value.map { it.id })
    }

    @Test fun `a history load that fails ends without an uncaught exception, and the next one runs`() {
        coEvery { client.getMessages(any(), null, any()) } returns listOf(message(1, 100))
        coEvery { dao.insertAll(any()) } throws IllegalStateException("disk full")
        val uncaught = mutableListOf<Throwable>()
        val thread = Thread.currentThread()
        val previous = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val chat = chat()
            chat.loadMoreHistory()

            assertEquals("a failed load escaped the view model (it would end the app)", emptyList<Throwable>(), uncaught)
            coVerify(exactly = 2) { client.getMessages(HubWebSocket.CHAT_CHANNEL_ID, any(), any()) }
            assertEquals(listOf(1), chat.currentMessages.value.map { it.id })
        } finally {
            thread.uncaughtExceptionHandler = previous
        }
    }

    @Test fun `a reconnect waits for a running load, and load more does not start a second`() {
        val gate = CompletableDeferred<List<ChatMessage>>()
        var calls = 0
        coEvery { client.getMessages(any(), any(), any()) } coAnswers { calls++; if (calls == 1) gate.await() else emptyList() }
        val chat = chat() // the first load is now waiting on [gate]

        chat.loadMoreHistory()
        connection.value = ConnectionState.CONNECTED

        assertEquals("a load started while one was running", 1, calls)
        gate.complete(listOf(message(1, 100)))
        assertEquals("the reconnect's newest page did not follow the running load", 2, calls)
    }

    // ── the posting gate ───────────────────────────────────────────────────────

    @Test fun `an account the server does not let post gets the note, not the composer`() {
        coEvery { client.getProfile() } returns DigiScopeProfile("me", "Dme", 0, userId = 5, trustLevel = 1, canPost = false)
        assertEquals(false, chat().canPost.value)
    }

    @Test fun `an account that may post keeps the composer`() {
        assertEquals(true, chat().canPost.value)
    }

    @Test fun `a refusal for the trust level is shown in the server's words and hides the composer`() {
        val chat = chat()
        val words = "Posting in chat needs Trust Level 2. Link a quality X or Telegram account on My Account (/my-account) to get there."

        events.tryEmit(WebSocketEvent.Error(words, HubJson.TRUST_L2_REQUIRED))

        assertEquals(words, chat.sendError.value)
        assertEquals(false, chat.canPost.value)
    }

    // ── image posts ────────────────────────────────────────────────────────────

    @Test fun `an image post uploads, signs the v2 form with its attachment, and sends the attachment id`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Read(PickedImage(byteArrayOf(1, 2), "image/png"))
        coEvery { client.uploadImage(any(), "image/png") } returns HubImageUpload.Uploaded(42, "/api/hub/media/x.png", 1, 1)
        val chat = chat()
        var sent = 0

        connection.value = ConnectionState.CONNECTED
        chat.attachImage(uri)
        chat.sendMessage("  look  ") { sent++ }

        assertEquals(listOf("DigiScope Hub signed post v2\nkind: chat-message\nchannel: 1\nattachment: 42\n\nlook"), signed)
        verify(exactly = 1) { socket.sendMessage(HubWebSocket.CHAT_CHANNEL_ID, "look", "Dme|sig", 42) }
        assertEquals(1, sent)
        assertNull("the image stayed attached after it was posted", chat.pendingImage.value)
    }

    @Test fun `an image alone is posted with empty text`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Read(PickedImage(byteArrayOf(1), "image/jpeg"))
        coEvery { client.uploadImage(any(), any()) } returns HubImageUpload.Uploaded(7, "/api/hub/media/y.jpg", 1, 1)
        val chat = chat()

        connection.value = ConnectionState.CONNECTED
        chat.attachImage(uri)
        chat.sendMessage("")

        verify(exactly = 1) { socket.sendMessage(HubWebSocket.CHAT_CHANNEL_ID, "", "Dme|sig", 7) }
    }

    @Test fun `an upload the server refuses signs and sends nothing, keeps the image, and shows its words`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Read(PickedImage(byteArrayOf(1), "image/png"))
        coEvery { client.uploadImage(any(), any()) } returns HubImageUpload.Failed("Rate limit exceeded", null, 429)
        val chat = chat()
        var sent = 0

        connection.value = ConnectionState.CONNECTED
        chat.attachImage(uri)
        chat.sendMessage("look") { sent++ }

        assertTrue(signed.isEmpty())
        verify(exactly = 0) { socket.sendMessage(any(), any(), any(), any()) }
        assertEquals(0, sent)
        assertEquals(uri, chat.pendingImage.value)
        assertEquals("Rate limit exceeded", chat.sendError.value)
        assertFalse(chat.uploading.value)
    }

    @Test fun `an image too large is refused before any upload`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Refused(PickedImageProblem.TOO_LARGE)
        val chat = chat()

        connection.value = ConnectionState.CONNECTED
        chat.attachImage(uri)
        chat.sendMessage("look")

        coVerify(exactly = 0) { client.uploadImage(any(), any()) }
        assertEquals(ChatNotice.IMAGE_TOO_LARGE, chat.notice.value)
        assertTrue(signed.isEmpty())
    }

    @Test fun `a JPEG that could not be re-encoded is not uploaded, and the user is told`() {
        val uri = mockk<Uri>()
        coEvery { reader.read(uri) } returns HubImageReader.Result.Refused(PickedImageProblem.NOT_PREPARED)
        val chat = chat()

        connection.value = ConnectionState.CONNECTED
        chat.attachImage(uri)
        chat.sendMessage("look")

        coVerify(exactly = 0) { client.uploadImage(any(), any()) }
        verify(exactly = 0) { socket.sendMessage(any(), any(), any(), any()) }
        assertEquals(ChatNotice.IMAGE_NOT_PREPARED, chat.notice.value)
    }

    @Test fun `a text post still sends no attachment`() {
        val chat = chat()
        connection.value = ConnectionState.CONNECTED
        chat.sendMessage("gm")

        verify(exactly = 1) { socket.sendMessage(HubWebSocket.CHAT_CHANNEL_ID, "gm", "Dme|sig", null) }
        assertEquals(listOf("DigiScope Hub signed post v1\nkind: chat-message\nchannel: 1\n\ngm"), signed)
    }

    // ── report ─────────────────────────────────────────────────────────────────

    @Test fun `a report sends the message id and shows the outcome`() {
        coEvery { client.reportMessage(7) } returns HubReportResult.REPORTED
        coEvery { client.reportMessage(8) } returns HubReportResult.GONE
        val chat = chat()

        chat.report(7)
        assertEquals(ChatNotice.REPORTED, chat.notice.value)
        chat.report(8)
        assertEquals(ChatNotice.REPORT_GONE, chat.notice.value)
        coVerify(exactly = 1) { client.reportMessage(7) }
    }

    @Test fun `my own message is known by my account id`() {
        val chat = chat()
        assertTrue(chat.isMine(ChatMessage(1, "gm", UserInfo("me", "Delsewhere", 5), 0, "")))
        assertFalse(chat.isMine(ChatMessage(2, "gm", UserInfo("h", "Dother", 9), 0, "")))
    }
}
