package io.digibyte.ui.recovery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.recovery.LegacySweepService
import io.digibyte.core.recovery.RecoveryScanService
import io.digibyte.core.recovery.SweepDestination

private val BG = Color(0xFF0A1628)
private val CARD = Color(0xFF1A2742)
private val ACCENT = Color(0xFF26C6DA)
private val MUTED = Color(0xFF8899AA)
private val DIVIDER = Color(0xFF243352)
private val WARNING_RED = Color(0xFFEF5350)
private val SUCCESS_GREEN = Color(0xFF66BB6A)
private val AMBER = Color(0xFFFFB300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverFundsScreen(
    navController: NavController,
    vm: RecoverFundsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state is RecoverFundsViewModel.UiState.Idle) vm.classify()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Recover funds from another wallet",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D1E35),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = BG
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val s = state) {
                is RecoverFundsViewModel.UiState.Classifying ->
                    ScanningBody("Checking older derivation paths…")
                is RecoverFundsViewModel.UiState.Sweeping ->
                    ScanningBody("Sweeping…")
                is RecoverFundsViewModel.UiState.Findings ->
                    FindingsBody(
                        findings = s.findings,
                        totalSat = s.totalSat,
                        onSweepNative = { vm.sweep(SweepDestination.Native) },
                        onSweepExternal = { addr -> vm.sweep(SweepDestination.External(addr)) },
                    )
                is RecoverFundsViewModel.UiState.Done ->
                    ResultBody(s.outcomes)
                is RecoverFundsViewModel.UiState.Error ->
                    ErrorBody(
                        reason = s.reason,
                        onRetry = { vm.classify() }
                    )
                else -> Unit
            }
        }
    }
}

// ── Scanning / Sweeping progress ─────────────────────────────────────────────

