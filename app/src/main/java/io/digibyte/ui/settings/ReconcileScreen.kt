package io.digibyte.ui.settings

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
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.reconcile.ChainReconciliationService
import io.digibyte.core.reconcile.DgbNodeClient
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReconcileScreenEntryPoint {
    fun assetManager(): AssetManager
    fun walletManager(): io.digibyte.core.WalletManager
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
fun ReconcileScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { DgbNodeClient(context) }
    // Pull the app-scoped AssetManager via a Hilt entry point so reconcile
    // can refresh asset UTXOs after tx import. Without this the Assets tab
    // stayed empty even after a successful scan.
    val entryPoint = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReconcileScreenEntryPoint::class.java,
        )
    }
    val assetManager = remember { entryPoint.assetManager() }
    val walletManager = remember { entryPoint.walletManager() }
    val service = remember { ChainReconciliationService(client, assetManager) }
    val scope = rememberCoroutineScope()
    var stuckResult by remember { mutableStateOf<String?>(null) }

    val state by service.state.collectAsStateWithLifecycle()
    var endpoint by remember { mutableStateOf(client.endpoint()) }
    var editingEndpoint by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan for missing funds", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
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
                .imePadding()
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
                            "What this does",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The wallet normally finds your transactions via SPV (bloom " +
                        "filters + peer merkleblocks). If a sync was interrupted or " +
                        "peers dropped blocks, some of your transactions may be " +
                        "missing from the local state even though they're on-chain.\n\n" +
                        "This tool asks a DigiByte full node to list the unspent " +
                        "outputs on every address your wallet derives, then imports " +
                        "any missing transactions into the local wallet.",
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
                        "This queries an external node. The default endpoint is " +
                        "api.digiscope.me (cert-pinned). For full sovereignty, " +
                        "point it at your own node below — no tx credentials ever " +
                        "leave your device, we only send public addresses.",
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
                        "Node endpoint",
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
                            }) { Text("Save") }
                            TextButton(onClick = {
                                client.setCustomEndpoint(null)
                                endpoint = DgbNodeClient.DEFAULT_BASE_URL
                                editingEndpoint = false
                            }) { Text("Reset to default") }
                        }
                    } else {
                        Text(
                            endpoint,
                            color = Color(0xFFB0BEC5),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { editingEndpoint = true }) {
                            Text("Use my own node")
                        }
                    }
                }
            }

            // Action + state
            val busy = state is ChainReconciliationService.State.Scanning

            Button(
                onClick = {
                    scope.launch {
                        val result = service.reconcile()
                        // Clear the post-upgrade banner flag if this manual
                        // run got us a successful reconcile — same pref the
                        // auto-trigger writes to, so a Done here also
                        // dismisses the WalletScreen banner.
                        if (result is ChainReconciliationService.State.Done) {
                            io.digibyte.core.reconcile.PostUpgradeReconciler
                                .clearFailedFlag(context)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(
                    if (busy) "Scanning…" else "Scan for missing funds",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            // --- Stuck / phantom-chain send recovery ---
            HorizontalDivider(color = Color(0xFF243352))
            Text(
                "Stuck unconfirmed sends",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                "If sends never confirm, they may be spending change from an earlier send " +
                    "that never landed on-chain — so they can never be mined. This drops those " +
                    "un-mineable transactions and restores the coins they tied up. Confirmed " +
                    "sends are never touched.",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            walletManager.clearStuckSends()
                        }
                        stuckResult = if (result.dropped == 0)
                            "No stuck sends to clear."
                        else
                            "Cleared ${result.dropped} stuck send(s); corrected ${result.assetRowsCleared} " +
                                "asset row(s). Balance updates shortly."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Clear stuck sends")
            }
            stuckResult?.let {
                Text(it, color = Color(0xFF6BE8A3), fontSize = 13.sp)
            }

            // --- Full rebuild from chain (rescan every tx, re-stamp block heights) ---
            HorizontalDivider(color = Color(0xFF243352))
            Text(
                "Full rebuild from chain",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                "If confirmations, ordering, or balance still look wrong, this clears the local " +
                    "transaction cache and re-scans the whole chain with compact filters — re-detecting " +
                    "and block-stamping every transaction from on-chain data. Your seed and coins are " +
                    "untouched; everything is rebuilt from the chain. The app restarts and the rescan " +
                    "runs from your wallet's birth, so it takes a while to finish.",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
            )
            var armRebuild by remember { mutableStateOf(false) }
            var rebuilding by remember { mutableStateOf(false) }
            OutlinedButton(
                enabled = !rebuilding,
                onClick = {
                    if (!armRebuild) {
                        armRebuild = true
                    } else {
                        // Run the native teardown OFF the main thread. rebuildFromChainRescan()
                        // calls NativeBridge.stopSync(), which blocks on the peer lock; on a
                        // wallet jammed on an orphaned tip (peers hammering getheaders) that
                        // lock is contended, and running it on the UI thread ANRs the app
                        // (reported: "clicked rescan and it locked up"). Restart on the main
                        // thread once the clear completes.
                        rebuilding = true
                        scope.launch {
                            withContext(Dispatchers.IO) { walletManager.rebuildFromChainRescan() }
                            val li = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            li?.addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                            context.startActivity(li)
                            Runtime.getRuntime().exit(0)
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
                        rebuilding -> "Rebuilding — clearing cache, restarting…"
                        armRebuild -> "Tap again to confirm — clears cache & restarts"
                        else       -> "Full rebuild from chain (rescan)"
                    }
                )
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
                        Text(s.stage, color = Color.White, fontSize = 14.sp)
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
                    val totalDgb = s.totalChainBalanceSat / 100_000_000.0
                    val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                        minimumFractionDigits = 2
                        maximumFractionDigits = 8
                    }
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
                            "Scan complete",
                            color = Color(0xFF6BE8A3),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        ResultRow("Addresses scanned", "${s.scannedAddresses}")
                        ResultRow("UTXOs on-chain", "${s.utxosSeenOnChain}")
                        ResultRow("Total chain balance", "${fmt.format(totalDgb)} DGB")
                        ResultRow(
                            "Transactions imported",
                            "${s.txsImported}",
                            emphasize = s.txsImported > 0
                        )
                        ResultRow("Already in wallet", "${s.alreadyKnown}")
                        if (s.historyTxsImported > 0) {
                            ResultRow(
                                "Recovered from history",
                                "${s.historyTxsImported}",
                                emphasize = true
                            )
                        }
                        if (s.txsImported > 0 || s.historyTxsImported > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Your balance should update shortly.",
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp
                            )
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
                            "Scan failed",
                            color = Color(0xFFFF8A80),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(s.reason, color = Color(0xFFB0BEC5), fontSize = 13.sp)
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
