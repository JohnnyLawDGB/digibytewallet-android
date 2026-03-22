@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.core.model.SyncState
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import java.text.NumberFormat
import java.util.Locale

@Composable
fun NetworkInfoScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val syncState by viewModel.syncState.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val lastBlock by viewModel.lastBlockHeight.collectAsState()
    val estimatedHeight by viewModel.estimatedHeight.collectAsState()

    // Refresh network stats when this screen is opened
    LaunchedEffect(Unit) { viewModel.refreshNetworkStats() }

    val numFmt = remember { NumberFormat.getNumberInstance(Locale.US) }

    val (statusLabel, statusColor, statusIcon) = when (syncState) {
        is SyncState.Idle -> Triple("Idle", Color(0xFF8899AA), Icons.Default.CloudOff)
        is SyncState.Syncing -> {
            val s = syncState as SyncState.Syncing
            val pct = (s.progress * 100).toInt()
            Triple("Syncing ($pct%)", DigiByteAccent, Icons.Default.Sync)
        }
        is SyncState.Complete -> Triple("Synced", DigiByteGreen, Icons.Default.CheckCircle)
        is SyncState.Failed -> Triple("Error", DigiByteRed, Icons.Default.Error)
        is SyncState.ChainSplit -> Triple("Chain Split!", DigiByteRed, Icons.Default.Warning)
    }

    val progressValue = (syncState as? SyncState.Syncing)?.progress ?: if (syncState is SyncState.Complete) 1f else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Info", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshNetworkStats() }) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
            )
        },
        containerColor = Color(0xFF0A1628)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // ── Sync status card ─────────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(24.dp))
                            Text(
                                text = "Sync Status",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF8899AA)
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (syncState is SyncState.Syncing) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = DigiByteAccent,
                                trackColor = Color(0xFF243352)
                            )
                        }

                        if (syncState is SyncState.Failed) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = (syncState as SyncState.Failed).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = DigiByteRed
                            )
                        }
                        if (syncState is SyncState.ChainSplit) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = (syncState as SyncState.ChainSplit).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = DigiByteRed
                            )
                        }
                    }
                }
            }

            // ── Stats rows ───────────────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        NetworkStatRow(
                            icon = Icons.Default.People,
                            iconTint = DigiByteAccent,
                            label = "Connected Peers",
                            value = peerCount.toString()
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        NetworkStatRow(
                            icon = Icons.Default.Block,
                            iconTint = Color(0xFF4CAF50),
                            label = "Last Synced Block",
                            value = if (lastBlock > 0) numFmt.format(lastBlock) else "—"
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        NetworkStatRow(
                            icon = Icons.Default.Cloud,
                            iconTint = Color(0xFF8899AA),
                            label = "Estimated Chain Height",
                            value = if (estimatedHeight > 0) numFmt.format(estimatedHeight) else "—"
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        val remaining = if (estimatedHeight > lastBlock && estimatedHeight > 0)
                            estimatedHeight - lastBlock else 0L
                        NetworkStatRow(
                            icon = Icons.Default.HourglassBottom,
                            iconTint = if (remaining == 0L) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            label = "Blocks Remaining",
                            value = if (remaining == 0L && lastBlock > 0) "Caught up" else numFmt.format(remaining)
                        )
                    }
                }
            }

            // ── Tor (Phase 2) ────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Privacy") {
                    SettingsRow(
                        icon = Icons.Default.VpnLock,
                        iconTint = Color(0xFF7C4DFF),
                        title = "Tor Routing",
                        subtitle = "Coming in Phase 2",
                        onClick = {},
                        trailing = {
                            Switch(
                                checked = false,
                                onCheckedChange = null,
                                enabled = false
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkStatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconTint.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8899AA),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}
