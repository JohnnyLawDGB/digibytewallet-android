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
import io.digibyte.ui.components.TxKind
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.res.stringResource
import io.digibyte.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onNavigateSend: () -> Unit,
    onNavigateReceive: () -> Unit,
    onNavigateScan: () -> Unit,
    onNavigateTx: (String) -> Unit,
    onNavigateAssets: () -> Unit = {},
    onNavigateNetworkInfo: () -> Unit = {},
    /** Opens the reconcile / full-rebuild screen — both recovery paths for an
     *  abandoned compact-filter band (see [AbandonedBandBanner]). */
    onNavigateReconcile: () -> Unit = {},
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val ddBalance by viewModel.ddBalance.collectAsStateWithLifecycle()
    val balanceHidden by viewModel.balanceHidden.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val fiatBalance by viewModel.fiatBalance.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val txKinds by viewModel.txKinds.collectAsStateWithLifecycle()
    val txTypedAmounts by viewModel.txTypedAmounts.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    val displayCurrency by viewModel.displayCurrency.collectAsStateWithLifecycle()
    var showCurrencyPicker by remember { mutableStateOf(false) }
    val syncProgressInfo by viewModel.syncProgressInfo.collectAsStateWithLifecycle()
    val reconcileFailed by viewModel.postUpgradeReconcileFailed.collectAsStateWithLifecycle()
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
                        dgbAmount = WalletViewModel.formatSatoshisBare(balance),
                        // Always give DigiDollar a place on the hero — shows $0.00
                        // when none is held (rather than hiding the pill).
                        ddAmount = WalletViewModel.formatDigiDollar(ddBalance),
                        // CF-gated — matches the sync card/overlay, never "synced"
                        // while cfheaders is still catching up.
                        isSynced = syncProgressInfo.stage == SyncStage.Synced,
                        hidden = balanceHidden,
                        onFiatTap = { showCurrencyPicker = true }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sync state indicator — fed from syncProgressInfo (the
                    // same source the inline card uses) so the two can never
                    // disagree about block / progress / target.
                    SyncIndicator(syncProgressInfo)

                    // Tor indicator — only visible when Tor is connected
                    TorIndicator(torState)
                }

                // Privacy toggle — masks/reveals every figure on the hero card.
                // Persisted per-device (WalletViewModel.toggleBalanceHidden), so the
                // choice holds across restarts.
                IconButton(
                    onClick = { viewModel.toggleBalanceHidden() },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = if (balanceHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (balanceHidden) "Show balances" else "Hide balances",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
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

        // Abandoned compact-filter band (paced-convoy fetch, spec Part E —
        // operator GATE 3). The B2 valve can only prove a height is unservable by
        // the peers it is CURRENTLY connected to, never fleet-wide, so under fleet
        // saturation it can abandon a height that was in fact servable. That
        // residual is acceptable ONLY because the band stays visible and
        // recoverable — this banner is the "visible" half, and it is deliberately
        // NOT inside SyncProgressCard (which is gated on isWorking): it must
        // SURVIVE the flip to Synced. It clears only on the recovered signal
        // (a reconcile that covered the owned set, or a full rescan), never on
        // the monotonic abandonedBelow watermark, which no recovery clears.
        syncProgressInfo.abandonedBand?.let { band ->
            item {
                AbandonedBandBanner(band = band, onScan = onNavigateReconcile)
            }
        }

        // Tor → clearnet fallback notice. Watchdog flips this when Tor
        // never reached bootstrap-complete (or reached it but routed nothing)
        // and the wallet was forced to dial peers directly. Resets on next
        // launch so each app start retries Tor before degrading.
        if (torFailure) {
            item {
                TorFailureBanner(onRetry = { viewModel.retryTor() })
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
                    label = stringResource(R.string.wallet_send),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateSend
                )
                WalletActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = stringResource(R.string.wallet_receive),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateReceive
                )
                WalletActionButton(
                    icon = Icons.Default.CropFree,
                    label = stringResource(R.string.wallet_scan),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateScan
                )
                WalletActionButton(
                    icon = Icons.Default.Stars,
                    label = stringResource(R.string.wallet_assets),
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
                    // The rate for the currency the user actually chose — the same setting the
                    // balance above already honours. This card used to render USD regardless,
                    // so a wallet set to NGN showed "≈ NGN0.00" over "DGB / USD $0.005102".
                    currencyCode = displayCurrency.code,
                    rate = p.rateFor(displayCurrency.code),
                    change24h = p.change24h,
                    source = p.source,
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
                text = stringResource(R.string.wallet_recent_activity),
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
                        text = stringResource(R.string.wallet_no_transactions),
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    kind = txKinds[tx.txid] ?: TxKind.DGB,
                    typedAmount = txTypedAmounts[tx.txid]
                )
            }
        }
    }
    }

    // Opened by tapping the equivalent line under the balance. Rendered here rather than inside
    // the LazyColumn: a sheet declared inside a scrolling item is disposed the moment that item
    // scrolls out of view, which closes it under the user's finger.
    if (showCurrencyPicker) {
        io.digibyte.ui.components.CurrencyPickerSheet(
            selected = displayCurrency,
            onSelect = {
                viewModel.selectCurrency(it)
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
        )
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
            // Stage-specific headline. An un-recovered abandoned band holds the stage
            // at Syncing even though the scan itself has finished — say so, rather
            // than pretending a completed scan is still grinding (the banner above
            // carries the range and the recovery action).
            val (icon, headline) = when {
                info.abandonedBandHolding -> Icons.Filled.ErrorOutline to
                    stringResource(R.string.wallet_sync_gap_headline)
                info.stage == SyncStage.Connecting -> Icons.Filled.CloudSync to
                    stringResource(R.string.wallet_sync_connecting)
                info.stage == SyncStage.Syncing -> Icons.Filled.Search to
                    stringResource(R.string.wallet_sync_scanning)
                info.stage == SyncStage.Synced -> Icons.Filled.CheckCircle to
                    stringResource(R.string.wallet_sync_uptodate)
                else -> Icons.Filled.ErrorOutline to stringResource(R.string.wallet_sync_failed)
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
                    text = stringResource(
                        R.string.wallet_block_of,
                        formatThousands(info.currentBlock),
                        formatThousands(info.targetBlock),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C5D6)
                )
            }

            // Progress bar — meaningful only during Syncing, and NOT when the only
            // thing left is the surfaced band (the scan has finished; an animated
            // bar pinned at 100% would read as "still working").
            if (info.stage == SyncStage.Syncing && !info.abandonedBandHolding) {
                LinearProgressIndicator(
                    progress = { info.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = DigiByteAccent,
                    trackColor = Color(0xFF273956)
                )
            }

            // ETA + percent line. Percent, blocks-remaining and ETA all derive from
            // the SAME frontier (the CF scan frontier under the paced convoy), so
            // they can no longer disagree with each other or with "Block X of Y".
            if (info.stage == SyncStage.Syncing && !info.abandonedBandHolding) {
                val pct = (info.progressFraction * 100).toInt()
                val etaSeconds = info.etaSeconds
                val blocks = stringResource(
                    R.string.wallet_blocks_count, formatThousands(info.blocksBehind)
                )
                val res = LocalContext.current.resources
                val text = when {
                    pct >= 100 -> stringResource(R.string.wallet_sync_finishing)
                    etaSeconds != null -> stringResource(
                        R.string.wallet_sync_progress_eta, pct, blocks, formatEta(res, etaSeconds)
                    )
                    else -> stringResource(R.string.wallet_sync_progress_no_eta, pct, blocks)
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
                    text = stringResource(
                        R.string.wallet_scan_found,
                        info.matchCount,
                        runningBalanceDisplay,
                    ),
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
                        text = stringResource(R.string.wallet_recovery_window, formatRecoveryDate(recoveryTs)),
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

private fun formatEta(res: android.content.res.Resources, seconds: Long): String {
    if (seconds <= 0) return res.getString(R.string.wallet_eta_estimating)
    return when {
        seconds < 60 -> res.getString(R.string.wallet_eta_seconds, seconds)
        seconds < 3600 -> res.getString(R.string.wallet_eta_minutes, seconds / 60)
        else -> {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            if (mins == 0L) res.getString(R.string.wallet_eta_hours, hours)
            else res.getString(R.string.wallet_eta_hours_mins, hours, mins)
        }
    }
}

private fun formatRecoveryDate(unixSeconds: Long): String =
    // UTC on purpose: this is the scan floor, a chain fact, not a local wall-clock moment.
    // The LANGUAGE follows the user; the zone does not.
    io.digibyte.core.DateDisplay.monthYear(
        millis = unixSeconds * 1000,
        zone = java.util.TimeZone.getTimeZone("UTC"),
    )

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
    currencyCode: String,
    rate: Double,
    change24h: Double,
    source: String,
    modifier: Modifier = Modifier
) {
    // FiatDisplay.formatUnitPrice, not a fixed six decimals: DGB is sub-cent in USD and needs
    // six, but is above 1 in naira or rupees where six would be noise. It also renders "--"
    // rather than a confident zero when the fetch did not carry this currency.
    val priceFormatted = io.digibyte.core.FiatDisplay.formatUnitPrice(currencyCode, rate)

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
                    text = stringResource(R.string.wallet_price_pair, currencyCode.uppercase()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = priceFormatted,
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
                    text = stringResource(R.string.wallet_price_via, source),
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
            contentDescription = stringResource(R.string.wallet_testnet_active),
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
            contentDescription = stringResource(R.string.wallet_tor_active),
            tint = Color(0xFFB39DDB),
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.wallet_tor_hidden),
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
                pct >= 100 -> stringResource(R.string.wallet_status_finishing_peers, info.peerCount)
                else -> stringResource(R.string.wallet_status_syncing, info.peerCount, pct)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // DETERMINATE (`progress = {...}`), deliberately. The indeterminate overload —
                // CircularProgressIndicator with no `progress` — resolves to a Material3
                // implementation built on rememberInfiniteTransition, whose InfiniteTransition
                // calls withInfiniteAnimationFrameNanos: an unbounded frame-clock loop that asks
                // for a Choreographer frame EVERY vsync, with no terminating condition.
                //
                // This sits in the wallet hero card (LazyColumn item #0 — always composed, never
                // scrolled off) and the Syncing stage lasts the ENTIRE deep restore. Measured on
                // a Note 8 mid-restore: 132,757 frames in ~35 minutes (~63fps on a 60Hz panel),
                // 31.96% janky, RenderThread pegged near 41% of a core, and one GC pass freeing
                // 689,111 objects — the infinite transition also rewrites four animated State
                // values per frame, one of them a boxed Int.
                //
                // A 12dp spinner is not worth a third of a core on every device for a whole sync,
                // least of all while the SPV threads are competing for the same CPU. The
                // determinate form draws the same ring and only invalidates when progressFraction
                // actually changes — the 5s poll, so ~0.2 redraws/sec instead of ~63.
                CircularProgressIndicator(
                    progress = { info.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = DigiByteAccent,
                    trackColor = DigiByteAccent.copy(alpha = 0.25f),
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
                info.peerCount > 0 && info.currentBlock > 0 -> stringResource(
                    R.string.wallet_status_connected_block,
                    info.peerCount, formatThousands(info.currentBlock), v,
                )
                info.peerCount > 0 ->
                    stringResource(R.string.wallet_status_connected_peers, info.peerCount, v)
                else -> stringResource(R.string.wallet_status_connected, v)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50).copy(alpha = 0.85f)
            )
        }
        SyncStage.Failed -> {
            Text(
                text = stringResource(R.string.wallet_disconnected_peers, info.peerCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
        }
        SyncStage.Connecting -> {
            val text = if (info.peerCount > 0)
                stringResource(R.string.wallet_status_connecting_peers, info.peerCount)
            else
                stringResource(R.string.wallet_status_connecting)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // No spinner here either, and this one is a judgement call worth stating.
                //
                // Connecting is genuinely indeterminate, which is exactly what an indeterminate
                // spinner is for — but the indeterminate overload runs the same unbounded
                // withInfiniteAnimationFrameNanos loop as the Syncing branch above, and THIS app
                // has a documented failure mode where Connecting persists for hours (the 0-peer
                // wedge). Burning ~41% of a core to animate a 12dp ring is worst precisely when
                // the wallet is already failing to connect and every cycle matters.
                //
                // The trailing "…" carries the in-progress cue at zero cost. If the spinner is
                // wanted back, it needs a FINITE animation (a bounded repeat that stops after a
                // few seconds), not the infinite transition.
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
 * The exact sentence the abandoned-band banner shows, given a band. Pure, so the
 * honesty of what it claims is unit-testable (see `AbandonedBandBannerTextTest`).
 *
 * Two shapes, because the band's TOP is exact but its BOTTOM may not be:
 *  - bottom observed → "blocks 23,900,120–23,900,124"
 *  - bottom unknown  → "blocks below 23,900,125"
 *
 * It deliberately never renders a COUNT. The only native counter,
 * `getAbandonedCount()`, returns `abandonedBelow - ledger.start` — the size of the
 * whole scanned range below the watermark, not the number of heights abandoned. On
 * a wallet that abandoned one deep height it reads as the entire history, so
 * "N blocks abandoned" would be a straight lie.
 */
internal sealed interface AbandonedRange {
    /** Both ends observed: the exact inclusive range. */
    data class Between(val low: String, val high: String) : AbandonedRange
    /** Bottom never observed: an exclusive watermark, which is TRUE where a
     *  fabricated lower bound would claim the whole history was abandoned. */
    data class Below(val watermark: String) : AbandonedRange
}

/**
 * The range only. Kept pure and free of prose so it stays unit-testable after
 * localisation — the property that matters is which range is named, not the
 * sentence around it, and the sentence now lives in strings_wallet.xml.
 */
internal fun abandonedBandRange(band: io.digibyte.core.sync.AbandonedBand): AbandonedRange =
    if (band.lowKnown && band.low in 1..band.high) {
        AbandonedRange.Between(formatThousands(band.low), formatThousands(band.high))
    } else {
        AbandonedRange.Below(formatThousands(band.high + 1))
    }

/**
 * Persistent recover-me banner for an abandoned compact-filter band (paced-convoy
 * fetch, spec Part E — operator GATE 3).
 *
 * This is the surfacing half of the safety coupling that makes the B2 abandonment
 * valve tolerable: the valve can only prove a height unservable by the peers it is
 * connected to, so under fleet saturation it can abandon a height that was really
 * servable. Accepting that residual is conditional on the band never being silent.
 *
 * Consequently this banner is NOT dismissible and NOT inside the sync card: it
 * survives the flip to "Synced" and clears only when a recovery has actually
 * covered the band ([io.digibyte.core.sync.CfAbandonmentStore]). Both recovery
 * paths live behind [onScan]: stringResource(R.string.wallet_scan_missing) (the node reconcile,
 * CF-independent, covers any height) and "Full rebuild from chain" (the rescan,
 * which now also deletes the persisted CF scan ledger so the re-`Init` really does
 * clear `abandonedBelow`).
 */
@androidx.compose.runtime.Composable
private fun AbandonedBandBanner(
    band: io.digibyte.core.sync.AbandonedBand,
    onScan: () -> Unit,
) {
    androidx.compose.material3.Card(
        onClick = onScan,
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
                    text = stringResource(R.string.wallet_history_gap),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.wallet_gap_body,
                    when (val r = abandonedBandRange(band)) {
                        is AbandonedRange.Between ->
                            stringResource(R.string.wallet_gap_range_between, r.low, r.high)
                        is AbandonedRange.Below ->
                            stringResource(R.string.wallet_gap_range_below, r.watermark)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = onScan,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = DigiByteAccent,
                ),
            ) { Text(stringResource(R.string.wallet_scan_missing)) }
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
                    text = stringResource(R.string.wallet_balance_refresh_failed),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wallet_reconcile_unreachable),
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
                ) { Text(stringResource(R.string.common_retry_now)) }
                androidx.compose.material3.OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_dismiss))
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TorFailureBanner(onRetry: () -> Unit) {
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
                    text = stringResource(R.string.wallet_tor_unavailable),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wallet_tor_timeout),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            )
            // ROADMAP Phase 2 item 5's "banner + retry": the missing half. One
            // tap = one Tor start attempt; the banner clears itself only when
            // Tor actually reaches Connected (SyncService's state observer).
            androidx.compose.material3.TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = stringResource(R.string.common_retry_now),
                    color = androidx.compose.ui.graphics.Color(0xFFFFCC66),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
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
                    text = stringResource(R.string.wallet_node_offline),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD580),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wallet_node_offline_body),
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
                    ) { Text(stringResource(R.string.wallet_use_public_peers)) }
                }
                androidx.compose.material3.OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.wallet_node_settings))
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
            text = stringResource(R.string.wallet_own_node_serving),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF6FCF97),
        )
    }
}
