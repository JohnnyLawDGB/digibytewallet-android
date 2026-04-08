package io.digibyte.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.hub.ForumThread
import io.digibyte.core.hub.Reply
import io.digibyte.core.hub.ThreadDetail
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailScreen(
    threadId: Int,
    onNavigateBack: () -> Unit,
    viewModel: ForumViewModel = hiltViewModel()
) {
    val detailState by viewModel.threadDetailState.collectAsStateWithLifecycle()
    val isReplying by viewModel.isReplying.collectAsStateWithLifecycle()
    val replyError by viewModel.replyError.collectAsStateWithLifecycle()

    // Load thread on entry
    LaunchedEffect(threadId) {
        viewModel.loadThread(threadId)
    }

    var replyText by remember { mutableStateOf("") }
    var showReplyInput by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    val title = (detailState as? ThreadDetailState.Success)
                        ?.detail?.thread?.title ?: "Thread"
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearThreadDetail()
                        onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (detailState is ThreadDetailState.Success) {
                        val thread = (detailState as ThreadDetailState.Success).detail.thread
                        // Upvote thread action in top bar
                        IconButton(onClick = { viewModel.upvoteThread(thread.id) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Upvote",
                                    tint = DigiByteAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = thread.upvotes.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DigiByteAccent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
            )
        },
        bottomBar = {
            if (viewModel.isLoggedIn) {
                ReplyBar(
                    visible = showReplyInput,
                    text = replyText,
                    onTextChange = { replyText = it },
                    isReplying = isReplying,
                    error = replyError,
                    onToggle = {
                        showReplyInput = !showReplyInput
                        viewModel.clearReplyError()
                    },
                    onSend = {
                        viewModel.replyToThread(
                            threadId = threadId,
                            content = replyText.trim(),
                            onSuccess = {
                                replyText = ""
                                showReplyInput = false
                            }
                        )
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = detailState) {
                is ThreadDetailState.Idle, ThreadDetailState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = DigiByteBlue)
                    }
                }

                is ThreadDetailState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = { viewModel.loadThread(threadId) }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is ThreadDetailState.Success -> {
                    ThreadContent(
                        detail = state.detail,
                        onUpvoteThread = { viewModel.upvoteThread(state.detail.thread.id) },
                        onUpvoteReply = { /* upvote reply — future expansion */ }
                    )
                }
            }
        }
    }
}

// ── Thread content ─────────────────────────────────────────────────────────

@Composable
private fun ThreadContent(
    detail: ThreadDetail,
    onUpvoteThread: () -> Unit,
    onUpvoteReply: (Reply) -> Unit
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thread body
        item {
            ThreadBodyCard(thread = detail.thread, onUpvote = onUpvoteThread)
        }

        // Replies header
        if (detail.replies.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${detail.replies.size} ${if (detail.replies.size == 1) "reply" else "replies"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Reply items
        items(detail.replies, key = { "reply_${it.id}" }) { reply ->
            ReplyCard(reply = reply, onUpvote = { onUpvoteReply(reply) })
        }

        // Bottom padding for FAB clearance
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Thread body card ───────────────────────────────────────────────────────

@Composable
private fun ThreadBodyCard(
    thread: ForumThread,
    onUpvote: () -> Unit
) {
    val authorHandle = thread.author.handle
        ?: thread.author.address.let { addr ->
            if (addr.length > 14) "${addr.take(6)}…${addr.takeLast(6)}" else addr
        }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Author + time row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DigiByteBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = authorHandle.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = DigiByteAccent,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(
                        text = authorHandle,
                        style = MaterialTheme.typography.labelMedium,
                        color = DigiByteAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = forumTimeAgo(thread.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Title
            Text(
                text = thread.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Content
            if (thread.content.isNotBlank()) {
                Text(
                    text = thread.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                thickness = 0.5.dp
            )

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${thread.replyCount} replies",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Upvote button
                OutlinedButton(
                    onClick = onUpvote,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp
                    )
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Upvote",
                        tint = DigiByteAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = thread.upvotes.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = DigiByteAccent
                    )
                }
            }
        }
    }
}

// ── Reply card ────────────────────────────────────────────────────────────

@Composable
private fun ReplyCard(
    reply: Reply,
    onUpvote: () -> Unit
) {
    val authorHandle = reply.author.handle
        ?: reply.author.address.let { addr ->
            if (addr.length > 14) "${addr.take(6)}…${addr.takeLast(6)}" else addr
        }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp) // Indent replies slightly
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Author row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(DigiByteBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = authorHandle.take(1).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = DigiByteAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = authorHandle,
                        style = MaterialTheme.typography.labelSmall,
                        color = DigiByteAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = forumTimeAgo(reply.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Upvote button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(DigiByteAccent.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Upvote reply",
                        tint = DigiByteAccent,
                        modifier = Modifier
                            .size(16.dp)
                            .noRippleClickable(onUpvote)
                    )
                    Text(
                        text = reply.upvotes.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = DigiByteAccent,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Reply content
            Text(
                text = reply.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── Reply input bar ────────────────────────────────────────────────────────

@Composable
private fun ReplyBar(
    visible: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    isReplying: Boolean,
    error: String?,
    onToggle: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (visible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= 2000) onTextChange(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Write a reply…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotBlank() && !isReplying) onSend()
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DigiByteBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )

                    IconButton(
                        onClick = { if (text.isNotBlank() && !isReplying) onSend() },
                        enabled = text.isNotBlank() && !isReplying,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (text.isNotBlank() && !isReplying) DigiByteBlue
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        if (isReplying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send reply",
                                tint = if (text.isNotBlank()) Color.White
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                // Collapsed — show "Add a reply" tap target
                TextButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = DigiByteBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Add a reply",
                        color = DigiByteBlue,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

// ── No-ripple click helper ─────────────────────────────────────────────────

private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(
        interactionSource = null,
        indication = null,
        onClick = onClick
    ))
