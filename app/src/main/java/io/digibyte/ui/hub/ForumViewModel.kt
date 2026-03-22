package io.digibyte.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.ForumThread
import io.digibyte.core.hub.Reply
import io.digibyte.core.hub.ThreadDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Forum channel definitions (id matches DigiScope hub channel IDs for forum)
data class ForumChannel(val id: Int, val name: String)

val FORUM_CHANNELS = listOf(
    ForumChannel(10, "Announcements"),
    ForumChannel(11, "Proposals"),
    ForumChannel(12, "Support")
)

sealed class ForumUiState {
    data object Loading : ForumUiState()
    data class Success(val threads: List<ForumThread>) : ForumUiState()
    data class Error(val message: String) : ForumUiState()
}

sealed class ThreadDetailState {
    data object Idle : ThreadDetailState()
    data object Loading : ThreadDetailState()
    data class Success(val detail: ThreadDetail) : ThreadDetailState()
    data class Error(val message: String) : ThreadDetailState()
}

@HiltViewModel
class ForumViewModel @Inject constructor(
    private val digiScopeClient: DigiScopeClient
) : ViewModel() {

    // ── Thread list state ─────────────────────────────────────────────────
    private val _uiState = MutableStateFlow<ForumUiState>(ForumUiState.Loading)
    val uiState: StateFlow<ForumUiState> = _uiState.asStateFlow()

    // ── Selected channel ──────────────────────────────────────────────────
    private val _selectedChannel = MutableStateFlow(FORUM_CHANNELS.first())
    val selectedChannel: StateFlow<ForumChannel> = _selectedChannel.asStateFlow()

    // ── Thread detail state ───────────────────────────────────────────────
    private val _threadDetailState = MutableStateFlow<ThreadDetailState>(ThreadDetailState.Idle)
    val threadDetailState: StateFlow<ThreadDetailState> = _threadDetailState.asStateFlow()

    // ── Create thread state ───────────────────────────────────────────────
    private val _createError = MutableStateFlow<String?>(null)
    val createError: StateFlow<String?> = _createError.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    // ── Reply state ───────────────────────────────────────────────────────
    private val _isReplying = MutableStateFlow(false)
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    private val _replyError = MutableStateFlow<String?>(null)
    val replyError: StateFlow<String?> = _replyError.asStateFlow()

    // ── Login state ───────────────────────────────────────────────────────
    val isLoggedIn: Boolean get() = digiScopeClient.isLoggedIn()

    init {
        loadThreads()
    }

    // ── Channel selection ─────────────────────────────────────────────────

    fun selectChannel(channel: ForumChannel) {
        if (_selectedChannel.value.id == channel.id) return
        _selectedChannel.value = channel
        loadThreads()
    }

    // ── Thread loading ────────────────────────────────────────────────────

    fun loadThreads(page: Int = 1) {
        _uiState.value = ForumUiState.Loading
        viewModelScope.launch {
            val channelId = _selectedChannel.value.id
            val threads = digiScopeClient.getThreads(channelId, page)
            _uiState.value = if (threads.isEmpty()) {
                ForumUiState.Success(emptyList())
            } else {
                ForumUiState.Success(threads)
            }
        }
    }

    // ── Thread detail ─────────────────────────────────────────────────────

    fun loadThread(threadId: Int) {
        _threadDetailState.value = ThreadDetailState.Loading
        viewModelScope.launch {
            val detail = digiScopeClient.getThread(threadId)
            _threadDetailState.value = if (detail != null) {
                ThreadDetailState.Success(detail)
            } else {
                ThreadDetailState.Error("Failed to load thread. Check your connection.")
            }
        }
    }

    fun clearThreadDetail() {
        _threadDetailState.value = ThreadDetailState.Idle
    }

    // ── Create thread ─────────────────────────────────────────────────────

    fun createThread(
        title: String,
        content: String,
        channelId: Int,
        onSuccess: (ForumThread) -> Unit
    ) {
        if (title.isBlank() || content.isBlank()) {
            _createError.value = "Title and content are required"
            return
        }
        _createError.value = null
        _isCreating.value = true

        viewModelScope.launch {
            val signContent = "$title\n$content"
            val signature = try {
                NativeBridge.signMessage(signContent, 1) ?: ""
            } catch (e: Exception) {
                ""
            }

            val thread = digiScopeClient.createThread(channelId, title, content, signature)
            _isCreating.value = false

            if (thread != null) {
                // Refresh the list in the background
                loadThreads()
                onSuccess(thread)
            } else {
                _createError.value = "Failed to create thread. You must be logged in."
            }
        }
    }

    fun clearCreateError() {
        _createError.value = null
    }

    // ── Reply ─────────────────────────────────────────────────────────────

    fun replyToThread(
        threadId: Int,
        content: String,
        onSuccess: (Reply) -> Unit
    ) {
        if (content.isBlank()) {
            _replyError.value = "Reply cannot be empty"
            return
        }
        _replyError.value = null
        _isReplying.value = true

        viewModelScope.launch {
            val signature = try {
                NativeBridge.signMessage(content, 1) ?: ""
            } catch (e: Exception) {
                ""
            }

            val reply = digiScopeClient.replyToThread(threadId, content, signature)
            _isReplying.value = false

            if (reply != null) {
                // Re-load thread detail so the new reply is visible
                loadThread(threadId)
                onSuccess(reply)
            } else {
                _replyError.value = "Failed to post reply. You must be logged in."
            }
        }
    }

    fun clearReplyError() {
        _replyError.value = null
    }

    // ── Upvote ────────────────────────────────────────────────────────────

    fun upvoteThread(threadId: Int) {
        viewModelScope.launch {
            digiScopeClient.upvoteThread(threadId)
            // Refresh list or detail depending on current state
            when (val state = _threadDetailState.value) {
                is ThreadDetailState.Success -> loadThread(state.detail.thread.id)
                else -> loadThreads()
            }
        }
    }
}
