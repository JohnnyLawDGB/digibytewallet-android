package io.digibyte.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.DigiRunnerStats
import io.digibyte.core.hub.LeaderboardEntry
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy

// ── Tab definitions ────────────────────────────────────────────────────────

private enum class HubTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    FORUM("Forum", Icons.Default.Forum),
    GAMES("Games", Icons.Default.SportsEsports),
    PROFILE("Profile", Icons.Default.Person)
}

/**
 * Top-level Hub screen with Chat / Forum / Games / Profile tabs.
 *
 * [onNavigateToThread] and [onNavigateCreateThread] are forwarded from the host
 * NavGraph so that thread detail and create screens can be pushed as top-level
 * destinations (full-screen, with their own back stack entries).
 */
@Composable
fun HubScreen(
    onNavigateToThread: (threadId: Int) -> Unit,
    onNavigateCreateThread: () -> Unit,
    onPlayDigiRunner: () -> Unit = {},
    onViewLeaderboard: () -> Unit = {},
    digiScopeClient: DigiScopeClient? = null
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
                HubTab.GAMES -> DigiRunnerSection(
                    onPlayDigiRunner = onPlayDigiRunner,
                    onViewLeaderboard = onViewLeaderboard,
                    digiScopeClient = digiScopeClient
                )
                HubTab.PROFILE -> ProfileScreen()
            }
        }
    }
}

// ── DigiRunner section inside the Games tab ──────────────────────────────

@Composable
private fun DigiRunnerSection(
    onPlayDigiRunner: () -> Unit,
    onViewLeaderboard: () -> Unit,
    digiScopeClient: DigiScopeClient?
) {
    var stats by remember { mutableStateOf<DigiRunnerStats?>(null) }
    var topEntries by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (digiScopeClient != null) {
            stats = digiScopeClient.getDigiRunnerStats()
            topEntries = digiScopeClient.getDigiRunnerLeaderboard(limit = 3)
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Hero play button ──────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DigiByteNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = DigiByteAccent,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DigiRunner",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "Run, jump, and collect DGB coins!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onPlayDigiRunner,
                    colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play Now", color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Personal best ──────────────────────────────────────────────
        if (stats != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Your Stats",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("Best Score", "${stats!!.bestScore}")
                        StatItem("Rank", if (stats!!.bestRank > 0) "#${stats!!.bestRank}" else "-")
                        StatItem("Games", "${stats!!.totalGames}")
                        StatItem("Coins", "${stats!!.totalCoins}")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (!isLoading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = "Play a game and submit your score to see your stats!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Top 3 leaderboard preview ──────────────────────────────────
        if (topEntries.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Top Players",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    topEntries.forEach { entry ->
                        LeaderboardPreviewRow(entry)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Full leaderboard link ──────────────────────────────────────
        TextButton(onClick = onViewLeaderboard) {
            Text(
                text = "View Full Leaderboard",
                color = DigiByteBlue,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = DigiByteBlue
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LeaderboardPreviewRow(entry: LeaderboardEntry) {
    val medalColor = when (entry.rank) {
        1 -> Color(0xFFFFD700)  // gold
        2 -> Color(0xFFC0C0C0)  // silver
        3 -> Color(0xFFCD7F32)  // bronze
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#${entry.rank}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = medalColor,
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = entry.handle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${entry.score}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = DigiByteBlue
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${entry.coins} coins",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
