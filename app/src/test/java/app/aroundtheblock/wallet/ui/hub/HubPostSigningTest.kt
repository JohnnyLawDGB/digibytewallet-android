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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * AND-009 / BB-2026-10-10-superisme #2, through the real view models: what the Hub screens sign is exactly what
 * they send, and it names the destination it is sent to. The text signed is the fixed form
 * ChatSignPayloadTest specifies; here it is checked against what reaches the socket or the client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubPostSigningTest {

    private val header = "DigiScope Hub signed post v1"
    private val signature = "Didentity|c2lnbmF0dXJl"
    private val signed = mutableListOf<String>()
    private val identity = mockk<HubIdentity>()
    private val client = mockk<DigiScopeClient>(relaxed = true)
    private val socket = mockk<HubWebSocket>(relaxed = true)
    private val opened = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { identity.address() } returns "Didentity"
        every { identity.sign(any()) } answers { signed += firstArg<String>(); signature }
        every { client.isLoggedIn() } returns false
        coEvery { client.getMessages(any(), any(), any()) } returns emptyList()
        every { socket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { socket.messages } returns MutableSharedFlow<WebSocketEvent>()
        every { socket.reconnectDelayMs() } returns 5_000L
    }

    @After fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun chat(): ChatViewModel {
        val dao = mockk<CachedMessageDao>(relaxed = true)
        every { dao.getMessagesForChannel(any()) } returns flowOf(emptyList())
        return ChatViewModel(client, socket, dao, identity).also { opened += it }
    }

    @Test fun `a chat message with spaces around it is signed exactly as it is sent`() {
        chat().sendMessage("  gm  ")

        verify { socket.sendMessage(1, "gm", signature) }
        assertEquals(listOf("$header\nkind: chat-message\nchannel: 1\n\ngm"), signed)
    }

    @Test fun `a chat message is signed for the one chat room it is sent to`() {
        chat().sendMessage("gm")

        verify { socket.sendMessage(HubWebSocket.CHAT_CHANNEL_ID, "gm", signature) }
        assertEquals(listOf("$header\nkind: chat-message\nchannel: ${HubWebSocket.CHAT_CHANNEL_ID}\n\ngm"), signed)
    }

    @Test fun `nothing is signed for a post with nothing to send`() {
        chat().sendMessage(" \n ")

        assertEquals(emptyList<String>(), signed)
        verify(exactly = 0) { socket.sendMessage(any(), any(), any()) }
    }
}