@Composable
private fun ScanningBody(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = ACCENT,
            strokeWidth = 3.dp,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This may take a moment",
            color = MUTED,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ── Findings: list + destination chooser ─────────────────────────────────────

@Composable
private fun FindingsBody(
    findings: List<RecoveryScanService.ProfileResult>,
    totalSat: Long,
    onSweepNative: () -> Unit,
    onSweepExternal: (String) -> Unit,
) {
    var externalExpanded by remember { mutableStateOf(false) }
    var externalAddress by remember { mutableStateOf("") }
    val trimmedAddr = externalAddress.trim()

    // Live address validation — only check when field is non-empty
    val addressValid = remember(trimmedAddr) {
        if (trimmedAddr.isEmpty()) null   // null = not-yet-checked
        else NativeBridge.isValidAddress(trimmedAddr)
    }

    val sweepableFindings = findings.filter { it.profile.addressFormat != 2 }
    val hasSweepable = sweepableFindings.isNotEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (findings.isEmpty()) {
            // Clean empty state — no balance header, no section header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SUCCESS_GREEN,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No recoverable funds found",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "This seed has no coins on other derivation paths.",
                        color = MUTED,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // Total header — only shown when there are findings
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CARD)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(ACCENT.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Recoverable balance",
                                color = MUTED,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = formatSatToDgb(totalSat),
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Section header
            item {
                Text(
                    text = "FOUND ON",
                    color = MUTED,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            // Individual finding rows
            items(findings) { finding ->
                val isBip49 = finding.profile.addressFormat == 2
                FindingCard(finding = finding, isBip49 = isBip49)
            }
        }

        // Destination chooser + sweep — only when there's something sweepable
        if (hasSweepable) {
            item {
                Text(
                    text = "DESTINATION",
                    color = MUTED,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CARD)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Default: Into this wallet
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !externalExpanded,
                                onClick = { externalExpanded = false },
                                colors = RadioButtonDefaults.colors(selectedColor = ACCENT)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Into this wallet",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Funds will appear at your native receive address",
                                    color = MUTED,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)

                        // Advanced: Send to a different address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = externalExpanded,
                                onClick = { externalExpanded = true },
                                colors = RadioButtonDefaults.colors(selectedColor = ACCENT),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Advanced ▸ Send to a different address",
                                    color = if (externalExpanded) ACCENT else MUTED,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (externalExpanded) FontWeight.Medium else FontWeight.Normal
                                )

                                AnimatedVisibility(visible = externalExpanded) {
                                    Column {
                                        Spacer(Modifier.height(12.dp))
                                        OutlinedTextField(
                                            value = externalAddress,
                                            onValueChange = { externalAddress = it },
                                            placeholder = {
                                                Text(
                                                    "DigiByte address (D…, S…, dgb1q…)",
                                                    color = MUTED,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Ascii
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = ACCENT,
                                                unfocusedBorderColor = DIVIDER,
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                cursorColor = ACCENT,
                                            ),
                                            trailingIcon = if (trimmedAddr.isNotEmpty()) {
                                                {
                                                    Icon(
                                                        imageVector = if (addressValid == true)
                                                            Icons.Default.CheckCircle
                                                        else
                                                            Icons.Default.Warning,
                                                        contentDescription = null,
                                                        tint = if (addressValid == true) SUCCESS_GREEN else WARNING_RED,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            } else null,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp)
                                        )

                                        // Irreversible warning — always shown when external is selected
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    WARNING_RED.copy(alpha = 0.12f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    0.5.dp,
                                                    WARNING_RED.copy(alpha = 0.35f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = WARNING_RED,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .padding(top = 1.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "This address is NOT in your wallet — sweeps are irreversible",
                                                color = WARNING_RED,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sweep button
            item {
                val canSweep = !externalExpanded || (trimmedAddr.isNotEmpty() && addressValid == true)
                Button(
                    onClick = {
                        if (externalExpanded && trimmedAddr.isNotEmpty()) {
                            onSweepExternal(trimmedAddr)
                        } else {
                            onSweepNative()
                        }
                    },
                    enabled = canSweep,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ACCENT,
                        contentColor = Color(0xFF0A1628),
                        disabledContainerColor = ACCENT.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Sweep funds",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun FindingCard(
    finding: RecoveryScanService.ProfileResult,
    isBip49: Boolean,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CARD)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            (if (isBip49) AMBER else ACCENT).copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isBip49) Icons.Default.ErrorOutline else Icons.Default.Savings,
                        contentDescription = null,
                        tint = if (isBip49) AMBER else ACCENT,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = finding.profile.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = finding.profile.pathString(),
                        color = MUTED,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Text(
                    text = formatSatToDgb(finding.totalSat),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isBip49) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AMBER.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AMBER,
                        modifier = Modifier
                            .size(15.dp)
                            .padding(top = 1.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Manual recovery for now — wrapped segwit (BIP49) won’t be swept automatically",
                        color = AMBER,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ── Done: per-outcome results ─────────────────────────────────────────────────

@Composable
private fun ResultBody(outcomes: List<LegacySweepService.SweepOutcome>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SUCCESS_GREEN,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Sweep submitted",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        items(outcomes) { outcome ->
            OutcomeCard(outcome)
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Recovered funds will appear once confirmed.",
                color = MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun OutcomeCard(outcome: LegacySweepService.SweepOutcome) {
    // "succeeded" here means the broadcast was submitted (reached local relay),
    // NOT confirmed — a PENDING tx still shows a check plus the pending caption
    // below so we never claim confirmed success on local relay alone (#6).
    val succeeded = outcome.broadcastState != LegacySweepService.BroadcastState.FAILED
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CARD)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (succeeded) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (succeeded) SUCCESS_GREEN else WARNING_RED,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = outcome.profile.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = outcome.profile.pathString(),
                        color = MUTED,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                if (succeeded) {
                    Text(
                        text = formatSatToDgb(outcome.sweptSat),
                        color = SUCCESS_GREEN,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (succeeded) {
                val txid = outcome.txid!!
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "TXID",
                    color = MUTED,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = txid,
                    color = ACCENT,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Pending network confirmation",
                    color = MUTED,
                    style = MaterialTheme.typography.labelSmall
                )
            } else if (!succeeded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = friendlyFailureReason(outcome.failureReason),
                    color = WARNING_RED,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorBody(reason: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = WARNING_RED,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Something went wrong",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = friendlyErrorReason(reason),
            color = MUTED,
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color(0xFF0A1628))
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Format satoshis as a DGB amount string: "0.00123456 DGB". */
private fun formatSatToDgb(sat: Long): String {
    val dgb = sat / 100_000_000.0
    val formatted = "%.8f".format(dgb).trimEnd('0').trimEnd('.')
    return "$formatted DGB"
}

/** Map raw exception/service text to friendlier copy where obviously raw. */
private fun friendlyErrorReason(reason: String): String = when {
    reason.contains("UnknownHostException", ignoreCase = true) ||
            reason.contains("Unable to resolve host", ignoreCase = true) ->
        "Could not reach the lookup service. Check your internet connection and try again."
    reason.contains("SocketTimeoutException", ignoreCase = true) ||
            reason.contains("timeout", ignoreCase = true) ->
        "The request timed out. Try again when you have a stronger connection."
    reason.contains("seed unavailable", ignoreCase = true) ->
        "Wallet seed could not be loaded. Unlock your wallet and try again."
    reason.contains("Couldn't reach", ignoreCase = true) ->
        "Could not reach the lookup service. Try again later."
    else -> "Something went wrong — try again."
}

/** Map sweep failure reasons to friendlier copy. */
private fun friendlyFailureReason(reason: String?): String = when {
    reason == null -> "Failed for an unknown reason."
    reason.contains("no mappable UTXOs", ignoreCase = true) ->
        "No spendable outputs could be mapped to signing keys."
    reason.contains("seed derivation failed", ignoreCase = true) ->
        "Could not derive keys from seed for this path."
    reason.contains("broadcast", ignoreCase = true) ->
        "Transaction broadcast failed. Try again when connected to peers."
    else -> "Couldn't sweep this one — try again."
}
