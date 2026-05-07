package io.digibyte.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.model.SyncProgressInfo
import io.digibyte.core.model.SyncStage
import io.digibyte.core.model.SyncState
import io.digibyte.core.tor.TorState
import io.digibyte.ui.components.BalanceDisplay
import io.digibyte.ui.components.TransactionItem
import io.digibyte.ui.sync.SyncOverlay
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@Composable
fun WalletScreen(
    onNavigateSend: () -> Unit,
    onNavigateReceive: () -> Unit,
    onNavigateScan: () -> Unit,
    onNavigateTx: (String) -> Unit,
    onNavigateAssets: () -> Unit = {},
    onNavigateGame: () -> Unit = {},
    onScoreSubmit: ((score: Int, distance: Int, coins: Int, livesRemaining: Int) -> Unit)? = null,
    onShowLeaderboard: (() -> Unit)? = null,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val fiatBalance by viewModel.fiatBalance.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    val syncProgressInfo by viewModel.syncProgressInfo.collectAsStateWithLifecycle()
    val reconcileFailed by viewModel.postUpgradeReconcileFailed.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // ── Hero balance card ─────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(DigiByteNavy, DigiByteBlue.copy(alpha = 0.85f))
                        )
                    )
                    .padding(top = 32.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BalanceDisplay(
                        fiatAmount = fiatBalance,
                        dgbAmount = WalletViewModel.formatSatoshis(balance),
                        isSynced = syncState is SyncState.Complete,
                        onFiatTap = { viewModel.cycleCurrency() }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sync state indicator
                    SyncIndicator(syncState)

                    // Tor indicator — only visible when Tor is connected
                    TorIndicator(torState)
                }
            }
        }

        // Failed-reconcile banner: shown when PostUpgradeReconciler fired
        // and couldn't reach the backend. Without this, the user just sees
        // a wrong/stale balance and has no signal that anything went wrong —
        // exact scenario reported on 2026-05-07 ("wallet functional but
        // balance not reflecting"). Banner sits between the balance area
        // and the action row so it's unmissable but not in-flow with txs.
        if (reconcileFailed) {
            item {
                ReconcileFailedBanner(
                    onRetry = { viewModel.retryPostUpgradeReconcile() },
                    onDismiss = { viewModel.dismissReconcileFailedBanner() },
                )
            }
        }

        // ── Action buttons ────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WalletActionButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = "Send",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateSend
                )
                WalletActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Receive",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateReceive
                )
                WalletActionButton(
                    icon = Icons.Default.CropFree,
                    label = "Scan",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateScan
                )
                WalletActionButton(
                    icon = Icons.Default.Stars,
                    label = "Assets",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateAssets
                )
            }
        }

        // ── Verbose sync status card ──────────────────────────────────────
        // Shown while connecting / syncing. Shows current vs target block,
        // ETA based on observed scan rate, count of transactions found so
        // far during this scan, running balance accumulated so far, and
        // an honesty banner about the recovery scan window.
        if (syncProgressInfo.isWorking) {
            item {
                SyncProgressCard(
                    info = syncProgressInfo,
                    runningBalanceDisplay = WalletViewModel.formatSatoshis(syncProgressInfo.runningBalanceSat),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // ── DigiRunner sync game / progress bar ──────────────────────────
        item {
            SyncOverlay(
                syncState = syncState,
                onPlayGame = onNavigateGame,
                onScoreSubmit = onScoreSubmit,
                onShowLeaderboard = onShowLeaderboard,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }

        // ── Price feed card ───────────────────────────────────────────────
        item {
            price?.let { p ->
                PriceFeedCard(
                    priceUsd = p.priceUsd,
                    change24h = p.change24h,
                    source = p.source,
                    pricePhp = p.pricePhp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // ── Recent activity header ────────────────────────────────────────
        item {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── Transaction list ──────────────────────────────────────────────
        if (transactions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transactions yet.\nSend or receive DGB to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(
                items = transactions.take(20),
                key = { it.txid }
            ) { tx ->
                TransactionItem(
                    tx = tx,
                    onClick = { onNavigateTx(tx.txid) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun SyncProgressCard(
    info: SyncProgressInfo,
    runningBalanceDisplay: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Stage-specific headline.
            val (icon, headline) = when (info.stage) {
                SyncStage.Connecting -> Icons.Filled.CloudSync to
                    "Connecting to DigiByte network"
                SyncStage.Syncing -> Icons.Filled.Search to
                    "Scanning for your transactions"
                SyncStage.Synced -> Icons.Filled.CheckCircle to "Up to date"
                SyncStage.Failed -> Icons.Filled.ErrorOutline to "Sync failed"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = DigiByteAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Block X of Y, where data is meaningful.
            if (info.targetBlock > 0 && info.currentBlock > 0) {
                Text(
                    text = "Block ${formatThousands(info.currentBlock)} of ${formatThousands(info.targetBlock)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C5D6)
                )
            }

            // Progress bar — meaningful only during Syncing.
            if (info.stage == SyncStage.Syncing) {
                LinearProgressIndicator(
                    progress = { info.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = DigiByteAccent,
                    trackColor = Color(0xFF273956)
                )
            }

            // ETA + percent line.
            if (info.stage == SyncStage.Syncing) {
                val pct = (info.progressFraction * 100).toInt()
                val eta = info.etaSeconds?.let { formatEta(it) } ?: "estimating…"
                Text(
                    text = "$pct% complete · $eta remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8FA1B8)
                )
            }

            // Running totals — what the scan has surfaced so far.
            if (info.matchCount > 0 || info.runningBalanceSat > 0) {
                Text(
                    text = "Found ${info.matchCount} transaction${if (info.matchCount != 1) "s" else ""}, $runningBalanceDisplay so far",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C5D6)
                )
            }

            // Scan-window honesty banner — only when a recovery date is set
            // AND we're still syncing. Tells the user explicitly that older
            // history isn't included in this scan.
            val recoveryTs = info.recoveryFromTimestamp
            if (recoveryTs != null && info.stage != SyncStage.Synced) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33FFAA00), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFAA00),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Showing transactions from ${formatRecoveryDate(recoveryTs)} onward. " +
                                "Earlier history is not being recovered.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFCC66)
                    )
                }
            }
        }
    }
}

private fun formatThousands(value: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(value)
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "estimating…"
    return when {
        seconds < 60 -> "~${seconds}s"
        seconds < 3600 -> "~${seconds / 60} min"
        else -> {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            if (mins == 0L) "~${hours}h" else "~${hours}h ${mins}m"
        }
    }
}

private fun formatRecoveryDate(unixSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("MMMM yyyy", Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(unixSeconds * 1000))
}

@Composable
private fun WalletActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = DigiByteAccent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PriceFeedCard(
    priceUsd: Double,
    change24h: Double,
    source: String,
    pricePhp: Double = 0.0,
    modifier: Modifier = Modifier
) {
    val priceFormatted = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 6
        maximumFractionDigits = 6
    }.format(priceUsd)

    val changeFormatted = String.format("%.2f", abs(change24h))
    val isPositive = change24h >= 0.0
    val changeColor = if (isPositive) Color(0xFF4CAF50) else Color(0xFFE53935)
    val changePrefix = if (isPositive) "▲" else "▼"

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DGB / USD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$$priceFormatted",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$changePrefix $changeFormatted%",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = changeColor
                )
                Text(
                    text = "via $source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Small badge shown in the wallet header when Tor is actively connected.
 * Shows nothing when Tor is disabled, starting, or failed — keeps the header
 * clean when Tor is not yet fully operational.
 */
@Composable
private fun TorIndicator(torState: TorState) {
    if (torState !is TorState.Connected) return

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .background(
                color = Color(0xFF7C4DFF).copy(alpha = 0.20f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = Icons.Default.VpnLock,
            contentDescription = "Tor active",
            tint = Color(0xFFB39DDB),
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Tor — your IP stays hidden",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB39DDB)
        )
    }
}

@Composable
private fun SyncIndicator(syncState: SyncState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
        catch (e: Exception) { "" }
    }

    // Live peer count — polled alongside balance in WalletViewModel
    val peerCount = remember { mutableIntStateOf(0) }
    val blockHeight = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            peerCount.intValue = io.digibyte.core.bridge.NativeBridge.getPeerCount()
            blockHeight.longValue = io.digibyte.core.bridge.NativeBridge.getLastBlockHeight()
            kotlinx.coroutines.delay(5000L)
        }
    }

    when (syncState) {
        is SyncState.Syncing -> {
            // Header intentionally omits the percent — SyncProgressCard in
            // the body is the single source of sync-progress percent. Two
            // percentages from flows that update on different ticks caused
            // visible bouncing (40% vs 99%) against each other.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = DigiByteAccent
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Syncing · ${peerCount.intValue} peers · Block ${syncState.blockHeight}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent.copy(alpha = 0.85f)
                )
            }
        }
        is SyncState.Complete -> {
            val peers = peerCount.intValue
            val block = blockHeight.longValue
            val v = if (versionName.isNotEmpty()) " · v$versionName" else ""
            val statusText = when {
                peers > 0 && block > 0 -> "Connected · $peers peers · Block $block$v"
                peers > 0 -> "Connected · $peers peers$v"
                else -> "Connected$v"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50).copy(alpha = 0.85f)
            )
        }
        is SyncState.Rescanning -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = DigiByteAccent
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Rescanning · ${peerCount.intValue} peers",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent.copy(alpha = 0.85f)
                )
            }
        }
        is SyncState.Failed -> {
            Text(
                text = "Disconnected · 0 peers",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
        }
        else -> {
            // Idle or between sync states — show whatever info we have
            val peers = peerCount.intValue
            val block = blockHeight.longValue
            val text = when {
                peers > 0 && block > 0 -> "Syncing · $peers peers · Block $block"
                peers > 0 -> "Connecting · $peers peers"
                else -> null
            }
            if (text != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (block > 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = DigiByteAccent
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = DigiByteAccent.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

/**
 * Amber banner shown above the action row when the post-upgrade reconcile
 * couldn't reach the backend. Replaces the silent "wrong balance + no idea
 * why" failure mode reported 2026-05-07.
 */
@androidx.compose.runtime.Composable
private fun ReconcileFailedBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color(0x33FFCC66),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFFFFCC66),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Balance refresh failed",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "We couldn't reach the reconcile node after this upgrade. " +
                       "Your balance shown may be out of date.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = onRetry,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = DigiByteAccent,
                    ),
                ) { Text("Retry now") }
                androidx.compose.material3.OutlinedButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
