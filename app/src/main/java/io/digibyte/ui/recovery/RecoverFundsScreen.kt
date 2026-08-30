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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
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
import io.digibyte.ui.components.SecureWindow

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
    SecureWindow()

    val passphraseVerdict by vm.passphraseVerdict.collectAsState()
    val state by vm.state.collectAsState()

    var mode by rememberSaveable { mutableStateOf(RecoverMode.ThisWallet) }
    // Not rememberSaveable: a foreign recovery phrase must not be written to
    // Android's saved-instance-state Bundle (larger exposure surface than the
    // in-memory-only convention used by MnemonicInputScreen.kt).
    var phrase by remember { mutableStateOf("") }
    // Same reasoning as `phrase` above: never rememberSaveable, so a foreign passphrase is not
    // written to a saved-state bundle.
    var passphrase by remember { mutableStateOf("") }

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
                    passphrase = ""
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
                    // Its own screen because it is the one step that waits on a confirmation —
                    // up to a few minutes. Reusing "Sweeping…" would look frozen, and a user
                    // watching a stalled spinner over their own coins force-quits.
                    is RecoverFundsViewModel.UiState.SplittingForAssets -> ScanningBody(
                        stringResource(R.string.rf_splitting_for_assets, s.feeOutputCount)
                    )
                    is RecoverFundsViewModel.UiState.Findings -> FindingsBody(
                        findings = s.findings,
                        totalSat = s.totalSat,
                        // Foreign: sweep to THIS wallet only (no external option shown).
                        onSweepNative = { if (s.isForeign) vm.sweepForeign() else vm.sweep(SweepDestination.Native) },
                        onSweepExternal = if (s.isForeign) null else { addr -> vm.sweep(SweepDestination.External(addr)) },
                        partialFailurePaths = s.partialFailurePaths,
                        verdict = passphraseVerdict,
                        assetOutpointCount = s.assetOutpointCount,
                        digiDollar = s.digiDollar,
                    )
                    is RecoverFundsViewModel.UiState.Done -> ResultBody(
                        outcomes = s.outcomes,
                        assetMoves = s.assetMoves,
                        digiDollar = s.digiDollar,
                    )
                    is RecoverFundsViewModel.UiState.Error ->
                        if (mode == RecoverMode.AnotherPhrase)
                            PhraseEntry(phrase, { phrase = it }, error = s.reason, passphrase = passphrase,
                                onPassphrase = { passphrase = it }) { vm.classifyForeign(phrase, passphrase) }
                        else ErrorBody(reason = s.reason, onRetry = { vm.classify() })
                    else -> // Idle
                        if (mode == RecoverMode.AnotherPhrase)
                            PhraseEntry(phrase, { phrase = it }, error = null, passphrase = passphrase,
                                onPassphrase = { passphrase = it }) { vm.classifyForeign(phrase, passphrase) }
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
    verdict: io.digibyte.core.recovery.PassphraseScanVerdict.Outcome? = null,
    /** Outpoints whose parent transaction carries a DigiAsset marker. Non-zero means the
     *  recovery will move assets, which is irreversible — so it is said BEFORE the button. */
    assetOutpointCount: Int = 0,
    /** DigiDollar the scan found. A token output holds zero satoshis, so it is invisible to
     *  [findings] — without this a dollars-only wallet reads as empty. */
    digiDollar: io.digibyte.core.recovery.DigiDollarScan.Result? = null,
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

    // Every found profile is sweepable — the BIP49 exclusion went when BRTransactionSign
    // learned the P2SH-P2WPKH branch.
    val sweepableFindings = findings
    // Dollars count as something to move even with zero sweepable UTXOs — that is the whole
    // point of RecoverableValue. The move itself may still be refused (the DigiDollar consensus
    // fee floor needs DGB), and that refusal is reported on the results screen.
    val hasSweepable = sweepableFindings.isNotEmpty() ||
            io.digibyte.core.recovery.RecoverableValue.exists(0, digiDollar)
    val hasValue = io.digibyte.core.recovery.RecoverableValue.exists(
        sweepableFindings.size, digiDollar,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Said before anything else: a dollar found is a dollar the user needs to see, whether
        // or not there is a single satoshi beside it.
        if (digiDollar != null && (digiDollar.hasDollars || !digiDollar.reachable)) {
            item { DigiDollarFoundCard(digiDollar) }
        }

        // The scan could not reach every path. With no findings this is the whole message (below);
        // with findings it used to be dropped entirely, which read as a settled answer.
        if (hasValue && partialFailurePaths.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(
                        R.string.rf_partial_body,
                        partialFailurePaths.joinToString(", "),
                    ),
                    color = AMBER,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        if (findings.isEmpty() && !hasValue) {
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
                    // A passphrase was supplied and found nothing, but the SAME phrase has funds
                    // without one. That is the most actionable thing we can say, and it takes
                    // priority over both "nothing found" and "couldn't finish" — see
                    // PassphraseScanVerdict for why a positive observation outranks an
                    // incomplete scan.
                    val typo = verdict == io.digibyte.core.recovery.PassphraseScanVerdict.Outcome.LIKELY_TYPO
                    Icon(
                        imageVector = if (incomplete || typo) Icons.Default.ErrorOutline
                                      else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (incomplete || typo) WARNING_RED else SUCCESS_GREEN,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = when {
                            typo -> stringResource(R.string.pass_restore_none)
                            incomplete -> stringResource(R.string.rf_couldnt_finish)
                            else -> stringResource(R.string.rf_none_found)
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            typo -> stringResource(R.string.pass_restore_typo)
                            incomplete -> stringResource(
                                R.string.rf_partial_body,
                                partialFailurePaths.joinToString(", "),
                            )
                            else -> stringResource(R.string.rf_no_coins)
                        },
                        color = MUTED,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else if (findings.isNotEmpty()) {
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
                FindingCard(finding = finding)
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
                // Said BEFORE the button, not after. Recovering this wallet moves its DigiAssets
                // first — and an asset transfer cannot be undone, so it must not be something the
                // user discovers on the results screen.
                if (assetOutpointCount > 0) {
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
                            text = stringResource(R.string.rf_will_move_assets, assetOutpointCount),
                            color = AMBER,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun FindingCard(
    finding: RecoveryScanService.ProfileResult,
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
                            ACCENT.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Savings,
                        contentDescription = null,
                        tint = ACCENT,
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

        }
    }
}

// ── Done: per-outcome results ─────────────────────────────────────────────────

@Composable
private fun ResultBody(
    outcomes: List<LegacySweepService.SweepOutcome>,
    assetMoves: List<io.digibyte.core.recovery.ForeignAssetTransferService.Move>,
    digiDollar: io.digibyte.core.recovery.DigiDollarTransferService.Result? = null,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // A green tick over a run where nothing was broadcast is a success claim about a failure.
        // A dollars-only wallet that could not meet the fee floor sweeps NOTHING — and used to be
        // told "Sweep submitted" all the same.
        val broadcastSomething = outcomes.any { it.txid != null } ||
                assetMoves.any { it.moved } || digiDollar?.moved == true
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    imageVector = if (broadcastSomething) Icons.Default.CheckCircle
                                  else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (broadcastSomething) SUCCESS_GREEN else AMBER,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (broadcastSomething) stringResource(R.string.rf_sweep_submitted)
                           else stringResource(R.string.rf_nothing_moved),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        items(outcomes) { outcome ->
            OutcomeCard(outcome, assetMoves)
        }

        // Moving the DigiAssets the sweep held back. Offered only when there ARE any, and only
        // once — the action spans every profile, so one button, not one per outcome card.
        //
        // Deliberately a separate press rather than a tail of the sweep: an asset transfer is
        // irreversible and spends the reserve, and the user has just been told in plain words
        // what was held back and why. Acting on that is their decision to make.
        if (assetMoves.isNotEmpty()) {
            item { AssetMoveSection(assetMoves) }
        }

        // Reported whenever there is anything to say — including dollars we found and could not
        // move. A recovery that empties a wallet of DGB while staying silent about its DigiDollar
        // is "no funds found" about money that exists.
        digiDollar?.let { dd ->
            if (dd.hasDollars || !dd.reachable) item { DigiDollarSection(dd) }
        }

        // Only true if something is actually on the wire. Promising arriving funds after a run
        // that broadcast nothing sends the user back to watch a balance that will never change.
        if (broadcastSomething) {
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
}

/**
 * DigiDollar found by the scan, shown BEFORE any move is attempted.
 *
 * Separate from [DigiDollarSection], which reports what became of it afterwards. This one exists
 * because a token output carries zero satoshis and so never appears among the UTXO findings: a
 * wallet holding only dollars rendered as "no funds found", with no button to move them.
 */
@Composable
private fun DigiDollarFoundCard(
    dd: io.digibyte.core.recovery.DigiDollarScan.Result,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CARD)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            if (dd.hasDollars) {
                Text(
                    text = stringResource(
                        R.string.rf_dd_found,
                        io.digibyte.core.recovery.DigiDollarHolding.formatCents(dd.cents),
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!dd.reachable) {
                if (dd.hasDollars) Spacer(Modifier.height(6.dp))
                // Could not ask about at least one address. Never reported as "holds none".
                Text(
                    text = stringResource(R.string.rf_dd_unreachable),
                    color = AMBER,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * What became of the wallet's DigiDollar.
 *
 * Shown whenever there is anything true to say — moved, found-but-unmovable, or not askable.
 * Silence was the original defect: DigiDollar was invisible to the scan, so a wallet was emptied
 * of DGB and assets and told nothing about its dollars.
 */
@Composable
private fun DigiDollarSection(
    dd: io.digibyte.core.recovery.DigiDollarTransferService.Result,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CARD)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            val amount = io.digibyte.core.recovery.DigiDollarHolding.formatCents(dd.cents)
            when {
                dd.moved -> {
                    Text(
                        text = stringResource(R.string.rf_dd_moved, amount),
                        color = SUCCESS_GREEN,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                !dd.reachable && !dd.hasDollars -> {
                    // Could not ask. Never reported as "holds none" — the wallet may hold plenty.
                    Text(
                        text = stringResource(R.string.rf_dd_unreachable),
                        color = AMBER,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.rf_dd_left_behind, amount),
                        color = AMBER,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The fee floor is the refusal a real user actually hits, so it gets a
                    // sentence in their language and in DGB. Everything else falls back to the
                    // planner's own words — imperfect, but never silent.
                    val reason = if (dd.refusalReason ==
                        io.digibyte.core.recovery.DigiDollarTransferPlan.Reason.BELOW_FEE_FLOOR
                    ) {
                        stringResource(
                            R.string.rf_dd_fee_floor,
                            formatSatToDgb(io.digibyte.core.recovery.DigiDollarTransferPlan.DD_MIN_FEE_SATS),
                            formatSatToDgb(
                                (io.digibyte.core.recovery.DigiDollarTransferPlan.DD_MIN_FEE_SATS -
                                    dd.shortfallSat).coerceAtLeast(0L)
                            ),
                        )
                    } else dd.failureReason
                    reason?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            color = MUTED,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetMoveSection(
    moves: List<io.digibyte.core.recovery.ForeignAssetTransferService.Move>,
) {
    val moved = moves.count { it.moved }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CARD)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Text(
                text = stringResource(R.string.rf_move_result, moved, moves.size),
                color = if (moved == moves.size) SUCCESS_GREEN else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // Every asset that did NOT move is named with its reason. A summary count alone
            // would let a partial failure read as a clean run.
            moves.filter { !it.moved }.forEach { failed ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.rf_move_failed,
                        failed.outpoint,
                        failed.failureReason ?: "",
                    ),
                    color = WARNING_RED,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
@Composable
private fun OutcomeCard(
    outcome: LegacySweepService.SweepOutcome,
    assetMoves: List<io.digibyte.core.recovery.ForeignAssetTransferService.Move> = emptyList(),
) {
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

            // Gated on the txid EXISTING, not on "succeeded". A sweep can legitimately succeed
            // with nothing to show: when every outpoint was claimed by the DigiAsset or
            // DigiDollar moves that ran before it, the sweep has no inputs, reports PENDING and
            // carries no txid. Asserting one here crashed the results screen on a real recovery
            // whose DigiDollar transfer consumed both DGB outputs.
            val txid = outcome.txid
            if (succeeded && txid != null) {
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
            // Only assets that did NOT move. The sweep excludes every asset-bearing outpoint —
            // spending one as plain DGB destroys it — and under the old order "excluded from the
            // sweep" and "left behind" were the same list. Since assets move FIRST now they are
            // not, and reading one as the other printed "2 left behind" directly above "2 of 2
            // assets moved" on a real mainnet run. See RecoverySequence.assetsLeftBehind.
            val leftBehind = io.digibyte.core.recovery.RecoverySequence.assetsLeftBehind(
                assetBearing = outcome.heldBackAssets,
                moves = assetMoves,
            )
            if (leftBehind.isNotEmpty()) {
                HeldBackNote(
                    title = stringResource(R.string.rf_held_assets, leftBehind.size),
                    body = stringResource(R.string.rf_held_assets_body),
                    outpoints = leftBehind,
                )
            }
            // The fee-reserve and shortfall notes are gone with AssetFeeReserve. Assets now move
            // BEFORE the sweep, so there is nothing to hold back in advance and nothing to be
            // short of — an asset that could not be funded says so on its own row above.
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
private fun PhraseEntry(
    phrase: String,
    onPhrase: (String) -> Unit,
    error: String?,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    onScan: () -> Unit,
) {
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
            modifier = Modifier.fillMaxWidth().semantics { password() },
            minLines = 3,
            label = { Text(stringResource(R.string.rf_phrase_hint)) },
            isError = error != null,
            // Password keyboard type, no visualTransformation: the IME must not learn a foreign
            // recovery phrase, but the user must be able to read what they pasted or typed.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
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
        Spacer(Modifier.height(10.dp))

        // Optional BIP39 passphrase. Labelled so that leaving it blank is obviously correct for
        // the overwhelming majority of phrases — a field that looks required would have people
        // inventing a passphrase and restoring an empty wallet.
        OutlinedTextField(
            value = passphrase,
            onValueChange = onPassphrase,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.pass_restore_label)) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ACCENT,
                unfocusedBorderColor = DIVIDER,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = ACCENT,
            ),
            shape = RoundedCornerShape(8.dp),
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
