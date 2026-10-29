package app.aroundtheblock.wallet.ui.hub

import androidx.lifecycle.viewModelScope
import app.aroundtheblock.wallet.core.db.dao.CachedMessageDao
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.hub.WebSocketEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The chat tries the Hub socket again only after the wait the socket asks for
 * ([HubWebSocket.reconnectDelayMs], which grows while the server refuses), never on a fixed 5 s
 * loop. Driven on virtual time through the real view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReconnectWaitTest {

    private val scheduler = TestCoroutineScheduler()
    private val socket = mockk<HubWebSocket>(relaxed = true)
    private lateinit var chat: ChatViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val client = mockk<DigiScopeClient>(relaxed = true)
        every { client.isLoggedIn() } returns true
        coEvery { client.getMessages(any(), any(), any()) } returns emptyList()
        // The server refuses every connection: the socket stays down and asks for a minute's wait.
        every { socket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { socket.messages } returns MutableSharedFlow<WebSocketEvent>()
        every { socket.reconnectDelayMs() } returns 60_000L
        val dao = mockk<CachedMessageDao>(relaxed = true)
        every { dao.getMessagesForChannel(any()) } returns flowOf(emptyList())
        val identity = mockk<HubIdentity>(relaxed = true)
        chat = ChatViewModel(client, socket, dao, identity)
    }

    @After fun tearDown() {
        chat.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `while the server refuses, the chat tries again only after the wait the socket asks for`() {
        scheduler.runCurrent()
        verify(exactly = 1) { socket.connect() } // on opening the chat

        scheduler.advanceTimeBy(300_000L)
        scheduler.runCurrent()

        // Five minutes at one try a minute: five more, not sixty.
        verify(exactly = 6) { socket.connect() }
    }
}
