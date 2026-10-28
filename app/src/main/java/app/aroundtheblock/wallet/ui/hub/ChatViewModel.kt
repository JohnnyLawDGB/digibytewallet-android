package app.aroundtheblock.wallet.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import app.aroundtheblock.wallet.core.db.dao.CachedMessageDao
import app.aroundtheblock.wallet.core.db.entity.CachedMessageEntity
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.digiscope.HubImageUpload
import app.aroundtheblock.wallet.core.digiscope.HubReportResult
import app.aroundtheblock.wallet.core.hub.ChatMessage
import app.aroundtheblock.wallet.core.hub.HubJson
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.hub.HubWebSocket.Companion.CHAT_CHANNEL_ID
import app.aroundtheblock.wallet.core.hub.UserInfo
import app.aroundtheblock.wallet.core.hub.WebSocketEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** A short, fixed notice the chat shows; the screen words it in the user's language. */
enum class ChatNotice {
    REPORTED, REPORT_GONE, REPORT_LIMITED, REPORT_FAILED,
    IMAGE_UNREADABLE, IMAGE_WRONG_TYPE, IMAGE_TOO_LARGE, UPLOAD_FAILED,
}

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
    private val imageReader: HubImageReader,
) : ViewModel() {

    // ── Current user's address (for identifying own messages) ──────────────
    val myAddress: String = hubIdentity.address()

    /** The signed-in account's server id ([DigiScopeClient.getProfile]), or null until it is known. */
    private val _myUserId = MutableStateFlow<Int?>(null)
    val myUserId: StateFlow<Int?> = _myUserId.asStateFlow()

    /**
     * Whether the server lets this account post (`can_post`, Trust Level 2), or null until the
     * profile is read. Cosmetic only: the server refuses a post or an upload below L2 whatever
     * this says, and its refusal sets this to false.
     */
    private val _canPost = MutableStateFlow<Boolean?>(null)
    val canPost: StateFlow<Boolean?> = _canPost.asStateFlow()

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

    /**
     * Held while a history page loads: one load at a time. "Load more" is dropped while it is held;
     * a reconnect's refresh waits for it, then loads the newest page.
     */
    private val historyLock = Mutex()

    // ── Typing indicators ─────────────────────────────────────────────────
    private val _typingUsers = MutableStateFlow<List<UserInfo>>(emptyList())
    val typingUsers: StateFlow<List<UserInfo>> = _typingUsers.asStateFlow()

    // ── Online count ──────────────────────────────────────────────────────
    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    // ── Send error ────────────────────────────────────────────────────────
    /** The server's own words for a refused post or upload, shown as they are. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private val _notice = MutableStateFlow<ChatNotice?>(null)
    val notice: StateFlow<ChatNotice?> = _notice.asStateFlow()

    // ── The image picked for the next post ────────────────────────────────
    private val _pendingImage = MutableStateFlow<Uri?>(null)
    val pendingImage: StateFlow<Uri?> = _pendingImage.asStateFlow()

    /** True while a picked image is read and uploaded for a post. */
    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    init {
        if (digiScopeClient.isLoggedIn()) {
            hubWebSocket.connect()
        }
        observeWebSocketEvents()
        observeReconnects()
        refreshNewest()
        refreshProfile()
        // Poll for login state — reconnects WebSocket after login or disconnect. Each wait is the
        // socket's own: it grows while the server keeps refusing the socket, and starts over once
        // a connection has held (HubWebSocket.reconnectDelayMs). What was missed while it was down
        // is fetched when the socket is up again (observeReconnects).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(hubWebSocket.reconnectDelayMs().coerceAtLeast(HubWebSocket.RECONNECT_FIRST_MS))
                if (digiScopeClient.isLoggedIn() &&
                    hubWebSocket.connectionState.value == ConnectionState.DISCONNECTED) {
                    hubWebSocket.connect()
                    if (_canPost.value == null) refreshProfile()
                }
            }
        }
    }

    // ── Profile ───────────────────────────────────────────────────────────

    private fun refreshProfile() {
        if (!digiScopeClient.isLoggedIn()) return
        viewModelScope.launch {
            try {
                val profile = digiScopeClient.getProfile() ?: return@launch
                _myUserId.value = profile.userId
                _canPost.value = profile.canPost
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // Not read: the composer stays as it is, and the next reconnect reads it again.
            }
        }
    }

    // ── History loading ───────────────────────────────────────────────────

    /**
     * Each time the socket is up again, the newest page is fetched and merged by id: anything
     * posted while it was down never reached it. Walking one page older instead (as the reconnect
     * did) fetched what was already shown and never what was missed.
     */
    private fun observeReconnects() {
        viewModelScope.launch {
            hubWebSocket.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    historyLock.withLock { loadPage(older = false) }
                    refreshProfile()
                }
            }
        }
    }

    /** The newest page, once any load running now has ended. */
    private fun refreshNewest() {
        viewModelScope.launch { historyLock.withLock { loadPage(older = false) } }
    }

    fun loadMoreHistory() {
        if (!historyLock.tryLock()) return
        viewModelScope.launch {
            try {
                loadPage(older = true)
            } finally {
                historyLock.unlock()
            }
        }
    }

    /**
     * Loads one page and merges it into the chat by id: the newest page, or with [older] the page
     * before the oldest message shown. Nothing it meets ends the screen: a failure is logged and
     * the next load may try again. The cache is read once ([first]): a Room Flow never completes.
     */
    private suspend fun loadPage(older: Boolean) {
        try {
            val messages = digiScopeClient.getMessages(CHAT_CHANNEL_ID, before = if (older) oldestTimestamp else null)
            if (messages.isNotEmpty()) {
                val pageOldest = messages.minOf { it.timestamp }
                oldestTimestamp = oldestTimestamp?.let { minOf(it, pageOldest) } ?: pageOldest
                merge(messages)
                // Cache to Room
                cachedMessageDao.insertAll(messages.map { it.toEntity(CHAT_CHANNEL_ID) })
            } else if (oldestTimestamp == null) {
                // No network history — show the Room cache, read once
                val cached = cachedMessageDao.getMessagesForChannel(CHAT_CHANNEL_ID).first()
                if (_currentMessages.value.isEmpty()) {
                    _currentMessages.value = cached
                        .map { it.toChatMessage() }
                        .sortedBy { it.timestamp }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Not loaded: the chat keeps what it shows, and the next load tries again.
        }
    }

    /** [page] merged into the chat by id: a message already shown is replaced by the page's copy. */
    private fun merge(page: List<ChatMessage>) {
        val byId = LinkedHashMap<Int, ChatMessage>()
        _currentMessages.value.forEach { byId[it.id] = it }
        page.forEach { byId[it.id] = it }
        _currentMessages.value = byId.values.sortedBy { it.timestamp }
    }

    // ── WebSocket event handling ───────────────────────────────────────────

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            hubWebSocket.messages.collect { event ->
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    // One event that could not be applied (a cache write) never ends the stream.
                }
            }
        }
    }

    private suspend fun handle(event: WebSocketEvent) {
        when (event) {
            is WebSocketEvent.Message -> if (event.channelId == CHAT_CHANNEL_ID) onMessage(event.message)
            is WebSocketEvent.Preview -> if (event.channelId == CHAT_CHANNEL_ID) {
                _currentMessages.value = _currentMessages.value.map {
                    if (it.id == event.messageId) it.copy(preview = event.preview) else it
                }
            }
            is WebSocketEvent.Deleted -> if (event.channelId == CHAT_CHANNEL_ID) {
                _currentMessages.value = _currentMessages.value.filter { it.id != event.id }
                // Out of the cache too, or the offline fallback would show it again.
                cachedMessageDao.deleteById(event.id)
            }
            is WebSocketEvent.Typing -> if (event.channelId == CHAT_CHANNEL_ID) onTyping(event.from)
            is WebSocketEvent.OnlineCount -> if (event.channelId == CHAT_CHANNEL_ID) _onlineCount.value = event.count
            is WebSocketEvent.Error -> {
                // The server's words, shown as they are. A refusal for the trust level also
                // swaps the composer for the note on how to reach it.
                _sendError.value = event.message
                if (event.code == HubJson.TRUST_L2_REQUIRED) _canPost.value = false
            }
        }
    }

    private suspend fun onMessage(message: ChatMessage) {
        val existing = _currentMessages.value
        if (existing.none { it.id == message.id }) {
            _currentMessages.value = existing + message
        }
        // Persist to cache
        cachedMessageDao.insertAll(listOf(message.toEntity(CHAT_CHANNEL_ID)))
    }

    /** Shows [from] as typing for 3 seconds. */
    private fun onTyping(from: UserInfo) {
        val current = _typingUsers.value.toMutableList()
        if (current.none { it.address == from.address }) {
            current.add(from)
            _typingUsers.value = current
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3_000)
            _typingUsers.value = _typingUsers.value.filter { it.address != from.address }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────

    /** Picks [uri] (from the system photo picker) to go with the next post. Nothing is read or sent yet. */
    fun attachImage(uri: Uri?) {
        if (uri != null && !_uploading.value) _pendingImage.value = uri
    }

    fun removeImage() {
        if (!_uploading.value) _pendingImage.value = null
    }

    /**
     * Sends [content] to the chat room, signed with the wallet's identity, with the picked image if
     * there is one. The text signed and the text sent are one [ChatSignPayload]: the message
     * exactly as sent, bound to the room it is sent to and, for an image post, to the attachment
     * id the upload answered with. The screen calls this only once the spend gate has passed;
     * the upload is part of this one gated send. [onSent] runs once the post has gone to the socket.
     */
    fun sendMessage(content: String, onSent: () -> Unit = {}) {
        val signAndSend = { post: ChatSignPayload ->
            _sendError.value = null
            // Sign the post — returns null when native stub not available
            val signature = try {
                hubIdentity.sign(post.signedText) ?: ""
            } catch (e: Exception) {
                ""
            }
            hubWebSocket.sendMessage(post.destination, post.content, signature, post.attachmentId)
        }
        val image = _pendingImage.value
        if (image == null) {
            ChatSignPayload.chatMessage(CHAT_CHANNEL_ID, content)?.let { post ->
                signAndSend(post)
                onSent()
            }
        } else if (!_uploading.value) {
            _uploading.value = true
            _sendError.value = null
            _notice.value = null
            viewModelScope.launch {
                try {
                    val post = upload(image)?.let { ChatSignPayload.chatMessage(CHAT_CHANNEL_ID, content, it) }
                    if (post != null) {
                        signAndSend(post)
                        _pendingImage.value = null
                        onSent()
                    }
                } finally {
                    _uploading.value = false
                }
            }
        }
    }

    /** Reads and uploads [image]; its attachment id, or null with the reason shown. */
    private suspend fun upload(image: Uri): Int? {
        val picked = when (val read = imageReader.read(image)) {
            is HubImageReader.Result.Read -> read.image
            is HubImageReader.Result.Refused -> {
                _notice.value = when (read.problem) {
                    PickedImageProblem.UNREADABLE -> ChatNotice.IMAGE_UNREADABLE
                    PickedImageProblem.WRONG_TYPE -> ChatNotice.IMAGE_WRONG_TYPE
                    PickedImageProblem.TOO_LARGE -> ChatNotice.IMAGE_TOO_LARGE
                }
                return null
            }
        }
        return when (val answer = digiScopeClient.uploadImage(picked.bytes, picked.mimeType)) {
            is HubImageUpload.Uploaded -> answer.id
            is HubImageUpload.Failed -> {
                if (answer.code == HubJson.TRUST_L2_REQUIRED) _canPost.value = false
                if (answer.message != null) _sendError.value = answer.message else _notice.value = ChatNotice.UPLOAD_FAILED
                null
            }
        }
    }

    fun sendTypingIndicator() {
        hubWebSocket.sendTyping(CHAT_CHANNEL_ID)
    }

    // ── Report ────────────────────────────────────────────────────────────

    /** Reports message [messageId] to the moderators; the outcome is shown as a [notice]. */
    fun report(messageId: Int) {
        viewModelScope.launch {
            _notice.value = when (digiScopeClient.reportMessage(messageId)) {
                HubReportResult.REPORTED -> ChatNotice.REPORTED
                HubReportResult.GONE -> ChatNotice.REPORT_GONE
                HubReportResult.LIMITED -> ChatNotice.REPORT_LIMITED
                HubReportResult.FAILED -> ChatNotice.REPORT_FAILED
            }
        }
    }

    /** True when [message] is the signed-in account's own ([isOwnMessage]). */
    fun isMine(message: ChatMessage): Boolean = isOwnMessage(message, _myUserId.value, myAddress)

    fun clearSendError() {
        _sendError.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        super.onCleared()
        hubWebSocket.disconnect()
    }
}

/**
 * True when [message] is the signed-in account's own: by the server account id when both sides
 * have one, otherwise by the identity address. Only decides what the screen offers (no Report on
 * your own message); the server decides what a report does.
 */
fun isOwnMessage(message: ChatMessage, myUserId: Int?, myAddress: String): Boolean {
    val theirs = message.from.userId
    if (myUserId != null && theirs != null) return theirs == myUserId
    return myAddress.isNotEmpty() && message.from.address == myAddress
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
