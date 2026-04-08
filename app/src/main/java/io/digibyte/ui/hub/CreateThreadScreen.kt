package io.digibyte.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.ui.theme.DigiByteBlue

private const val TITLE_MAX = 120
private const val CONTENT_MAX = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateThreadScreen(
    onNavigateBack: () -> Unit,
    onThreadCreated: (threadId: Int) -> Unit,
    viewModel: ForumViewModel = hiltViewModel()
) {
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val createError by viewModel.createError.collectAsStateWithLifecycle()

    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableStateOf(FORUM_CHANNELS.first()) }
    var channelDropdownExpanded by remember { mutableStateOf(false) }

    val canPost = title.isNotBlank() && content.isNotBlank() && !isCreating

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("New Thread", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.createThread(
                                title = title.trim(),
                                content = content.trim(),
                                channelId = selectedChannel.id,
                                onSuccess = { thread ->
                                    onThreadCreated(thread.id)
                                }
                            )
                        },
                        enabled = canPost
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Post",
                                color = if (canPost) DigiByteBlue else Color(0xFF546E7A),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Error banner ──────────────────────────────────────────────
            createError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearCreateError() },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // ── Channel selector ──────────────────────────────────────────
            ExposedDropdownMenuBox(
                expanded = channelDropdownExpanded,
                onExpandedChange = { channelDropdownExpanded = !channelDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedChannel.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Channel") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            tint = DigiByteBlue
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelDropdownExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DigiByteBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                ExposedDropdownMenu(
                    expanded = channelDropdownExpanded,
                    onDismissRequest = { channelDropdownExpanded = false }
                ) {
                    FORUM_CHANNELS.forEach { channel ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = channel.name,
                                    color = if (channel.id == selectedChannel.id)
                                        DigiByteBlue
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                selectedChannel = channel
                                channelDropdownExpanded = false
                            },
                            trailingIcon = if (channel.id == selectedChannel.id) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = DigiByteBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }

            // ── Title input ───────────────────────────────────────────────
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= TITLE_MAX) title = it },
                    label = { Text("Title") },
                    placeholder = { Text("What's this thread about?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DigiByteBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    isError = title.length >= TITLE_MAX
                )
                if (title.length >= TITLE_MAX - 20) {
                    Text(
                        text = "${title.length} / $TITLE_MAX",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (title.length >= TITLE_MAX)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp)
                    )
                }
            }

            // ── Content input ─────────────────────────────────────────────
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= CONTENT_MAX) content = it },
                    label = { Text("Content") },
                    placeholder = { Text("Share your thoughts…") },
                    minLines = 8,
                    maxLines = 20,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DigiByteBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    isError = content.length >= CONTENT_MAX
                )
                if (content.length >= CONTENT_MAX - 200) {
                    Text(
                        text = "${content.length} / $CONTENT_MAX",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (content.length >= CONTENT_MAX)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp)
                    )
                }
            }

            // ── Signing notice ────────────────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Your thread will be signed with your wallet key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
