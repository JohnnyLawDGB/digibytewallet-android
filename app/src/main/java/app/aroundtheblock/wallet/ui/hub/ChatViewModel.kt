package app.aroundtheblock.wallet.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.aroundtheblock.wallet.core.db.dao.CachedMessageDao
import app.aroundtheblock.wallet.core.db.entity.CachedMessageEntity
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.ChatMessage
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.hub.HubWebSocket.Companion.CHAT_CHANNEL_ID
import app.aroundtheblock.wallet.core.hub.UserInfo
import app.aroundtheblock.wallet.core.hub.WebSocketEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Hub chat: one room, [CHAT_CHANNEL_ID]. The server closed every other channel (website
 * migration 141), so there is nothing to choose; the socket joins the room each time it opens.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val digiScopeClient: DigiScopeClient,
    private val hubWebSocket: HubWebSocket,
    private val cachedMessageDao: CachedMessageDao,
    private val hubIdentity: HubIdentity,
) : ViewModel() {

    // ── Current user's address (for identifying own messages) ──────────────
    val myAddress: String = hubIdentity.address()

    // ── Messages ──────────────────────────────────────────────────────────
    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    // ── WebSocket state ───────────────────────────────────────────────────
    val connectionState: StateFlow<ConnectionState> = hubWebSocket.connectionState

    // ── Login state ───────────────────────────────────────────────────────
    val isLoggedIn: StateFlow<Boolean> = MutableStateFlow(digiScopeClient.isLoggedIn())
        .also { flow ->
            // Re-evaluate once per second while ViewModel is alive — lightweight enough
            // since isLoggedIn() just reads an in-memory field.
            viewModelScope.launch {
                while (true) {
                    flow.value = digiScopeClient.isLoggedIn()
                    kotlinx.coroutines.delay(2_000)
                }
            }
        }

    // ── Pagination cursor ─────────────────────────────────────────────────
    private var oldestTimestamp: Long? = null
    private var isLoadingHistory = false

    // ── Expose DigiScopeClient for use by composable-layer tip sheet ─────
    internal val digiScopeClientExposed get() = digiScopeClient

    // ── Typing indicators ─────────────────────────────────────────────────
    private val _typingUsers = MutableStateFlow<List<UserInfo>>(emptyList())
    val typingUsers: StateFlow<List<UserInfo>> = _typingUsers.asStateFlow()

    // ── Online count ──────────────────────────────────────────────────────
    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    // ── Send error ────────────────────────────────────────────────────────
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    init {
        if (digiScopeClient.isLoggedIn()) {
            hubWebSocket.connect()
        }
        observeWebSocketEvents()
        loadHistory()
        // Poll for login state — reconnects WebSocket after login or disconnect. Each wait is the
        // socket's own: it grows while the server keeps refusing the socket, and starts over once
        // a connection has held (HubWebSocket.reconnectDelayMs).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(hubWebSocket.reconnectDelayMs().coerceAtLeast(HubWebSocket.RECONNECT_FIRST_MS))
                if (digiScopeClient.isLoggedIn() &&
                    hubWebSocket.connectionState.value == ConnectionState.DISCONNECTED) {
                    hubWebSocket.connect()
                    loadHistory()
                }
            }
        }
    }

    // ── History loading ───────────────────────────────────────────────────

    private fun loadHistory() {
        viewModelScope.launch {
            isLoadingHistory = true
            val messages = digiScopeClient.getMessages(CHAT_CHANNEL_ID, before = oldestTimestamp)
            if (messages.isNotEmpty()) {
                oldestTimestamp = messages.minOf { it.timestamp }
                // Prepend older messages, avoid duplicates by id
                val existing = _currentMessages.value
                val existingIds = existing.map { it.id }.toSet()
                val newMessages = messages.filter { it.id !in existingIds }
                _currentMessages.value = (newMessages + existing).sortedBy { it.timestamp }
                // Cache to Room
                cachedMessageDao.insertAll(messages.map { it.toEntity(CHAT_CHANNEL_ID) })
            } else if (oldestTimestamp == null) {
                // No network history — try Room cache
                cachedMessageDao.getMessagesForChannel(CHAT_CHANNEL_ID).collect { cached ->
                    if (_currentMessages.value.isEmpty()) {
                        _currentMessages.value = cached
                            .map { it.toChatMessage() }
                            .sortedBy { it.timestamp }
                    }
                }
            }
            isLoadingHistory = false
        }
    }

    fun loadMoreHistory() {
        if (isLoadingHistory) return
        loadHistory()
    }

    // ── WebSocket event handling ───────────────────────────────────────────

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            hubWebSocket.messages.collect { event ->
                when (event) {
                    is WebSocketEvent.Message -> {
                        if (event.channelId == CHAT_CHANNEL_ID) {
                            val existing = _currentMessages.value
                            if (existing.none { it.id == event.message.id }) {
                                _currentMessages.value = existing + event.message
                            }
                            // Persist to cache
                            cachedMessageDao.insertAll(
                                listOf(event.message.toEntity(event.channelId))
                            )
                        }
                    }
                    is WebSocketEvent.Typing -> {
                        if (event.channelId == CHAT_CHANNEL_ID) {
                            // Show typing indicator for 3 seconds then clear
                            val current = _typingUsers.value.toMutableList()
                            if (current.none { it.address == event.from.address }) {
                                current.add(event.from)
                                _typingUsers.value = current
                            }
                            launch {
                                kotlinx.coroutines.delay(3_000)
                                _typingUsers.value = _typingUsers.value
                                    .filter { it.address != event.from.address }
                            }
                        }
                    }
                    is WebSocketEvent.OnlineCount -> {
                        if (event.channelId == CHAT_CHANNEL_ID) {
                            _onlineCount.value = event.count
                        }
                    }
                    is WebSocketEvent.Error -> {
                        // Surface WS errors as send errors so UI can display them
                        _sendError.value = event.message
                    }
                }
            }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────

    /**
     * Sends [content] to the chat room, signed with the wallet's identity. The text signed and the
     * text sent are one [ChatSignPayload]: the message exactly as sent, bound to the room it is
     * sent to. The screen calls this only once the spend gate has passed.
     */
    fun sendMessage(content: String) {
        val post = ChatSignPayload.chatMessage(CHAT_CHANNEL_ID, content) ?: return
        _sendError.value = null
        // Sign the post — returns null when native stub not available
        val signature = try {
            hubIdentity.sign(post.signedText) ?: ""
        } catch (e: Exception) {
            ""
        }
        hubWebSocket.sendMessage(post.destination, post.content, signature)
    }

    fun sendTypingIndicator() {
        hubWebSocket.sendTyping(CHAT_CHANNEL_ID)
    }

    fun clearSendError() {
        _sendError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        hubWebSocket.disconnect()
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────

private fun ChatMessage.toEntity(channelId: Int) = CachedMessageEntity(
    id        = id,
    channelId = channelId,
    content   = content,
    fromHandle  = from.handle,
    fromAddress = from.address,
    timestamp = timestamp,
    signature = signature
)

private fun CachedMessageEntity.toChatMessage() = ChatMessage(
    id        = id,
    content   = content,
    from      = UserInfo(handle = fromHandle, address = fromAddress),
    timestamp = timestamp,
    signature = signature
)
