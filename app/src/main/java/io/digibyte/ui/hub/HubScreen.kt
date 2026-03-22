package io.digibyte.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.digibyte.ui.theme.DigiByteBlue

// ── Tab definitions ────────────────────────────────────────────────────────

private enum class HubTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    FORUM("Forum", Icons.Default.Forum),
    PROFILE("Profile", Icons.Default.Person)
}

/**
 * Top-level Hub screen with Chat / Forum / Profile tabs.
 *
 * [onNavigateToThread] and [onNavigateCreateThread] are forwarded from the host
 * NavGraph so that thread detail and create screens can be pushed as top-level
 * destinations (full-screen, with their own back stack entries).
 */
@Composable
fun HubScreen(
    onNavigateToThread: (threadId: Int) -> Unit,
    onNavigateCreateThread: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(HubTab.CHAT) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Tab row ───────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = DigiByteBlue,
            divider = {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    thickness = 0.5.dp
                )
            }
        ) {
            HubTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    selectedContentColor = DigiByteBlue,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Tab content ───────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                HubTab.CHAT -> ChatScreen()
                HubTab.FORUM -> ForumScreen(
                    onNavigateToThread = onNavigateToThread,
                    onNavigateCreateThread = onNavigateCreateThread
                )
                HubTab.PROFILE -> ProfileScreen()
            }
        }
    }
}
