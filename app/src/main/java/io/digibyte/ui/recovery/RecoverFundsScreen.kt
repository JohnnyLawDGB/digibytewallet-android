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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.digibyte.R

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

    var mode by rememberSaveable { mutableStateOf(RecoverMode.ThisWallet) }
    // Not rememberSaveable: a foreign recovery phrase must not be written to
    // Android's saved-instance-state Bundle (larger exposure surface than the
    // in-memory-only convention used by MnemonicInputScreen.kt).
    var phrase by remember { mutableStateOf("") }

    LaunchedEffect(mode) {
        if (mode == RecoverMode.ThisWallet && state is RecoverFundsViewModel.UiState.Idle) vm.classify()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.rf_title),
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
                            contentDescription = stringResource(R.string.common_back),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ModeSelector(
                mode = mode,
                // Disabled mid-scan/sweep so the user can't cancel a sweep that
                // may have already broadcast — see reset()'s CancellationException
                // note in RecoverFundsViewModel.
                enabled = state !is RecoverFundsViewModel.UiState.Classifying &&
                        state !is RecoverFundsViewModel.UiState.Sweeping,
            ) { newMode ->
                // Guard: re-tapping the already-selected chip must be a no-op.
                // Otherwise vm.reset() fires -> Idle but LaunchedEffect(mode)
                // doesn't restart (mode unchanged) -> own-seed screen goes
                // permanently blank with no retry path.
                if (newMode != mode) {
                    mode = newMode
                    phrase = ""
                    vm.reset()
                }
            }
            Box(Modifier.weight(1f)) {
                when (val s = state) {
                    is RecoverFundsViewModel.UiState.Classifying -> ScanningBody(
                        if (mode == RecoverMode.ThisWallet) stringResource(R.string.rf_checking_paths)
                        else stringResource(R.string.rf_scanning_phrase)
                    )
                    is RecoverFundsViewModel.UiState.Sweeping -> ScanningBody(stringResource(R.string.rf_sweeping))
                    is RecoverFundsViewModel.UiState.Findings -> FindingsBody(
                        findings = s.findings,
                        totalSat = s.totalSat,
                        // Foreign: sweep to THIS wallet only (no external option shown).
                        onSweepNative = { if (s.isForeign) vm.sweepForeign() else vm.sweep(SweepDestination.Native) },
                        onSweepExternal = if (s.isForeign) null else { addr -> vm.sweep(SweepDestination.External(addr)) },
                        partialFailurePaths = s.partialFailurePaths,
                    )
                    is RecoverFundsViewModel.UiState.Done -> ResultBody(s.outcomes)
                    is RecoverFundsViewModel.UiState.Error ->
                        if (mode == RecoverMode.AnotherPhrase)
                            PhraseEntry(phrase, { phrase = it }, error = s.reason) { vm.classifyForeign(phrase) }
                        else ErrorBody(reason = s.reason, onRetry = { vm.classify() })
                    else -> // Idle
                        if (mode == RecoverMode.AnotherPhrase)
                            PhraseEntry(phrase, { phrase = it }, error = null) { vm.classifyForeign(phrase) }
                        else Unit
                }
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
            text = stringResource(R.string.rf_may_take),
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
    onSweepExternal: ((String) -> Unit)?,
    /** Paths the scan could not reach. Non-empty means these findings are incomplete. */
    partialFailurePaths: List<String> = emptyList(),
) {
    var externalExpanded by remember { mutableStateOf(false) }
    var externalAddress by remember { mutableStateOf("") }
    val trimmedAddr = externalAddress.trim()
    val allowExternal = onSweepExternal != null

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
                    // A scan that could not check every path has NOT established that there are
                    // no funds. Showing a green tick and "no funds found" there is a confident
                    // answer to a question the wallet did not finish asking — and a user who
                    // reads it stops looking. Distinct outcome, distinct wording, distinct icon.
                    val incomplete = partialFailurePaths.isNotEmpty()
                    Icon(
                        imageVector = if (incomplete) Icons.Default.ErrorOutline
                                      else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (incomplete) WARNING_RED else SUCCESS_GREEN,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (incomplete) stringResource(R.string.rf_couldnt_finish)
                               else stringResource(R.string.rf_none_found),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (incomplete)
                            stringResource(
                                R.string.rf_partial_body,
                                partialFailurePaths.joinToString(", "),
                            )
                        else stringResource(R.string.rf_no_coins),
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
                                text = stringResource(R.string.rf_recoverable_balance),
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
                    text = stringResource(R.string.rf_found_on),
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
                    text = stringResource(R.string.rf_destination),
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
                            if (allowExternal) {
                                RadioButton(
                                    selected = !externalExpanded,
                                    onClick = { externalExpanded = false },
                                    colors = RadioButtonDefaults.colors(selectedColor = ACCENT)
                                )
                            } else {
                                // Only option in foreign mode — read as a plain
                                // statement rather than a pre-selected radio.
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = ACCENT,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.rf_into_this_wallet),
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.rf_native_addr_note),
                                    color = MUTED,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Advanced: Send to a different address — only offered when
                        // the caller supports an external destination (own-seed
                        // flow). Foreign-phrase sweeps always land in this wallet.
                        if (allowExternal) {
                            HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)

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
                                        text = stringResource(R.string.rf_advanced_send),
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
                                                        stringResource(R.string.rf_address_hint),
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
                                                    text = stringResource(R.string.rf_not_in_wallet),
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
            }

            // Sweep button
            item {
                val canSweep = !allowExternal || !externalExpanded ||
                        (trimmedAddr.isNotEmpty() && addressValid == true)
                Button(
                    onClick = {
                        if (allowExternal && externalExpanded && trimmedAddr.isNotEmpty()) {
                            onSweepExternal?.invoke(trimmedAddr)
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
                        text = stringResource(R.string.rf_sweep),
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
                        text = stringResource(R.string.rf_bip49_note),
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
                    text = stringResource(R.string.rf_sweep_submitted),
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
                text = stringResource(R.string.rf_will_appear),
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
                    text = stringResource(R.string.rf_txid),
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
                    text = stringResource(R.string.rf_pending),
                    color = MUTED,
                    style = MaterialTheme.typography.labelSmall
                )
            } else if (!succeeded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = friendlyFailureReason(LocalContext.current.resources, outcome.failureReason),
                    color = WARNING_RED,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Coins deliberately left behind. Shown on failed outcomes too: someone
            // troubleshooting a failed sweep still needs to know what was held back, and this is
            // exactly when that detail would otherwise go missing.
            //
            // The two lists stay separate because they are different facts. "Carries a DigiAsset"
            // is a decision the wallet made on purpose; "could not tell" is an admission, and it
            // may clear on a retry. Merging them into one "skipped" count would hide which is
            // which from the person deciding what to do next.
            if (outcome.heldBackAssets.isNotEmpty()) {
                HeldBackNote(
                    title = stringResource(R.string.rf_held_assets, outcome.heldBackAssets.size),
                    body = stringResource(R.string.rf_held_assets_body),
                    outpoints = outcome.heldBackAssets,
                )
            }
            if (outcome.heldBackFeeReserve.isNotEmpty()) {
                HeldBackNote(
                    title = stringResource(R.string.rf_held_reserve, outcome.heldBackFeeReserve.size),
                    body = stringResource(R.string.rf_held_reserve_body),
                    outpoints = outcome.heldBackFeeReserve,
                )
            }
            if (outcome.feeReserveShortfall > 0L) {
                // Said plainly because the user has to act on it: the assets are safe but cannot
                // leave this wallet until it is funded.
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.rf_shortfall_title),
                    color = WARNING_RED,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.rf_shortfall_body,
                        formatSatToDgb(outcome.feeReserveShortfall),
                    ),
                    color = MUTED,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (outcome.heldBackUnknown.isNotEmpty()) {
                HeldBackNote(
                    title = stringResource(R.string.rf_held_unknown, outcome.heldBackUnknown.size),
                    body = stringResource(R.string.rf_held_unknown_body),
                    outpoints = outcome.heldBackUnknown,
                )
            }
        }
    }
}

