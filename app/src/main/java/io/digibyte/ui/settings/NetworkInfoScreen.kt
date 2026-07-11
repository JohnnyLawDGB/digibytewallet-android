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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.core.model.SyncStage
import io.digibyte.core.model.SyncState
import io.digibyte.core.tor.TorState
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
    // CF-gated sync frontier — the SAME source the main wallet screen uses, so
    // the two screens can never disagree. syncState is kept only for the
    // Failed / ChainSplit detail message (SyncStage doesn't carry the text).
    val frontier by viewModel.syncFrontier.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val peerCount by viewModel.peerCount.collectAsStateWithLifecycle()
    val torEnabled by viewModel.torEnabled.collectAsStateWithLifecycle()
    val dandelionEnabled by viewModel.dandelionEnabled.collectAsStateWithLifecycle()
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    val customNodeEnabled by viewModel.customNodeEnabled.collectAsStateWithLifecycle()
    val customNodeHostPort by viewModel.customNodeHostPort.collectAsStateWithLifecycle()

    // Refresh network stats when this screen is opened
    // Poll live stats while the screen is visible so the display self-corrects a
    // transient read (e.g. a momentary 0 during peer churn) instead of freezing a
    // one-shot snapshot that diverges from the main screen. Read-only — this never
    // mutates sync state (see SettingsViewModel.refreshNetworkStats). The loop is
    // cancelled automatically when the screen leaves composition.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshNetworkStats()
            kotlinx.coroutines.delay(3000)
        }
    }

    val numFmt = remember { NumberFormat.getNumberInstance(Locale.US) }

    val (statusLabel, statusColor, statusIcon) = when (frontier.stage) {
        SyncStage.Connecting -> Triple("Connecting…", DigiByteAccent, Icons.Default.Sync)
        SyncStage.Syncing -> {
            val pct = (frontier.progressFraction * 100).toInt()
            Triple("Syncing ($pct%)", DigiByteAccent, Icons.Default.Sync)
        }
        SyncStage.Synced -> Triple("Synced", DigiByteGreen, Icons.Default.CheckCircle)
        SyncStage.Failed -> Triple("Error", DigiByteRed, Icons.Default.Error)
    }

    val progressValue = frontier.progressFraction

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

                        if (frontier.stage == SyncStage.Syncing) {
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
                        // CF frontier — the functional sync height (compact-filter
                        // chain tip), NOT the header height. This is what drives
                        // tx/deposit detection, so it's the honest "synced to here".
                        val cfBlock = frontier.currentBlock
                        val chainTip = frontier.targetBlock
                        NetworkStatRow(
                            icon = Icons.Default.Block,
                            iconTint = Color(0xFF4CAF50),
                            label = "Synced Block (filters)",
                            value = if (cfBlock > 0) numFmt.format(cfBlock) else "—"
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        NetworkStatRow(
                            icon = Icons.Default.Cloud,
                            iconTint = Color(0xFF8899AA),
                            label = "Chain Height",
                            value = if (chainTip > 0) numFmt.format(chainTip) else "—"
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        // Once the CF-gated stage says Synced, report "Caught up"
                        // regardless of a 1–100 block tip lead — otherwise the green
                        // "Synced" header would sit next to an amber "3 remaining"
                        // during normal ~15s block propagation (the two rows used
                        // different thresholds). Only actively-behind sync shows a count.
                        val synced = frontier.stage == SyncStage.Synced
                        val remaining = if (synced) 0L
                            else if (chainTip > cfBlock && chainTip > 0) chainTip - cfBlock else 0L
                        NetworkStatRow(
                            icon = Icons.Default.HourglassBottom,
                            iconTint = if (remaining == 0L) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            label = "Blocks Remaining",
                            value = if (remaining == 0L && (synced || cfBlock > 0)) "Caught up" else numFmt.format(remaining)
                        )
                    }
                }
            }

            // ── Tor Privacy ──────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Privacy") {
                    val torSubtitle = when (val ts = torState) {
                        is TorState.Disabled -> "Disabled — connecting directly"
                        is TorState.Starting -> "Starting…"
                        is TorState.Connecting -> "Connecting to Tor network…"
                        is TorState.Connected -> "Connected via port ${ts.socksPort}"
                        is TorState.Failed -> "Failed: ${ts.reason}"
                    }
                    val torSubtitleColor = when (torState) {
                        is TorState.Connected -> DigiByteGreen
                        is TorState.Failed -> DigiByteRed
                        is TorState.Starting, is TorState.Connecting -> DigiByteAccent
                        else -> Color(0xFF8899AA)
                    }

                    SettingsRow(
                        icon = Icons.Default.VpnLock,
                        iconTint = Color(0xFF7C4DFF),
                        title = "Tor Routing",
                        subtitle = torSubtitle,
                        subtitleColor = torSubtitleColor,
                        onClick = { viewModel.setTorEnabled(!torEnabled) },
                        trailing = {
                            Switch(
                                checked = torEnabled,
                                onCheckedChange = { viewModel.setTorEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF7C4DFF)
                                )
                            )
                        }
                    )

                    SettingsRow(
                        icon = Icons.Default.AltRoute,
                        iconTint = Color(0xFF26A69A),
                        title = "Dandelion broadcast",
                        subtitle = "Hides which peer first saw your transaction. Falls back " +
                            "to a normal broadcast if no Dandelion node is reachable.",
                        onClick = { viewModel.setDandelionEnabled(!dandelionEnabled) },
                        trailing = {
                            Switch(
                                checked = dandelionEnabled,
                                onCheckedChange = { viewModel.setDandelionEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF26A69A)
                                )
                            )
                        }
                    )
                }
            }

            // ── Own node ─────────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Own node") {
                    SettingsRow(
                        icon = Icons.Filled.Dns,
                        iconTint = DigiByteAccent,
                        title = "Use my own node",
                        subtitle = "Sync only through your DigiByte node (compact filters). " +
                                   "Your node must run peerblockfilters=1. Applies on next app restart.",
                        onClick = { viewModel.setCustomNodeEnabled(!customNodeEnabled) },
                        trailing = {
                            Switch(checked = customNodeEnabled,
                                   onCheckedChange = { viewModel.setCustomNodeEnabled(it) })
                        }
                    )
                    if (customNodeEnabled) {
                        SettingsRowDivider()
                        var draft by remember(customNodeHostPort) { mutableStateOf(customNodeHostPort) }
                        var error by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it; error = false },
                                singleLine = true,
                                isError = error,
                                label = { Text("Node address (host or host:port)") },
                                placeholder = { Text("10.0.0.5  or  node.example.com:12024") }
                            )
                            if (error) {
                                Text("Enter a valid host, optionally :port (1–65535). IPv6/URLs not supported.",
                                     color = MaterialTheme.colorScheme.error,
                                     style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { error = !viewModel.saveCustomNodeHostPort(draft) }) {
                                Text("Save")
                            }
                            // Coarse reachability signal (full "your node is serving filters" check is a
                            // follow-up needing a native CF-peer-census accessor).
                            Text(
                                if (peerCount > 0) "Connected · $peerCount peer(s)"
                                else "Not connected — restart the app after saving; check your node is reachable and serving filters.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
