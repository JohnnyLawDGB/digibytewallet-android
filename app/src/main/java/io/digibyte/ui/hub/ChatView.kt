package io.digibyte.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.hub.ChatMessage
import io.digibyte.core.hub.Channel
import io.digibyte.core.hub.ConnectionState
import io.digibyte.core.hub.UserInfo
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ── Enigma bot handle ─────────────────────────────────────────────────────
private const val ENIGMA_HANDLE = "Enigma"
private val EnigmaPurple = Color(0xFF7B2FBE)
private val EnigmaPurpleLight = Color(0xFF9C27B0).copy(alpha = 0.18f)

// ── Max message length (matches backend limit) ────────────────────────────
private const val MAX_MESSAGE_LENGTH = 2000

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val messages by viewModel.currentMessages.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedChannelId by viewModel.selectedChannel.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val typingUsers by viewModel.typingUsers.collectAsStateWithLifecycle()
    val onlineCount by viewModel.onlineCount.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()

    // Tip sheet state
    var tipTarget by remember { mutableStateOf<UserInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // ── Header ────────────────────────────────────────────────────────
        ChatHeader(
            connectionState = connectionState,
            onlineCount = onlineCount,
            selectedChannelId = selectedChannelId,
            channels = channels
        )

        // ── Channel chips ─────────────────────────────────────────────────
        ChannelChipRow(
            channels = channels,
            selectedChannelId = selectedChannelId,
            onChannelSelected = { viewModel.selectChannel(it) }
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant,
            thickness = 0.5.dp
        )

        // ── Message list ──────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            MessageList(
                messages = messages,
                onLoadMore = { viewModel.loadMoreHistory() },
                onTapHandle = { userInfo -> tipTarget = userInfo }
            )

            // Not logged in overlay
            if (!isLoggedIn) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = "Log in with Digi-ID to join the conversation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // ── Typing indicator ──────────────────────────────────────────────
        if (typingUsers.isNotEmpty()) {
            val names = typingUsers.map {
                it.handle ?: it.address.take(8) + "…"
            }.joinToString(", ")
            Text(
                text = "$names is typing…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // ── Send error ────────────────────────────────────────────────────
        sendError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.clearSendError() }
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // ── Input bar ─────────────────────────────────────────────────────
        MessageInputBar(
            enabled = isLoggedIn,
            onSend = { content -> viewModel.sendMessage(content) },
            onTyping = { viewModel.sendTypingIndicator() }
        )
    }

    // ── Tip bottom sheet ──────────────────────────────────────────────────
    tipTarget?.let { target ->
        TipBottomSheet(
            target = target,
            digiScopeClient = viewModel.digiScopeClientExposed,
            onDismiss = { tipTarget = null }
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun ChatHeader(
    connectionState: ConnectionState,
    onlineCount: Int,
    selectedChannelId: Int,
    channels: List<Channel>
) {
    val channelName = channels.find { it.id == selectedChannelId }?.name ?: "Chat"

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Community Hub",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (onlineCount > 0) {
                    Text(
                        text = "#$channelName · $onlineCount online",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "#$channelName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Connection indicator dot
            ConnectionDot(state = connectionState)
        }
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED     -> DigiByteGreen
        ConnectionState.RECONNECTING  -> Color(0xFFFFC107) // amber
        ConnectionState.DISCONNECTED  -> DigiByteRed
    }
    val label = when (state) {
        ConnectionState.CONNECTED    -> "Connected"
        ConnectionState.RECONNECTING -> "Reconnecting"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

// ── Channel chips ─────────────────────────────────────────────────────────

@Composable
private fun ChannelChipRow(
    channels: List<Channel>,
    selectedChannelId: Int,
    onChannelSelected: (Int) -> Unit
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        channels.forEach { channel ->
            val selected = channel.id == selectedChannelId
            FilterChip(
                selected = selected,
                onClick = { onChannelSelected(channel.id) },
                label = {
                    Text(
                        text = if (channel.type == "bot") "✨ ${channel.name}" else channel.name,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = DigiByteBlue,
                    selectedLabelColor = Color.White,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

// ── Message list ──────────────────────────────────────────────────────────

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    onLoadMore: () -> Unit,
    onTapHandle: (UserInfo) -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Load more when the user scrolls to the top
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex) {
        if (firstVisibleIndex == 0 && messages.isNotEmpty()) {
            onLoadMore()
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Be the first to say something!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                onTapHandle = onTapHandle
            )
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onTapHandle: (UserInfo) -> Unit
) {
    val isEnigma = message.from.handle?.equals(ENIGMA_HANDLE, ignoreCase = true) == true

    val bubbleColor = when {
        isEnigma -> EnigmaPurpleLight
        else     -> MaterialTheme.colorScheme.surfaceVariant
    }

    val handleColor = when {
        isEnigma -> EnigmaPurple
        else     -> DigiByteAccent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Enigma bot icon / avatar placeholder
        if (isEnigma) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(EnigmaPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Enigma bot",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            // ── Handle row ────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val handle = message.from.handle
                    ?: message.from.address.let { addr ->
                        if (addr.length > 14) "${addr.take(6)}…${addr.takeLast(6)}" else addr
                    }

                Text(
                    text = handle,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = handleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onTapHandle(message.from) }
                )

                if (isEnigma) {
                    Surface(
                        color = EnigmaPurple.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = EnigmaPurple,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Text(
                    text = timeAgo(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }

            // ── Content bubble ────────────────────────────────────────────
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = if (isEnigma) 0.dp else 8.dp,
                    topEnd = 8.dp,
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                ),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ── Input bar ─────────────────────────────────────────────────────────────

@Composable
private fun MessageInputBar(
    enabled: Boolean,
    onSend: (String) -> Unit,
    onTyping: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val charCount = text.length
    val nearLimit = charCount >= MAX_MESSAGE_LENGTH - 200

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Character counter (only shown when approaching limit)
            if (nearLimit) {
                Text(
                    text = "$charCount / $MAX_MESSAGE_LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (charCount >= MAX_MESSAGE_LENGTH)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, top = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { newVal ->
                        if (newVal.length <= MAX_MESSAGE_LENGTH) {
                            text = newVal
                            if (newVal.isNotEmpty()) onTyping()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = if (enabled) "Message…" else "Log in to chat",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    enabled = enabled,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank() && enabled) {
                            onSend(text)
                            text = ""
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DigiByteBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp)
                )

                IconButton(
                    onClick = {
                        if (text.isNotBlank() && enabled) {
                            onSend(text)
                            text = ""
                        }
                    },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled && text.isNotBlank()) DigiByteBlue
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (enabled && text.isNotBlank()) Color.White
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Time-ago helper ───────────────────────────────────────────────────────

private fun timeAgo(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(1)  -> "just now"
        diff < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        diff < TimeUnit.DAYS.toMillis(7)     -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}