/** One held-back group: what was left, why, and which outpoints — so a user can look them up
 *  rather than take the wallet's word for it. */
@Composable
private fun HeldBackNote(title: String, body: String, outpoints: List<String>) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        color = Color(0xFFFFC107),
        style = MaterialTheme.typography.labelLarge
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = body,
        color = MUTED,
        style = MaterialTheme.typography.bodySmall
    )
    // Capped: a wallet with dozens of asset outputs would otherwise push the actual result off
    // screen. The count in the title is always the true one.
    outpoints.take(5).forEach { op ->
        Text(
            text = op,
            color = MUTED,
            style = MaterialTheme.typography.labelSmall
        )
    }
    if (outpoints.size > 5) {
        Text(
            text = stringResource(R.string.rf_and_more, outpoints.size - 5),
            color = MUTED,
            style = MaterialTheme.typography.labelSmall
        )
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
            text = stringResource(R.string.rf_something_wrong),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = friendlyErrorReason(LocalContext.current.resources, reason),
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
            Text(stringResource(R.string.seed_retry), fontWeight = FontWeight.SemiBold)
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
private fun friendlyErrorReason(
    res: android.content.res.Resources,
    reason: String,
): String = when {
    reason.contains("UnknownHostException", ignoreCase = true) ||
            reason.contains("Unable to resolve host", ignoreCase = true) ->
        res.getString(R.string.rf_err_network)
    reason.contains("SocketTimeoutException", ignoreCase = true) ||
            reason.contains("timeout", ignoreCase = true) ->
        res.getString(R.string.rf_err_timeout)
    reason.contains("seed unavailable", ignoreCase = true) ->
        res.getString(R.string.rf_err_seed)
    reason.contains("Couldn't reach", ignoreCase = true) ->
        res.getString(R.string.rf_err_lookup)
    else -> res.getString(R.string.rf_err_generic)
}

/** Map sweep failure reasons to friendlier copy. */
private fun friendlyFailureReason(
    res: android.content.res.Resources,
    reason: String?,
): String = when {
    reason == null -> res.getString(R.string.rf_fail_unknown)
    reason.contains("no mappable UTXOs", ignoreCase = true) ->
        res.getString(R.string.rf_fail_no_utxos)
    reason.contains("seed derivation failed", ignoreCase = true) ->
        res.getString(R.string.rf_fail_derive)
    reason.contains("broadcast", ignoreCase = true) ->
        res.getString(R.string.rf_fail_broadcast)
    else -> res.getString(R.string.rf_fail_generic)
}

// ── Mode selector + foreign-phrase entry ─────────────────────────────────────

enum class RecoverMode { ThisWallet, AnotherPhrase }

@Composable
private fun ModeSelector(mode: RecoverMode, enabled: Boolean, onChange: (RecoverMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == RecoverMode.ThisWallet,
            onClick = { onChange(RecoverMode.ThisWallet) },
            enabled = enabled,
            label = { Text(stringResource(R.string.rf_this_wallet)) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = CARD,
                labelColor = MUTED,
                selectedContainerColor = ACCENT.copy(alpha = 0.20f),
                selectedLabelColor = ACCENT,
            ),
        )
        FilterChip(
            selected = mode == RecoverMode.AnotherPhrase,
            onClick = { onChange(RecoverMode.AnotherPhrase) },
            enabled = enabled,
            label = { Text(stringResource(R.string.rf_other_phrase)) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = CARD,
                labelColor = MUTED,
                selectedContainerColor = ACCENT.copy(alpha = 0.20f),
                selectedLabelColor = ACCENT,
            ),
        )
    }
}

