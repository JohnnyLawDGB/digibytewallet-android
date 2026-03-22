package io.digibyte.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.hub.ForumThread
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun ForumScreen(
    onNavigateToThread: (Int) -> Unit,
    onNavigateCreateThread: () -> Unit,
    viewModel: ForumViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedChannel by viewModel.selectedChannel.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (viewModel.isLoggedIn) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateCreateThread,
                    icon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "New thread",
                            tint = Color.White
                        )
                    },
                    text = {
                        Text(
                            "New Thread",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    containerColor = DigiByteBlue
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Channel filter chips ──────────────────────────────────────
            ForumChannelChips(
                channels = FORUM_CHANNELS,
                selectedChannel = selectedChannel,
                onChannelSelected = { viewModel.selectChannel(it) }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                thickness = 0.5.dp
            )

            // ── Thread list ───────────────────────────────────────────────
            when (val state = uiState) {
                is ForumUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = DigiByteBlue)
                    }
                }

                is ForumUiState.Error -> {
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
                            OutlinedButton(onClick = { viewModel.loadThreads() }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is ForumUiState.Success -> {
                    if (state.threads.isEmpty()) {
                        ForumEmptyState(
                            channelName = selectedChannel.name,
                            isLoggedIn = viewModel.isLoggedIn,
                            onCreateThread = onNavigateCreateThread
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(state.threads, key = { it.id }) { thread ->
                                ThreadListItem(
                                    thread = thread,
                                    onClick = { onNavigateToThread(thread.id) }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Channel chips ──────────────────────────────────────────────────────────

@Composable
private fun ForumChannelChips(
    channels: List<ForumChannel>,
    selectedChannel: ForumChannel,
    onChannelSelected: (ForumChannel) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        channels.forEach { channel ->
            val selected = channel.id == selectedChannel.id
            FilterChip(
                selected = selected,
                onClick = { onChannelSelected(channel) },
                label = {
                    Text(
                        text = channel.name,
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

// ── Thread list item ───────────────────────────────────────────────────────

@Composable
private fun ThreadListItem(
    thread: ForumThread,
    onClick: () -> Unit
) {
    val authorHandle = thread.author.handle
        ?: thread.author.address.let { addr ->
            if (addr.length > 14) "${addr.take(6)}…${addr.takeLast(6)}" else addr
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thread upvote count column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.width(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Upvotes",
                tint = DigiByteAccent,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = thread.upvotes.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = DigiByteAccent,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Thread info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = authorHandle,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = forumTimeAgo(thread.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        // Reply count badge
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = "Replies",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = thread.replyCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
    }
}

// ── Empty state ────────────────────────────────────────────────────────────

@Composable
private fun ForumEmptyState(
    channelName: String,
    isLoggedIn: Boolean,
    onCreateThread: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "No threads in $channelName",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isLoggedIn)
                    "Be the first to start a discussion!"
                else
                    "Log in with Digi-ID to post threads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isLoggedIn) {
                Button(
                    onClick = onCreateThread,
                    colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Start a thread")
                }
            }
        }
    }
}

// ── Time-ago helper (shared with forum) ───────────────────────────────────

internal fun forumTimeAgo(timestampMs: Long): String {
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
