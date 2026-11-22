package app.aroundtheblock.wallet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.asset.AssetManager
import app.aroundtheblock.wallet.core.reconcile.ChainReconciliationService
import app.aroundtheblock.wallet.core.reconcile.ChainReconciliationService.Failure
import app.aroundtheblock.wallet.core.reconcile.ChainReconciliationService.Stage
import app.aroundtheblock.wallet.core.reconcile.DgbNodeClient
import app.aroundtheblock.wallet.core.reconcile.ClassTotal
import app.aroundtheblock.wallet.core.reconcile.ScanClass
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import app.aroundtheblock.wallet.ui.components.keyboardPadding

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReconcileScreenEntryPoint {
    fun assetManager(): AssetManager
    fun walletManager(): app.aroundtheblock.wallet.core.WalletManager
    /** The reconcile client Hilt builds on the shared client (Tor routing and DigiScope pins). */
    fun dgbNodeClient(): DgbNodeClient
}

/**
 * "Scan for missing funds" screen — Path B reconciliation.
 *
 * Flow:
 *  - Explain what we're about to do + trust model (node-trusted vs. SPV)
 *  - Let the user edit the RPC endpoint if they run their own node
 *  - Button kicks off ChainReconciliationService.reconcile()
 *  - Progress shown live; final result shows addresses/UTXOs/imported counts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileScreen(navController: NavController, autoStart: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Pull the app-scoped AssetManager via a Hilt entry point so reconcile
    // can refresh asset UTXOs after tx import. Without this the Assets tab
    // stayed empty even after a successful scan.
    val entryPoint = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReconcileScreenEntryPoint::class.java,
        )
    }
    // The injected client: it carries the Tor routing and the DigiScope pins.
    val client = remember { entryPoint.dgbNodeClient() }
    val assetManager = remember { entryPoint.assetManager() }
    val walletManager = remember { entryPoint.walletManager() }
    val service = remember {
        ChainReconciliationService(client, assetManager, appContext = context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var stuckResult by remember { mutableStateOf<String?>(null) }

    val state by service.state.collectAsStateWithLifecycle()
    var endpoint by remember { mutableStateOf(client.endpoint()) }
    var editingEndpoint by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_scan_funds), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A1628)
                )
            )
        },
        containerColor = Color(0xFF0A1628),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .keyboardPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = DigiByteAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.reconcile_what_title),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // What the scan does, in the user's language (RECONCILE-COPY).
                        stringResource(R.string.reconcile_intro_body),
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // Trust warning
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x33FFAA00)
                )
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFAA00),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        // What leaves the device, and to whom: a disclosure, so it is in every language.
                        stringResource(R.string.reconcile_trust_notice),
                        color = Color(0xFFFFCC66),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // Endpoint picker
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.reconcile_endpoint_title),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (editingEndpoint) {
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = DigiByteAccent,
                                unfocusedBorderColor = Color(0xFF546E7A),
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                client.setCustomEndpoint(endpoint)
                                editingEndpoint = false
                            }) { Text(stringResource(R.string.ni_save)) }
                            TextButton(onClick = {
                                client.setCustomEndpoint(null)
                                endpoint = DgbNodeClient.DEFAULT_BASE_URL
                                editingEndpoint = false
                            }) { Text(stringResource(R.string.reconcile_endpoint_reset)) }
                        }
                    } else {
                        Text(
                            endpoint,
                            color = Color(0xFFB0BEC5),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { editingEndpoint = true }) {
                            Text(stringResource(R.string.ni_use_own_node))
                        }
                    }
                }
            }

            // Action + state
            val busy = state is ChainReconciliationService.State.Scanning

            // ONE definition of "run the scan", shared by the button and by the
            // auto-start below, so the two can never drift apart.
            val runScan: () -> Unit = {
                scope.launch {
                    val result = service.reconcile()
                    // Clear the post-upgrade banner flag if this manual
                    // run got us a successful reconcile — same pref the
                    // auto-trigger writes to, so a Done here also
                    // dismisses the WalletScreen banner.
                    if (result is ChainReconciliationService.State.Done) {
                        app.aroundtheblock.wallet.core.reconcile.PostUpgradeReconciler
                            .clearFailedFlag(context)
                    }
                }
                Unit
            }

            // Arrived from the history-gap banner, whose button reads "Scan for missing
            // transactions". That button used to only NAVIGATE here, so a user who tapped it
            // landed on this screen believing a scan had started — nothing had, and nothing
            // said so. Observed 2026-08-07: a first tap produced no network call at all (the
            // DgbNodeClient logs every failure and was silent because it was never reached),
            // and the wallet sat at 0.00 DGB with its history written off until the button on
            // THIS screen was pressed. A control must do what its label says.
            //
            // Guarded by a remember so it fires once per arrival, not on every recomposition.
            var autoStarted by remember { mutableStateOf(false) }
            LaunchedEffect(autoStart, state) {
                if (shouldAutoStartReconcile(
                        requested = autoStart,
                        alreadyStarted = autoStarted,
                        scanInProgress = state is ChainReconciliationService.State.Scanning,
                    )
                ) {
                    autoStarted = true
                    runScan()
                }
            }

            Button(
                onClick = runScan,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(
                    if (busy) stringResource(R.string.reconcile_scanning) else stringResource(R.string.set_scan_funds),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            // --- Stuck / phantom-chain send recovery ---
            HorizontalDivider(color = Color(0xFF243352))
            Text(
                stringResource(R.string.reconcile_stuck_title),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.reconcile_stuck_body),
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            // A day-old send the node has never seen is cleared too (4.1.3, review R8).
                            walletManager.clearStuckSends(neverRelayed = { txid, inputs -> client.sendNeverRelayed(txid, inputs) })
                        }
                        // One sentence from up to four parts. Each part after the first carries its own
                        // leading separator in the resource, so a language picks its own punctuation.
                        val res = context.resources
                        stuckResult = if (result.dropped == 0)
                            context.getString(R.string.reconcile_stuck_none)
                        else buildString {
                            append(res.getQuantityString(R.plurals.reconcile_stuck_cleared, result.dropped, result.dropped))
                            // Name orphans explicitly: they are the ones that could never
                            // confirm no matter how long you waited, and until now the only
                            // cure was a full rebuild-from-chain re-sync.
                            if (result.orphansCleared > 0) {
                                append(res.getQuantityString(R.plurals.reconcile_stuck_orphans, result.orphansCleared, result.orphansCleared))
                            }
                            if (result.staleCleared > 0) {
                                append(context.getString(R.string.reconcile_stuck_stale_cleared, result.staleCleared))
                            }
                            append(res.getQuantityString(R.plurals.reconcile_stuck_asset_rows, result.assetRowsCleared, result.assetRowsCleared))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.reconcile_stuck_button))
            }
            stuckResult?.let {
                Text(it, color = Color(0xFF6BE8A3), fontSize = 13.sp)
            }

            // --- Full rebuild from chain (rescan every tx, re-stamp block heights) ---
            HorizontalDivider(color = Color(0xFF243352))
            Text(
                stringResource(R.string.reconcile_rebuild_title),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.reconcile_rebuild_body),
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
            )
            var armRebuild by remember { mutableStateOf(false) }
            var rebuilding by remember { mutableStateOf(false) }
            var rebuildNotStarted by remember { mutableStateOf(false) }
            OutlinedButton(
                enabled = !rebuilding,
                onClick = {
                    if (!armRebuild) {
                        armRebuild = true
                        rebuildNotStarted = false
                    } else {
                        // Run the native teardown OFF the main thread. rebuildFromChainRescan()
                        // calls NativeBridge.stopSync(), which blocks on the peer lock; on a
                        // wallet jammed on an orphaned tip (peers hammering getheaders) that
                        // lock is contended, and running it on the UI thread ANRs the app
                        // (reported: "clicked rescan and it locked up"). Restart on the main
                        // thread once the clear completes.
                        rebuilding = true
                        scope.launch {
                            val rebuilt = withContext(Dispatchers.IO) { walletManager.rebuildFromChainRescan() }
                            if (!rebuilt) {
                                // A step the clears depend on failed (BRChainRebuild.h): nothing
                                // was cleared, so there is nothing to restart into. Say so.
                                rebuilding = false
                                armRebuild = false
                                rebuildNotStarted = true
                                requestSyncResumeNearTip(context)
                                return@launch
                            }
                            app.aroundtheblock.wallet.AppRestart.restart(context)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (armRebuild) Color(0xFFFF6B6B) else Color(0xFFB0BEC5)
                ),
            ) {
                Text(
                    when {
                        rebuilding -> stringResource(R.string.reconcile_rebuild_in_progress)
                        armRebuild -> stringResource(R.string.reconcile_unprovable_confirm)
                        else       -> stringResource(R.string.reconcile_rebuild_button)
                    }
                )
            }
            if (rebuildNotStarted) {
                Text(stringResource(R.string.reconcile_rebuild_not_started), color = Color(0xFFFF8A80), fontSize = 13.sp)
            }

            when (val s = state) {
                is ChainReconciliationService.State.Idle -> Unit

                is ChainReconciliationService.State.Scanning -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF1A2742),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            // Every stage is worded here: the service only names it.
                            when (s.stage) {
                                Stage.CHECKING_PENDING -> stringResource(R.string.reconcile_stage_pending)
                                Stage.SCANNING_HISTORY -> stringResource(R.string.reconcile_stage_history)
                                Stage.READING_HISTORY -> stringResource(R.string.reconcile_stage_reading_history)
                                Stage.RECOVERING_TX -> stringResource(R.string.reconcile_stage_recovering, s.current, s.total)
                                Stage.LISTING_ADDRESSES -> stringResource(R.string.reconcile_stage_addresses)
                                Stage.TIDYING_ASSETS -> stringResource(R.string.reconcile_stage_assets)
                                Stage.QUERYING_NODE -> pluralStringResource(R.plurals.reconcile_stage_querying, s.total, s.total)
                                Stage.IMPORTING_TX -> stringResource(R.string.reconcile_stage_importing, s.current, s.total)
                                Stage.COMPARING -> stringResource(R.string.reconcile_stage_comparing)
                            },
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = s.progress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                            color = DigiByteAccent,
                            trackColor = Color(0xFF243352)
                        )
                    }
                }

                is ChainReconciliationService.State.Done -> {
                    val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                        minimumFractionDigits = 2
                        maximumFractionDigits = 8
                    }
                    // A value and the ticker; a count and that. Nothing here is a sentence.
                    fun dgb(sat: Long) = "${fmt.format(sat / 100_000_000.0)} DGB"
                    fun line(t: ClassTotal) = "${t.count} · ${dgb(t.sat)}"
                    val p = s.partition
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0x3348B76D),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.reconcile_done_title),
                            color = Color(0xFF6BE8A3),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        ResultRow(stringResource(R.string.reconcile_addresses_scanned), s.scannedAddresses.toString())
                        ResultRow(stringResource(R.string.reconcile_outputs_found), s.utxosSeenOnChain.toString())
                        if (p == null) {
                            // No wallet to compare with: say so rather than show a raw sum as a balance.
                            ResultRow(stringResource(R.string.reconcile_found_not_compared), dgb(s.totalChainBalanceSat))
                        } else {
                            Spacer(Modifier.height(8.dp))
                            ResultRow(stringResource(R.string.reconcile_in_balance), dgb(p.inBalanceSat), emphasize = true)
                            ResultRow(stringResource(R.string.reconcile_wallet_balance), dgb(p.walletBalanceSat))
                            Text(
                                if (p.matchesWallet) stringResource(R.string.reconcile_matches_wallet_balance)
                                else stringResource(R.string.reconcile_does_not_match_wallet_balance),
                                color = if (p.matchesWallet) Color(0xFF6BE8A3) else Color(0xFFFFB74D),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            val held = p.total(ScanClass.HELD_ASSET)
                            if (held.count > 0) ResultRow(stringResource(R.string.reconcile_held_asset), line(held))
                            val unknown = p.total(ScanClass.HELD_UNKNOWN)
                            if (unknown.count > 0) ResultRow(stringResource(R.string.reconcile_held_unknown), line(unknown))
                            val proven = p.total(ScanClass.PROVEN_PLAIN)
                            if (proven.count > 0) ResultRow(stringResource(R.string.reconcile_proven_plain), line(proven))
                            val dd = p.total(ScanClass.DIGIDOLLAR)
                            if (dd.count > 0) ResultRow(stringResource(R.string.reconcile_digidollar_outputs), dd.count.toString())
                            val immature = p.total(ScanClass.IMMATURE)
                            if (immature.count > 0) ResultRow(stringResource(R.string.reconcile_immature), line(immature))
                            val pending = p.total(ScanClass.PENDING)
                            if (pending.count > 0) ResultRow(stringResource(R.string.reconcile_pending), line(pending))
                            val missing = p.total(ScanClass.NOT_IN_WALLET)
                            ResultRow(stringResource(R.string.reconcile_not_in_wallet), line(missing), emphasize = missing.count > 0)
                            if (p.walletOnly.count > 0) {
                                ResultRow(stringResource(R.string.reconcile_wallet_only), line(p.walletOnly))
                            }
                            if (missing.count == 0) {
                                Text(
                                    stringResource(R.string.reconcile_no_missing_funds),
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ResultRow(
                            stringResource(R.string.reconcile_txs_imported),
                            s.txsImported.toString(),
                            emphasize = s.txsImported > 0
                        )
                        ResultRow(stringResource(R.string.reconcile_txs_already_in_wallet), s.alreadyKnown.toString())
                        if (s.txsNotAdded > 0) {
                            ResultRow(stringResource(R.string.reconcile_txs_not_added), s.txsNotAdded.toString())
                        }
                        if (s.historyTxsImported > 0) {
                            ResultRow(
                                stringResource(R.string.reconcile_history_recovered),
                                s.historyTxsImported.toString(),
                                emphasize = true
                            )
                        }
                        if (s.txsImported > 0 || s.historyTxsImported > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.reconcile_balance_updates),
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp
                            )
                        }
                        // The node named heights this wallet cannot check against its own chain:
                        // those transactions were not added on its word. A rescan from that block
                        // finds the real ones over the peer-to-peer network (4.1.3 row 1).
                        if (s.unprovableFromHeight > 0L) {
                            val height = NumberFormat.getIntegerInstance(Locale.US).format(s.unprovableFromHeight)
                            var armRescanFrom by remember { mutableStateOf(false) }
                            var rescanningFrom by remember { mutableStateOf(false) }
                            var rescanFromNotStarted by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.reconcile_unprovable_body, height),
                                color = Color(0xFFFFC46B),
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                enabled = !rescanningFrom,
                                onClick = {
                                    if (!armRescanFrom) {
                                        armRescanFrom = true
                                        rescanFromNotStarted = false
                                    } else {
                                        // Off the main thread, then restart: as for the full rebuild below.
                                        rescanningFrom = true
                                        scope.launch {
                                            val rebuilt = withContext(Dispatchers.IO) {
                                                walletManager.rebuildFromChainRescanFrom(s.unprovableFromHeight)
                                            }
                                            if (!rebuilt) {
                                                // Stopped before anything was cleared: no restart.
                                                rescanningFrom = false
                                                armRescanFrom = false
                                                rescanFromNotStarted = true
                                                requestSyncResumeNearTip(context)
                                                return@launch
                                            }
                                            app.aroundtheblock.wallet.AppRestart.restart(context)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    if (armRescanFrom) stringResource(R.string.reconcile_unprovable_confirm)
                                    else stringResource(R.string.reconcile_unprovable_rescan, height)
                                )
                            }
                            if (rescanFromNotStarted) {
                                Text(stringResource(R.string.reconcile_rebuild_not_started), color = Color(0xFFFF8A80), fontSize = 13.sp)
                            }
                        }
                    }
                }

                is ChainReconciliationService.State.Failed -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0x33FF5252),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.reconcile_failed_title),
                            color = Color(0xFFFF8A80),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when (s.failure) {
                                Failure.NO_ADDRESSES -> stringResource(R.string.reconcile_fail_no_addresses)
                                Failure.NODE_REQUEST -> stringResource(R.string.reconcile_fail_node)
                                // The detail is an exception's own text: shown as it is, after
                                // a line the user can read.
                                Failure.ERROR -> stringResource(R.string.reconcile_fail_error, s.detail)
                            },
                            color = Color(0xFFB0BEC5),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFFB0BEC5), fontSize = 13.sp)
        Text(
            value,
            color = if (emphasize) DigiByteAccent else Color.White,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

/**
 * A rebuild that stopped before clearing anything may already have stopped sync: hand the manager
 * to SyncService's reload-first rebuild (recreatePeerManagerResumingNearTip, the same request as the
 * main screen's pull-to-refresh) so the wallet reconnects near its tip instead of sitting
 * disconnected until the keepalive notices. Best effort: a refused foreground start only leaves
 * that recovery to the keepalive.
 */
private fun requestSyncResumeNearTip(context: android.content.Context) {
    try {
        val intent = android.content.Intent(context, app.aroundtheblock.wallet.service.SyncService::class.java)
            .setAction(app.aroundtheblock.wallet.service.SyncService.ACTION_RECREATE_NEAR_TIP)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    } catch (t: Throwable) {
        android.util.Log.w("ReconcileScreen", "rebuild stopped: the sync resume request was not delivered", t)
    }
}