@Composable
private fun PhraseEntry(phrase: String, onPhrase: (String) -> Unit, error: String?, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(R.string.rf_other_phrase_body),
            color = Color(0xFFB0BEC5),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = phrase,
            // Lowercase per-keystroke (keep spaces) — mirrors MnemonicInputScreen's
            // WordInputField normalization so IME auto-capitalization/autocorrect
            // can never smuggle uppercase into a case-sensitive BIP39 check.
            onValueChange = { onPhrase(it.lowercase()) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text(stringResource(R.string.rf_phrase_hint)) },
            isError = error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ACCENT,
                unfocusedBorderColor = DIVIDER,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = ACCENT,
            ),
            shape = RoundedCornerShape(8.dp)
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            // classifyForeign()'s messages are already user-friendly copy —
            // routing them through friendlyErrorReason() (which only maps the
            // own-seed classify() error strings) would fall through to the
            // generic "Something went wrong" text. Show directly instead.
            Text(error, color = WARNING_RED, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onScan,
            enabled = phrase.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ACCENT,
                contentColor = Color(0xFF0A1628),
                disabledContainerColor = ACCENT.copy(alpha = 0.35f),
                disabledContentColor = Color.White.copy(alpha = 0.4f)
            )
        ) {
            Text(stringResource(R.string.rf_scan_for_funds), fontWeight = FontWeight.SemiBold)
        }
    }
}
