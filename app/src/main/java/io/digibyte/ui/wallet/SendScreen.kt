package io.digibyte.ui.wallet

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.isTestnet
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.BiometricResult
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import io.digibyte.R

@Composable
fun SendScreen(
    biometricAuth: BiometricAuth,
    onNavigateBack: () -> Unit,
    prefillAddress: String = "",
    onScanQr: ((String) -> Unit) -> Unit = {},
    viewModel: SendViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    // Pre-fill address from QR scan
    LaunchedEffect(prefillAddress) {
        if (prefillAddress.isNotBlank()) {
            viewModel.onAddressChanged(prefillAddress)
        }
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // DigiDollar address prefix follows the running network (TD… testnet / DD… mainnet).
    val onTestnet = remember { isTestnet(context) }

    val address by viewModel.address.collectAsStateWithLifecycle()
    val addressValid by viewModel.addressValid.collectAsStateWithLifecycle()
    val amountDgb by viewModel.amountDgb.collectAsStateWithLifecycle()
    val amountFiat by viewModel.amountFiat.collectAsStateWithLifecycle()
    val isCustomFee by viewModel.isCustomFee.collectAsStateWithLifecycle()
    val customFeeInput by viewModel.customFeeInput.collectAsStateWithLifecycle()
    val estimatedFeeSat by viewModel.estimatedFeeSat.collectAsStateWithLifecycle()
    val feeWarning by viewModel.feeWarning.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val validationError by viewModel.validationError.collectAsStateWithLifecycle()
    val peerCount by walletViewModel.peerCount.collectAsStateWithLifecycle()
    val syncProgressInfo by walletViewModel.syncProgressInfo.collectAsStateWithLifecycle()
    // Hoisted: used inside coroutine/callback lambdas, which are not composable.
    val bioConfirmTitle = stringResource(R.string.send_confirm_title)
    val bioAuthSubtitle = stringResource(R.string.send_auth_subtitle)
    val bioConfirmDdTitle = stringResource(R.string.send_confirm_dd_title)
    val ddFailedMsg = stringResource(R.string.send_dd_failed)
    val hasPeers = peerCount > 0
    val isFullySynced = syncProgressInfo.stage == io.digibyte.core.model.SyncStage.Synced
    val canSend = hasPeers && isFullySynced

    // ── DigiDollar send mode ──────────────────────────────────────────────
    val ddBalance by viewModel.ddBalance.collectAsStateWithLifecycle()
    val ddAddressValid by viewModel.ddAddressValid.collectAsStateWithLifecycle()
    // Mode is auto-detected from the destination: a valid DD… address puts the
    // whole screen into DigiDollar-send mode. No manual toggle — the address type
    // is unambiguous (DD…/TD… vs dgb1q…/D…), and this also lets a DD send be
    // composed at $0 balance so the amount field can say "insufficient" clearly
    // instead of the address reading as an invalid DigiByte address.
    val effectiveDdMode = ddAddressValid == true
    val effectiveAddressValid = if (effectiveDdMode) ddAddressValid else addressValid
    var ddSentTxid by remember { mutableStateOf<String?>(null) }
    var ddSending by remember { mutableStateOf(false) }
    var ddConfirming by remember { mutableStateOf(false) }

    var inputIsDgb by remember { mutableStateOf(true) }

    // ── Failure overlay ───────────────────────────────────────────────────
    // Given the SAME weight as success, deliberately. This used to be one line of bodySmall red
    // text at the bottom of the form, below the button and below the fold if the form happened to
    // be scrolled, with nothing to dismiss. Success meanwhile blocked the whole app until
    // acknowledged. The quiet outcome was the one that mattered: reproduced on a Note 8, a failed
    // send was indistinguishable from nothing having happened, which is how three real sends came
    // to be described as having "not registered anywhere".
    if (sendState is SendState.Error) {
        SendFailureScreen(
            failure = io.digibyte.core.send.SendFailure.of((sendState as SendState.Error).message),
            onDismiss = { viewModel.resetState() },
        )
        return
    }

    // ── Success overlay ───────────────────────────────────────────────────
    if (sendState is SendState.Success || ddSentTxid != null) {
        SendSuccessScreen(
            txid = (sendState as? SendState.Success)?.txid ?: ddSentTxid!!,
            onDone = {
                viewModel.resetState()
                ddSentTxid = null
                onNavigateBack()
            }
        )
        return
    }

    // ── Confirmation dialog ───────────────────────────────────────────────
    if (sendState is SendState.Confirming) {
        SendConfirmationDialog(
            address = address,
            amountDgb = amountDgb,
            amountFiat = amountFiat,
            feeEstimate = estimatedFeeSat,
            onConfirm = {
                coroutineScope.launch {
                    val activity = context as? androidx.fragment.app.FragmentActivity
                    if (activity != null && biometricAuth.canAuthenticate(activity)) {
                        val result = biometricAuth.authenticate(
                            activity,
                            title = bioConfirmTitle,
                            subtitle = bioAuthSubtitle
                        )
                        if (result is BiometricResult.Success) {
                            viewModel.send()
                        } else if (result is BiometricResult.Error) {
                            viewModel.cancelConfirm()
                        }
                    } else {
                        // No biometric — proceed directly (PIN fallback handled by system)
                        viewModel.send()
                    }
                }
            },
            onCancel = { viewModel.cancelConfirm() }
        )
    }

    // ── DigiDollar confirmation dialog ────────────────────────────────────
    // DD sends get the same confirm + biometric gate as DGB — a real-value
    // transfer must not broadcast straight from a button tap (finality parity).
    if (ddConfirming) {
        DigiDollarConfirmationDialog(
            address = address,
            amountUsd = amountFiat,
            onConfirm = {
                // Synchronous re-entrancy guard: the first tap flips ddConfirming
                // false immediately, so a second tap already queued on this button
                // no-ops. (State set inside the coroutine below runs too late to
                // debounce an already-dispatched tap — hence the guard out here.)
                if (ddConfirming) {
                    ddConfirming = false
                    coroutineScope.launch {
                        val activity = context as? androidx.fragment.app.FragmentActivity
                        val authed = if (activity != null && biometricAuth.canAuthenticate(activity)) {
                            biometricAuth.authenticate(
                                activity,
                                title = bioConfirmDdTitle,
                                subtitle = bioAuthSubtitle
                            ) is BiometricResult.Success
                        } else {
                            // No biometric hardware — the confirmation dialog is the gate (matches DGB).
                            true
                        }
                        if (!authed) return@launch   // dialog already dismissed; back to the form
                        ddSending = true
                        viewModel.sendDigiDollar(address, amountFiat) { txid ->
                            // sendDigiDollar's callback fires on a background dispatcher —
                            // hop back to Main before touching Compose state or Toast.
                            coroutineScope.launch {
                                ddSending = false
                                if (txid != null) {
                                    ddSentTxid = txid
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        ddFailedMsg,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
            },
            onCancel = { ddConfirming = false }
        )
    }

    // ── Send form ─────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = if (effectiveDdMode) stringResource(R.string.send_title_dd) else stringResource(R.string.send_title_dgb),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Address input ─────────────────────────────────────────────────
        Text(
            text = if (effectiveDdMode) {
                if (onTestnet) stringResource(R.string.send_recipient_dd_testnet) else stringResource(R.string.send_recipient_dd)
            } else stringResource(R.string.send_recipient_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        val addressBorderColor = when (effectiveAddressValid) {
            true  -> DigiByteGreen
            false -> DigiByteRed
            null  -> MaterialTheme.colorScheme.outline
        }

        OutlinedTextField(
            value = address,
            onValueChange = { viewModel.onAddressChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, addressBorderColor, RoundedCornerShape(8.dp)),
            placeholder = {
                Text(
                    if (effectiveDdMode) (if (onTestnet) "TD…" else "DD…") else stringResource(R.string.send_hint_dgb),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                Row {
                    // Paste
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val text = cb?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.isNotBlank()) viewModel.onAddressChanged(text)
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.send_paste),
                             tint = DigiByteAccent)
                    }
                    // Scan QR
                    IconButton(onClick = {
                        onScanQr { scanned -> viewModel.applyScannedUri(scanned) }
                    }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.send_scan_qr),
                             tint = DigiByteAccent)
                    }
                }
            },
            singleLine = true,
            isError = effectiveAddressValid == false,
            shape = RoundedCornerShape(8.dp)
        )

        if (effectiveAddressValid == false) {
            Text(
                text = if (effectiveDdMode) stringResource(R.string.send_invalid_dd) else stringResource(R.string.send_invalid_dgb),
                style = MaterialTheme.typography.labelSmall,
                color = DigiByteRed,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Amount input ──────────────────────────────────────────────────
        if (effectiveDdMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.send_amount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { viewModel.setDdAmountToMax() },
                    enabled = ddBalance > 0
                ) {
                    Text(
                        text = stringResource(R.string.send_max),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ddBalance > 0) DigiByteAccent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = amountFiat,
                onValueChange = { viewModel.onAmountFiatChanged(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0.00") },
                prefix = { Text("$", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Available DigiDollar ceiling + inline amount validation (mirrors the
            // send button's ddAmountValid gate so the error and the disabled state agree).
            Text(
                text = stringResource(R.string.send_available_dd, SendViewModel.formatDdUsd(ddBalance)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val ddCents = SendViewModel.parseUsdToCents(amountFiat)
            val ddAmountError: String? = when {
                amountFiat.isBlank()                 -> null
                ddCents == null                      -> stringResource(R.string.send_err_invalid_amount)
                ddBalance <= 0L                      -> stringResource(R.string.send_err_no_dd)
                ddCents < SendViewModel.DD_MIN_CENTS -> stringResource(R.string.send_err_min_usd)
                ddCents > ddBalance                  -> stringResource(R.string.send_err_exceeds_dd)
                ddCents > SendViewModel.DD_MAX_CENTS -> stringResource(R.string.send_err_exceeds_max)
                else                                 -> null
            }
            if (ddAmountError != null) {
                Text(
                    text = ddAmountError,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteRed,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.send_fee_in_dgb),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.send_amount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { inputIsDgb = !inputIsDgb }) {
                    Text(
                        text = stringResource(if (inputIsDgb) R.string.send_switch_to_usd else R.string.send_switch_to_dgb),
                        style = MaterialTheme.typography.labelMedium,
                        color = DigiByteAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (inputIsDgb) {
                OutlinedTextField(
                    value = amountDgb,
                    onValueChange = { viewModel.onAmountDgbChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.00000000") },
                    suffix = { Text("DGB", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                if (amountFiat.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.send_approx_usd, amountFiat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            } else {
                OutlinedTextField(
                    value = amountFiat,
                    onValueChange = { viewModel.onAmountFiatChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.00") },
                    prefix = { Text("$", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                if (amountDgb.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.send_approx_dgb, amountDgb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Network fee ──────────────────────────────────────────────────
        // DD sends still pay their fee in DGB, but there's no fee tier/custom
        // input to show — the helper line above already covers it.
        if (!effectiveDdMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.send_network_fee),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.toggleCustomFee() }) {
                    Text(
                        text = if (isCustomFee) stringResource(R.string.send_fee_default) else stringResource(R.string.send_fee_custom),
                        style = MaterialTheme.typography.labelMedium,
                        color = DigiByteAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isCustomFee) {
                OutlinedTextField(
                    value = customFeeInput,
                    onValueChange = { viewModel.customFeeInput.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.00014100") },
                    suffix = { Text("DGB", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    isError = feeWarning is FeeWarning.ZeroFee
                )
            } else {
                val defaultFeeDgb = viewModel.defaultFeeSat / 100_000_000.0
                Text(
                    text = String.format("%.8f DGB", defaultFeeDgb),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            when (feeWarning) {
                is FeeWarning.BelowRelay -> {
                    Text(
                        text = stringResource(R.string.send_fee_below_min),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFA000)
                    )
                }
                is FeeWarning.ZeroFee -> {
                    Text(
                        text = stringResource(R.string.send_fee_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = DigiByteRed
                    )
                }
                is FeeWarning.None -> {
                    Text(
                        text = stringResource(R.string.send_confirms_15s),
                        style = MaterialTheme.typography.labelSmall,
                        color = DigiByteGreen
                    )
                }
            }

            // ── Validation error ──────────────────────────────────────────
            if (validationError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = validationError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = DigiByteRed
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Peer connectivity banner ──────────────────────────────────────
        // If the SPV peer manager has zero peers, a broadcast would never
        // propagate — the tx would sit in the local wallet marked "sent"
        // with nothing to relay it. Gate the button and say so explicitly.
        if (!hasPeers) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FFAA00), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = Color(0xFFFFAA00),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.send_no_peers),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFCC66)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else if (!isFullySynced) {
            // Wallet has peers but the bloom-filter rescan hasn't finished
            // yet. Sending now would build the tx against an incomplete
            // UTXO set — change calculation, fee estimate, and even basic
            // balance can be wrong until the scan reaches chain tip.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FFAA00), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.HourglassEmpty,
                    contentDescription = null,
                    tint = Color(0xFFFFAA00),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.send_wait_for_sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFCC66)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Confirm button ────────────────────────────────────────────────
        if (effectiveDdMode) {
            val ddCents = SendViewModel.parseUsdToCents(amountFiat) ?: -1L
            val ddValid = ddAddressValid == true && SendViewModel.ddAmountValid(ddCents, ddBalance)
            Button(
                // Gate DD sends behind the same confirm + biometric flow as DGB
                // (finality parity) instead of broadcasting straight from the tap.
                onClick = { ddConfirming = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !ddSending && !ddConfirming && canSend && ddValid,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (ddSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.send_broadcasting))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.send_title_dd), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        } else {
            val isSending = sendState is SendState.Sending
            Button(
                onClick = { viewModel.requestConfirm() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isSending && canSend,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.send_broadcasting))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.send_review), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // The inline error line lived here. It is now a blocking overlay — see the failure
            // overlay at the top of SendScreen.
        }
    }
}

// ── Confirmation dialog ──────────────────────────────────────────────────────

@Composable
private fun SendConfirmationDialog(
    address: String,
    amountDgb: String,
    amountFiat: String,
    feeEstimate: Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val view = LocalView.current
    // Security: filter touches when obscured (tapjacking protection)
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = false }
    }

    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.send_dialog_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(20.dp))

                ConfirmRow(label = stringResource(R.string.send_to), value = address) // FULL address — never truncated
                ConfirmRow(label = stringResource(R.string.send_amount), value = "$amountDgb DGB")
                if (amountFiat.isNotBlank()) {
                    ConfirmRow(label = stringResource(R.string.send_approx_usd_label), value = "$$amountFiat")
                }
                val feeDgb = feeEstimate / 100_000_000.0
                ConfirmRow(
                    label = stringResource(R.string.send_network_fee_row),
                    value = String.format("%.8f DGB", feeDgb)
                )

                Spacer(modifier = Modifier.height(8.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.send_verify_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.send_send), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DigiDollarConfirmationDialog(
    address: String,
    amountUsd: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val view = LocalView.current
    // Security: filter touches when obscured (tapjacking protection) — same as DGB.
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = false }
    }

    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.send_dd_dialog_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(20.dp))

                ConfirmRow(label = stringResource(R.string.send_to), value = address) // FULL address — never truncated
                ConfirmRow(label = stringResource(R.string.send_amount), value = stringResource(R.string.send_amount_dd, amountUsd))
                ConfirmRow(label = stringResource(R.string.send_network_fee_row), value = stringResource(R.string.send_paid_in_dgb))

                Spacer(modifier = Modifier.height(8.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.send_verify_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.send_send), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ── Outcome screens ──────────────────────────────────────────────────────────
// Failure and success are both full-screen and both must be dismissed. That symmetry is the
// point: see SendFailureScreen.

/**
 * A send that did not go out, shown with the same prominence as one that did.
 *
 * Blocking and dismissible on purpose: an outcome the user can walk past without noticing is an
 * outcome they will assume went the other way. [io.digibyte.core.send.SendFailure] decides what
 * happened and whether trying again could help; this only renders it.
 */
@Composable
private fun SendFailureScreen(
    failure: io.digibyte.core.send.SendFailure,
    onDismiss: () -> Unit,
) {
    // Switched on the enum rather than the guidance key: string literals in UI code are what the
    // untranslated-literal gate is looking for, and matching on a key would have meant teaching
    // that gate to ignore exactly the kind of literal it exists to catch.
    val guidance = when (failure.kind) {
        io.digibyte.core.send.SendFailure.Kind.INSUFFICIENT -> stringResource(R.string.send_fail_insufficient_fee)
        io.digibyte.core.send.SendFailure.Kind.BROADCAST -> stringResource(R.string.send_fail_broadcast)
        io.digibyte.core.send.SendFailure.Kind.SIGNING -> stringResource(R.string.send_fail_signing)
        io.digibyte.core.send.SendFailure.Kind.ADDRESS -> stringResource(R.string.send_fail_address)
        io.digibyte.core.send.SendFailure.Kind.AMOUNT -> stringResource(R.string.send_fail_amount)
        io.digibyte.core.send.SendFailure.Kind.UNKNOWN -> stringResource(R.string.send_fail_unknown)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = DigiByteRed,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.send_fail_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Said plainly, because the first thing anyone needs to know is that their coins did not
        // move and are still theirs.
        Text(
            text = stringResource(R.string.send_fail_nothing_sent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = guidance,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // The raw reason, kept for the failures nobody anticipated — it is what a bug report has
        // to contain, and it is the only clue when the classification falls through to UNKNOWN.
        if (failure.kind == io.digibyte.core.send.SendFailure.Kind.UNKNOWN) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = failure.rawReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (failure.retryable) R.string.send_fail_try_again else R.string.common_done
                ),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SendSuccessScreen(txid: String, onDone: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.send_success),
            tint = DigiByteGreen,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.send_sent_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.send_txid_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Text(
                text = txid,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = DigiByteAccent
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(android.content.ClipData.newPlainText("txid", txid))
        }) {
            Icon(Icons.Default.ContentCopy, contentDescription = null,
                 modifier = Modifier.size(16.dp), tint = DigiByteAccent)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.send_copy_txid), color = DigiByteAccent)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
