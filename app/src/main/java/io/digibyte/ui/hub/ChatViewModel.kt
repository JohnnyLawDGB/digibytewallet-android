package io.digibyte.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.CachedMessageDao
import io.digibyte.core.db.entity.CachedMessageEntity
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.Channel
import io.digibyte.core.hub.ChatMessage
import io.digibyte.core.hub.ConnectionState
import io.digibyte.core.hub.HubWebSocket
import io.digibyte.core.hub.UserInfo
import io.digibyte.core.hub.WebSocketEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val digiScopeClient: DigiScopeClient,
    private val hubWebSocket: HubWebSocket,
    private val cachedMessageDao: CachedMessageDao
) : ViewModel() {

    // ── Channels ──────────────────────────────────────────────────────────
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    // ── Messages ──────────────────────────────────────────────────────────
    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    // ── WebSocket state ───────────────────────────────────────────────────
    val connectionState: StateFlow<ConnectionState> = hubWebSocket.connectionState

    // ── Selected channel ──────────────────────────────────────────────────
    val selectedChannel = MutableStateFlow(1)

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
        loadChannels()
        if (digiScopeClient.isLoggedIn()) {
            hubWebSocket.connect()
        }
        observeWebSocketEvents()
        loadHistory(channelId = 1)
    }

    // ── Channel loading ───────────────────────────────────────────────────

    private fun loadChannels() {
        viewModelScope.launch {
            val fetched = digiScopeClient.getChannels()
            if (fetched.isNotEmpty()) {
                _channels.value = fetched
            } else {
                // Fallback static list — displayed even when not logged in
                _channels.value = listOf(
                    Channel(1, "General",    "General discussion",      "text"),
                    Channel(2, "Trading",    "Price and trading talk",  "text"),
                    Channel(3, "Dev",        "Developer discussion",    "text"),
                    Channel(4, "Assets",     "DigiAssets talk",         "text"),
                    Channel(5, "Ask Enigma", "Chat with the Enigma AI", "bot")
                )
            }
        }
    }

    // ── Channel selection ─────────────────────────────────────────────────

    fun selectChannel(channelId: Int) {
        val old = selectedChannel.value
        if (old == channelId) return

        hubWebSocket.leaveChannel(old)
        selectedChannel.value = channelId
        _currentMessages.value = emptyList()
        oldestTimestamp = null

        hubWebSocket.joinChannel(channelId)
        loadHistory(channelId)
    }

    // ── History loading ───────────────────────────────────────────────────

    private fun loadHistory(channelId: Int) {
        viewModelScope.launch {
            isLoadingHistory = true
            val messages = digiScopeClient.getMessages(channelId, before = oldestTimestamp)
            if (messages.isNotEmpty()) {
                oldestTimestamp = messages.minOf { it.timestamp }
                // Prepend older messages, avoid duplicates by id
                val existing = _currentMessages.value
                val existingIds = existing.map { it.id }.toSet()
                val newMessages = messages.filter { it.id !in existingIds }
                _currentMessages.value = (newMessages + existing).sortedBy { it.timestamp }
                // Cache to Room
                cachedMessageDao.insertAll(messages.map { it.toEntity(channelId) })
            } else if (oldestTimestamp == null) {
                // No network history — try Room cache
                cachedMessageDao.getMessagesForChannel(channelId).collect { cached ->
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
        loadHistory(selectedChannel.value)
    }

    // ── WebSocket event handling ───────────────────────────────────────────

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            hubWebSocket.messages.collect { event ->
                when (event) {
                    is WebSocketEvent.Message -> {
                        if (event.channelId == selectedChannel.value) {
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
                        if (event.channelId == selectedChannel.value) {
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
                        if (event.channelId == selectedChannel.value) {
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

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        _sendError.value = null
        val channelId = selectedChannel.value
        // Sign content — returns null when native stub not available
        val signature = try {
            NativeBridge.signMessage(content, 1) ?: ""
        } catch (e: Exception) {
            ""
        }
        hubWebSocket.sendMessage(channelId, content.trim(), signature)
    }

    fun sendTypingIndicator() {
        hubWebSocket.sendTyping(selectedChannel.value)
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
