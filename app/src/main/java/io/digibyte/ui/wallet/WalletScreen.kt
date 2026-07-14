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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.isTestnet
import io.digibyte.core.model.SyncProgressInfo
import io.digibyte.core.model.SyncStage
import io.digibyte.core.tor.TorState
import io.digibyte.service.SyncService.Companion.OwnNodeHealth
import io.digibyte.ui.components.BalanceDisplay
import io.digibyte.ui.components.TransactionItem
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onNavigateSend: () -> Unit,
    onNavigateReceive: () -> Unit,
    onNavigateScan: () -> Unit,
    onNavigateTx: (String) -> Unit,
    onNavigateAssets: () -> Unit = {},
    onNavigateNetworkInfo: () -> Unit = {},
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val ddBalance by viewModel.ddBalance.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val fiatBalance by viewModel.fiatBalance.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    val syncProgressInfo by viewModel.syncProgressInfo.collectAsStateWithLifecycle()
    val reconcileFailed by viewModel.postUpgradeReconcileFailed.collectAsStateWithLifecycle()
    val bloomFallback by viewModel.bloomFallbackActive.collectAsStateWithLifecycle()
    val torFailure by viewModel.torFailureActive.collectAsStateWithLifecycle()
    val ownNodeHealth by viewModel.ownNodeHealth.collectAsStateWithLifecycle()

    // Runtime network selection (Task 6, dev-gated toggle in Settings > Advanced).
    // Read once — a restart is required to change it (see SettingsViewModel
    // .setNetworkTestnet), so it can never change within a running process.
    val context = LocalContext.current
    val onTestnet = remember { isTestnet(context) }

    // Report RESUMED state so the poll loop's active-screen readiness nudge only
    // fires while the user is actually looking at this screen — never in the
    // background or behind a pushed-over destination. Pairs with [shouldWakePeers].
    LifecycleResumeEffect(viewModel) {
        viewModel.setScreenActive(true)
        onPauseOrDispose { viewModel.setScreenActive(false) }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
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
                    if (onTestnet) {
                        TestnetBadge()
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    BalanceDisplay(
                        fiatAmount = fiatBalance,
                        dgbAmount = WalletViewModel.formatSatoshis(balance),
                        // Always give DigiDollar a place on the hero — shows $0.00
                        // when none is held (rather than hiding the pill).
                        ddAmount = WalletViewModel.formatDigiDollar(ddBalance),
                        // CF-gated — matches the sync card/overlay, never "synced"
                        // while cfheaders is still catching up.
                        isSynced = syncProgressInfo.stage == SyncStage.Synced,
                        onFiatTap = { viewModel.cycleCurrency() }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sync state indicator — fed from syncProgressInfo (the
                    // same source the inline card uses) so the two can never
                    // disagree about block / progress / target.
                    SyncIndicator(syncProgressInfo)

                    // Tor indicator — only visible when Tor is connected
                    TorIndicator(torState)
                }
            }
        }

        // Failed-reconcile banner: shown ONLY when there's evidence the
        // displayed balance might actually be wrong. Reports 2026-05-08 of
        // the banner firing on wallets whose balance was visibly correct
        // (5000 DGB, recent confirmed tx, sync at tip) — academically the
        // reconcile DID fail, but the user can see they're fine, so the
        // banner just adds anxiety. Now requires:
        //   - sync settled (CF-gated Synced) so we're not pre-empting SPV's
        //     own recovery — and specifically not firing while cfheaders is
        //     still finding the user's funds,
        //   - reconcile failed flag set,
        //   - AND balance == 0 (the actual symptom users complained about).
        // A wallet with non-zero balance is its own evidence of working,
        // even if the reconcile insurance step didn't run.
        if (reconcileFailed && syncProgressInfo.stage == SyncStage.Synced && balance == 0L) {
            item {
                ReconcileFailedBanner(
                    onRetry = { viewModel.retryPostUpgradeReconcile() },
                    onDismiss = { viewModel.dismissReconcileFailedBanner() },
                )
            }
        }

        // BIP158 → bloom fallback notice. Watchdog flips this when filter
        // peers didn't make cfheaders progress within 120s; we tell the user
        // their session lost privacy for now but will retry filters next launch.
        if (bloomFallback) {
            item {
                BloomFallbackBanner()
            }
        }

        // Tor → clearnet fallback notice. Watchdog flips this when Tor
        // never reached bootstrap-complete (or reached it but routed nothing)
        // and the wallet was forced to dial peers directly. Same session
        // lifecycle as bloomFallback — resets on next launch so each app
        // start retries Tor before degrading.
        if (torFailure) {
            item {
                TorFailureBanner()
            }
        }

        // Own-node dark banner (own-node-pairing track): the paired node is
        // unreachable or connected but not serving compact filters (a node
        // missing peerblockfilters=1). "Use public peers" only makes sense in
        // exclusive mode — in additive mode the wallet already has public
        // peers alongside the own node, so the escape hatch would be a no-op.
        if (ownNodeHealth == OwnNodeHealth.DARK) {
            item {
                OwnNodeDarkBanner(
                    showUsePublicPeers = viewModel.customNodeExclusive,
                    onUsePublicPeers = { viewModel.temporarilyUsePublicPeers() },
                    onOpenSettings = onNavigateNetworkInfo,
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

        // Compact own-node health chip — small and non-intrusive, only shown
        // once the paired node is actually serving compact filters (a paired
        // but DARK/CONNECTING node surfaces via the banner above instead, not
        // this chip, so this never contradicts it).
        if (ownNodeHealth == OwnNodeHealth.SERVING) {
            item {
                OwnNodeHealthChip(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        // DigiRunner is no longer shown during sync — it launches from the
        // Hub's Games tab. (Rendering the game during a fresh full sync could
        // OOM the process on memory-constrained devices; the sync progress
        // readout lives in SyncProgressCard.)

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
                val etaSeconds = info.etaSeconds
                val text = when {
                    pct >= 100 -> "Finishing up — verifying recent blocks"
                    etaSeconds != null -> "$pct% complete · ${formatEta(etaSeconds)} remaining"
                    else -> "$pct% complete · estimating…"
                }
                Text(
                    text = text,
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
 * Small badge shown at the top of the wallet header when the runtime network
 * selection (Settings > Advanced > Network, dev-gated) is testnet — so it's
 * never ambiguous which chain a balance/transaction belongs to. Never renders
 * on mainnet (the default, and the only option in a mainnet release build).
 */
@Composable
private fun TestnetBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .background(
                color = Color(0xFFFF6D00).copy(alpha = 0.25f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = Icons.Default.BugReport,
            contentDescription = "Testnet active",
            tint = Color(0xFFFFB74D),
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "TESTNET",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFFB74D),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
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
private fun SyncIndicator(info: SyncProgressInfo) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
        catch (e: Exception) { "" }
    }

    when (info.stage) {
        SyncStage.Syncing -> {
            val pct = (info.progressFraction * 100).toInt()
            // Header shows the same percent the card shows — they're sourced
            // from the same syncProgressInfo so they can't disagree. When
            // progress hits 100 but stage is still Syncing (final wrap-up),
            // show "Finishing up" instead of "100%".
            val status = when {
                pct >= 100 -> "Finishing up · ${info.peerCount} peers"
                else -> "Syncing · ${info.peerCount} peers · ${pct}%"
            }
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
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent.copy(alpha = 0.85f)
                )
            }
        }
        SyncStage.Synced -> {
            val v = if (versionName.isNotEmpty()) " · v$versionName" else ""
            val statusText = when {
                info.peerCount > 0 && info.currentBlock > 0 ->
                    "Connected · ${info.peerCount} peers · Block ${formatThousands(info.currentBlock)}$v"
                info.peerCount > 0 -> "Connected · ${info.peerCount} peers$v"
                else -> "Connected$v"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50).copy(alpha = 0.85f)
            )
        }
        SyncStage.Failed -> {
            Text(
                text = "Disconnected · ${info.peerCount} peers",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
        }
        SyncStage.Connecting -> {
            val text = if (info.peerCount > 0)
                "Connecting · ${info.peerCount} peers"
            else
                "Connecting…"
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
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent.copy(alpha = 0.85f)
                )
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

/**
 * Shown when the BIP158 watchdog gave up reaching filter peers and fell
 * back to bloom for the session. Bloom leaks the wallet address set to
 * each connected peer — telling the user this happened lets them stop
 * the session (force-close) if they care about that privacy guarantee.
 * Next app launch tries filters first again.
 */
@androidx.compose.runtime.Composable
private fun BloomFallbackBanner() {
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
                    text = "Privacy degraded for this session",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Compact-filter (private) sync was unavailable this session, so " +
                       "the wallet fell back to bloom filters — your addresses are " +
                       "visible to peers. Restart the app to retry private sync.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TorFailureBanner() {
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
                    text = "Tor unavailable — using direct connections",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tor didn't reach the network in time so the wallet is " +
                       "connecting directly to peers for this session. Your IP " +
                       "is visible to peers until you restart the app.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            )
        }
    }
}

/**
 * Own-node-pairing track: shown when the paired node is DARK — either
 * unreachable, or connected but not answering cfheaders (a node missing
 * peerblockfilters=1). "Use public peers" is the session escape hatch
 * (additive, non-exclusive re-pin — see SyncService.ACTION_OWN_NODE_ADDITIVE_SESSION);
 * it's hidden in additive mode since the wallet already has public peers
 * there and the action would be a no-op.
 */
@androidx.compose.runtime.Composable
private fun OwnNodeDarkBanner(
    showUsePublicPeers: Boolean,
    onUsePublicPeers: () -> Unit,
    onOpenSettings: () -> Unit,
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
                    text = "Your node is offline",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "The paired node isn't reachable or isn't serving compact " +
                       "filters right now. Check that it's running and reachable, " +
                       "and that it has peerblockfilters=1 set.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showUsePublicPeers) {
                    androidx.compose.material3.Button(
                        onClick = onUsePublicPeers,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = DigiByteAccent,
                        ),
                    ) { Text("Use public peers") }
                }
                androidx.compose.material3.OutlinedButton(onClick = onOpenSettings) {
                    Text("Node settings")
                }
            }
        }
    }
}

/**
 * Small, non-intrusive chip near the sync card confirming the paired own
 * node is actively serving compact filters. Only rendered by the caller
 * when ownNodeHealth == SERVING, so this composable itself has no gating.
 */
@androidx.compose.runtime.Composable
private fun OwnNodeHealthChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                color = Color(0xFF1E3A2E),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "✓ Own node serving",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF6FCF97),
        )
    }
}
