@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.digibyte.service.SyncService
import io.digibyte.service.SyncService.Companion.OwnNodeHealth
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import io.digibyte.ui.util.openExternalUrl
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.res.stringResource
import io.digibyte.R

/**
 * Setup guide for running a DigiByte node that can serve this wallet.
 *
 * Points at the repo doc rather than a digiscope.me page on purpose: this is the one piece of
 * guidance that should survive the author's infrastructure, since its whole point is helping
 * people stop depending on it. Kept on `develop` so the link is stable across releases.
 */
private const val OWN_NODE_GUIDE_URL =
    "https://github.com/JohnnyLawDGB/digibytewallet-android/blob/develop/docs/run-your-own-node.md"

@Composable
fun NetworkInfoScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
    onScanNode: () -> Unit = {}
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
    val customNodeLabel by viewModel.customNodeLabel.collectAsStateWithLifecycle()
    val customNodeExclusive by viewModel.customNodeExclusive.collectAsStateWithLifecycle()
    val ownNodeHealth by SyncService.ownNodeHealth.collectAsStateWithLifecycle()
    val cfLedgerCounts by viewModel.cfLedgerCounts.collectAsStateWithLifecycle()
    val cfLedgerHoles by viewModel.cfLedgerHoles.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

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
        SyncStage.Connecting -> Triple(stringResource(R.string.ni_state_connecting), DigiByteAccent, Icons.Default.Sync)
        SyncStage.Syncing -> {
            val pct = (frontier.progressFraction * 100).toInt()
            Triple(stringResource(R.string.ni_state_syncing, pct), DigiByteAccent, Icons.Default.Sync)
        }
        SyncStage.Synced -> Triple(stringResource(R.string.ni_state_synced), DigiByteGreen, Icons.Default.CheckCircle)
        SyncStage.Failed -> Triple(stringResource(R.string.ni_state_error), DigiByteRed, Icons.Default.Error)
    }

    val progressValue = frontier.progressFraction

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_network_info), color = Color.White) },
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
            // ── Privacy — Tor + Dandelion (top of the tab) ──────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.ni_privacy)) {
                    val torSubtitle = when (val ts = torState) {
                        is TorState.Disabled -> stringResource(R.string.ni_tor_disabled)
                        is TorState.Starting -> stringResource(R.string.ni_tor_starting)
                        is TorState.Connecting -> stringResource(R.string.ni_tor_connecting)
                        is TorState.Connected -> stringResource(R.string.ni_tor_connected, ts.socksPort)
                        is TorState.Failed -> stringResource(R.string.ni_tor_failed, ts.reason)
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
                        title = stringResource(R.string.ni_tor),
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
                        title = stringResource(R.string.ni_dandelion),
                        subtitle = stringResource(R.string.ni_dandelion_sub),
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
                                text = stringResource(R.string.ni_sync_status),
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
                            label = stringResource(R.string.ni_connected_peers),
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
                            label = stringResource(R.string.ni_synced_block),
                            value = if (cfBlock > 0) numFmt.format(cfBlock) else "—"
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        NetworkStatRow(
                            icon = Icons.Default.Cloud,
                            iconTint = Color(0xFF8899AA),
                            label = stringResource(R.string.ni_chain_height),
                            value = if (chainTip > 0) numFmt.format(chainTip) else "—"
                        )
                        HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                        // Once the CF-gated stage says Synced, report stringResource(R.string.ni_caught_up)
                        // regardless of a 1–100 block tip lead — otherwise the green
                        // stringResource(R.string.ni_state_synced) header would sit next to an amber "3 remaining"
                        // during normal ~15s block propagation (the two rows used
                        // different thresholds). Only actively-behind sync shows a count.
                        val synced = frontier.stage == SyncStage.Synced
                        val remaining = if (synced) 0L
                            else if (chainTip > cfBlock && chainTip > 0) chainTip - cfBlock else 0L
                        NetworkStatRow(
                            icon = Icons.Default.HourglassBottom,
                            iconTint = if (remaining == 0L) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            label = stringResource(R.string.ni_blocks_remaining),
                            value = if (remaining == 0L && (synced || cfBlock > 0)) stringResource(R.string.ni_caught_up) else numFmt.format(remaining)
                        )
                    }
                }
            }

            // ── CF Scan Ledger (Phase-1 observe-only) ────────────────────────
            item {
                val counts = cfLedgerCounts
                var holesExpanded by remember { mutableStateOf(false) }
                SettingsCategory(title = stringResource(R.string.ni_cf_ledger)) {
                    NetworkStatRow(
                        icon = Icons.Default.DoneAll,
                        iconTint = Color(0xFF4CAF50),
                        label = stringResource(R.string.ni_scanned_through),
                        value = if (counts.scannedThrough > 0) numFmt.format(counts.scannedThrough) else "—"
                    )
                    HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                    NetworkStatRow(
                        icon = Icons.Default.HourglassBottom,
                        iconTint = Color(0xFFFF9800),
                        label = stringResource(R.string.ni_outstanding),
                        value = numFmt.format(counts.outstanding)
                    )
                    HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                    NetworkStatRow(
                        icon = Icons.Default.Block,
                        iconTint = DigiByteRed,
                        label = stringResource(R.string.ni_gave_up),
                        value = numFmt.format(counts.gaveUp)
                    )
                    HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                    NetworkStatRow(
                        icon = Icons.Default.Schedule,
                        iconTint = DigiByteAccent,
                        label = stringResource(R.string.ni_pending),
                        value = numFmt.format(counts.pending)
                    )
                    HorizontalDivider(color = Color(0xFF243352), thickness = 0.5.dp, modifier = Modifier.padding(start = 56.dp))
                    // Expandable hole-range list — inclusive [start–end] gaps not yet scanned.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = cfLedgerHoles.isNotEmpty()) { holesExpanded = !holesExpanded }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF8899AA).copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ViewList, null, tint = Color(0xFF8899AA), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.ni_scan_holes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8899AA),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = cfLedgerHoles.size.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        if (cfLedgerHoles.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                if (holesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, tint = Color(0xFF8899AA), modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (holesExpanded && cfLedgerHoles.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Cap the rendered rows so an unexpectedly long list can't
                            // build a giant non-lazy Column inside this LazyColumn item.
                            cfLedgerHoles.take(100).forEach { range ->
                                Text(
                                    text = "• $range",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB0BEC5)
                                )
                            }
                            if (cfLedgerHoles.size > 100) {
                                Text(
                                    text = stringResource(R.string.ni_more_holes, cfLedgerHoles.size - 100),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8899AA)
                                )
                            }
                        }
                    }
                }
            }

            // ── Own node ─────────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.ni_own_node)) {
                    SettingsRow(
                        icon = Icons.Filled.Dns,
                        iconTint = DigiByteAccent,
                        title = stringResource(R.string.ni_use_own_node),
                        subtitle = stringResource(R.string.ni_use_own_node_sub),
                        onClick = {
                            viewModel.setCustomNodeEnabled(!customNodeEnabled)
                        },
                        trailing = {
                            Switch(checked = customNodeEnabled,
                                   onCheckedChange = { viewModel.setCustomNodeEnabled(it) })
                        }
                    )
                    // Deliberately OUTSIDE the `customNodeEnabled` block: the person who most
                    // needs this is the one who does not have a node set up yet, and hiding the
                    // guide behind the toggle would show it only to people who are already done.
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Filled.MenuBook,
                        iconTint = DigiByteAccent,
                        title = stringResource(R.string.ni_setup_guide),
                        subtitle = stringResource(R.string.ni_setup_guide_sub),
                        onClick = { openExternalUrl(context, OWN_NODE_GUIDE_URL) },
                        trailing = {
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = null,
                                tint = DigiByteAccent.copy(alpha = 0.7f)
                            )
                        }
                    )
                    if (customNodeEnabled) {
                        SettingsRowDivider()
                        var draft by remember(customNodeHostPort) { mutableStateOf(customNodeHostPort) }
                        var error by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            TextButton(onClick = onScanNode) {
                                Icon(Icons.Filled.QrCodeScanner, null, tint = DigiByteAccent)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.ni_scan_node_qr))
                            }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it; error = false },
                                singleLine = true,
                                isError = error,
                                label = { Text(stringResource(R.string.ni_node_address)) },
                                placeholder = { Text("10.0.0.5  or  node.example.com:12024") }
                            )
                            if (error) {
                                Text(stringResource(R.string.ni_node_address_err),
                                     color = MaterialTheme.colorScheme.error,
                                     style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                val ok = viewModel.saveCustomNodeHostPort(draft)
                                error = !ok
                                if (ok) viewModel.applyOwnNodeNow()
                            }) {
                                Text(stringResource(R.string.ni_save))
                            }
                            // Live pairing health (SyncService.ownNodeHealth), refreshed on its own
                            // ~30s tick plus whenever this screen's actions (toggle/save/exclusive)
                            // call applyOwnNodeNow() and reconnect.
                            val (healthText, healthColor) = when (ownNodeHealth) {
                                OwnNodeHealth.SERVING    -> stringResource(R.string.ni_node_serving) to DigiByteGreen
                                OwnNodeHealth.CONNECTING -> stringResource(R.string.ni_state_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
                                OwnNodeHealth.DARK       -> stringResource(R.string.ni_node_unreachable) to Color(0xFFFFCC66)
                                OwnNodeHealth.UNPAIRED   -> "" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            if (healthText.isNotEmpty()) {
                                Text(
                                    healthText + (customNodeLabel?.let { "  ·  $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = healthColor
                                )
                            }
                        }
                        SettingsRowDivider()
                        SettingsRow(
                            icon = Icons.Filled.Shield,
                            iconTint = DigiByteAccent,
                            title = stringResource(R.string.ni_exclusive),
                            subtitle = stringResource(R.string.ni_exclusive_sub),
                            onClick = {
                                viewModel.setCustomNodeExclusive(!customNodeExclusive)
                                viewModel.applyOwnNodeNow()
                            },
                            trailing = {
                                Switch(
                                    checked = customNodeExclusive,
                                    onCheckedChange = {
                                        viewModel.setCustomNodeExclusive(it)
                                        viewModel.applyOwnNodeNow()
                                    }
                                )
                            }
                        )
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
