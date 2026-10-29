package app.aroundtheblock.wallet.ui.hub

import androidx.lifecycle.viewModelScope
import app.aroundtheblock.wallet.core.db.dao.CachedMessageDao
import app.aroundtheblock.wallet.core.db.entity.CachedMessageEntity
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.hub.WebSocketEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The history load ends even when it falls back to the Room cache. The cache is a Room Flow, and a
 * Room Flow never completes: a load that collected it stayed "loading" for the life of the screen,
 * so "load more" never ran again. The stand-in cache here never completes either, as Room's does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryLoadTest {

    private val client = mockk<DigiScopeClient>(relaxed = true)
    private val socket = mockk<HubWebSocket>(relaxed = true)
    private val dao = mockk<CachedMessageDao>(relaxed = true)
    private lateinit var chat: ChatViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { client.isLoggedIn() } returns false
        coEvery { client.getMessages(any(), any(), any()) } returns emptyList()
        every { socket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { socket.messages } returns MutableSharedFlow<WebSocketEvent>()
        every { socket.reconnectDelayMs() } returns 5_000L
        // Like Room's: emits the current rows, then waits for changes and never completes.
        every { dao.getMessagesForChannel(any(), any()) } returns MutableStateFlow(emptyList<CachedMessageEntity>())
        chat = ChatViewModel(client, socket, dao, mockk(relaxed = true))
    }

    @After fun tearDown() {
        chat.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `an empty page and an empty cache end the load, and load more works again`() {
        coVerify(exactly = 1) { client.getMessages(HubWebSocket.CHAT_CHANNEL_ID, null, any()) }

        chat.loadMoreHistory()

        coVerify(exactly = 2) { client.getMessages(HubWebSocket.CHAT_CHANNEL_ID, null, any()) }
    }

    @Test fun `with no network page the cached chat rows are shown, and the load still ends`() {
        val cached = CachedMessageEntity(
            id = 7, channelId = HubWebSocket.CHAT_CHANNEL_ID, content = "gm", fromHandle = "a",
            fromAddress = "Daddress", timestamp = 1_000L, signature = "",
        )
        every { dao.getMessagesForChannel(HubWebSocket.CHAT_CHANNEL_ID, any()) } returns MutableStateFlow(listOf(cached))
        val withCache = ChatViewModel(client, socket, dao, mockk(relaxed = true))
        try {
            assertEquals(listOf("gm"), withCache.currentMessages.value.map { it.content })
            withCache.loadMoreHistory()
            coVerify(atLeast = 3) { client.getMessages(HubWebSocket.CHAT_CHANNEL_ID, null, any()) }
        } finally {
            withCache.viewModelScope.cancel()
        }
    }
}
