package io.digibyte.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.DigiRunnerStats
import io.digibyte.core.hub.LeaderboardEntry
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy

private enum class LeaderboardPeriod(val label: String, val apiValue: String) {
    ALL("All Time", "all"),
    WEEKLY("Weekly", "weekly"),
    DAILY("Daily", "daily")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigiRunnerLeaderboardScreen(
    digiScopeClient: DigiScopeClient,
    onNavigateBack: () -> Unit
) {
    var selectedPeriod by remember { mutableStateOf(LeaderboardPeriod.ALL) }
    var entries by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var stats by remember { mutableStateOf<DigiRunnerStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Fetch leaderboard whenever period changes
    LaunchedEffect(selectedPeriod) {
        isLoading = true
        entries = digiScopeClient.getDigiRunnerLeaderboard(
            period = selectedPeriod.apiValue,
            limit = 20
        )
        isLoading = false
    }

    // Fetch personal stats once
    LaunchedEffect(Unit) {
        stats = digiScopeClient.getDigiRunnerStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DigiRunner Leaderboard") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DigiByteNavy,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ── Period filter chips ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                LeaderboardPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { selectedPeriod = period },
                        label = { Text(period.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DigiByteBlue,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // ── Loading state ────────────────────────────────────────────
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = DigiByteBlue)
                }
            } else if (entries.isEmpty()) {
                // ── Empty state ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No scores yet -- be the first!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // ── Leaderboard rows ─────────────────────────────────────
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    items(entries, key = { "${it.rank}-${it.handle}" }) { entry ->
                        LeaderboardRow(entry)
                    }
                }
            }

            // ── Personal stats card at bottom ────────────────────────────
            stats?.let { s ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DigiByteNavy.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Your Stats",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            PersonalStatItem("Best Score", "${s.bestScore}")
                            PersonalStatItem("Rank", if (s.bestRank > 0) "#${s.bestRank}" else "-")
                            PersonalStatItem("Games", "${s.totalGames}")
                            PersonalStatItem("Coins", "${s.totalCoins}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    val medalColor = when (entry.rank) {
        1 -> Color(0xFFFFD700)  // gold
        2 -> Color(0xFFC0C0C0)  // silver
        3 -> Color(0xFFCD7F32)  // bronze
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val rowBackground = when (entry.rank) {
        1 -> Brush.horizontalGradient(
            listOf(Color(0xFFFFD700).copy(alpha = 0.08f), Color.Transparent)
        )
        2 -> Brush.horizontalGradient(
            listOf(Color(0xFFC0C0C0).copy(alpha = 0.06f), Color.Transparent)
        )
        3 -> Brush.horizontalGradient(
            listOf(Color(0xFFCD7F32).copy(alpha = 0.06f), Color.Transparent)
        )
        else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush = rowBackground, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank
        Text(
            text = "#${entry.rank}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = medalColor,
            modifier = Modifier.width(44.dp)
        )

        // Handle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.handle,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Score
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${entry.score}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = DigiByteBlue
            )
            Text(
                text = "${entry.coins} coins",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PersonalStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = DigiByteAccent
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}
