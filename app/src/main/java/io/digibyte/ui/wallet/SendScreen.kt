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

    var inputIsDgb by remember { mutableStateOf(true) }

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
                            title = "Confirm Send",
                            subtitle = "Authenticate to broadcast transaction"
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (effectiveDdMode) "Send DigiDollar" else "Send DGB",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Address input ─────────────────────────────────────────────────
        Text(
            text = if (effectiveDdMode) {
                if (onTestnet) "DigiDollar address (TD…)" else "DigiDollar address (DD…)"
            } else "Recipient Address",
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
                    if (effectiveDdMode) (if (onTestnet) "TD…" else "DD…") else "dgb1q… or D…",
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
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste",
                             tint = DigiByteAccent)
                    }
                    // Scan QR
                    IconButton(onClick = {
                        onScanQr { scanned -> viewModel.applyScannedUri(scanned) }
                    }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR",
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
                text = if (effectiveDdMode) "Invalid DigiDollar address" else "Invalid DigiByte address",
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
                    text = "Amount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { viewModel.setDdAmountToMax() },
                    enabled = ddBalance > 0
                ) {
                    Text(
                        text = "MAX",
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
                text = "Available: ${SendViewModel.formatDdUsd(ddBalance)} DigiDollar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val ddCents = SendViewModel.parseUsdToCents(amountFiat)
            val ddAmountError: String? = when {
                amountFiat.isBlank()                 -> null
                ddCents == null                      -> "Enter a valid amount"
                ddBalance <= 0L                      -> "You have no DigiDollar to send"
                ddCents < SendViewModel.DD_MIN_CENTS -> "Minimum is $1.00"
                ddCents > ddBalance                  -> "Exceeds available DigiDollar"
                ddCents > SendViewModel.DD_MAX_CENTS -> "Exceeds maximum per transaction"
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
                text = "Network fee paid in DGB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Amount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { inputIsDgb = !inputIsDgb }) {
                    Text(
                        text = if (inputIsDgb) "Switch to USD" else "Switch to DGB",
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
                        text = "≈ $$amountFiat USD",
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
                        text = "≈ $amountDgb DGB",
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
                    text = "Network Fee",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.toggleCustomFee() }) {
                    Text(
                        text = if (isCustomFee) "Default" else "Custom",
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
                        text = "⚠ Below minimum relay fee — transaction may not broadcast",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFA000)
                    )
                }
                is FeeWarning.ZeroFee -> {
                    Text(
                        text = "Fee required",
                        style = MaterialTheme.typography.labelSmall,
                        color = DigiByteRed
                    )
                }
                is FeeWarning.None -> {
                    Text(
                        text = "Confirms in ~15 seconds",
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
                    text = "No DigiByte peers connected — can't broadcast right now. Reconnecting…",
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
                    text = "Wait for sync to finish before sending — your full balance is still being recovered.",
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
                onClick = {
                    ddSending = true
                    viewModel.sendDigiDollar(address, amountFiat) { txid ->
                        // sendDigiDollar's callback fires on a background
                        // dispatcher — hop back to the composition's scope
                        // (Main) before touching Compose state or Toast.
                        coroutineScope.launch {
                            ddSending = false
                            if (txid != null) {
                                ddSentTxid = txid
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "DigiDollar send failed",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !ddSending && canSend && ddValid,
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
                    Text("Broadcasting…")
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send DigiDollar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    Text("Broadcasting…")
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Review & Send", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            if (sendState is SendState.Error) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = (sendState as SendState.Error).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = DigiByteRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
                    text = "Confirm Transaction",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(20.dp))

                ConfirmRow(label = "To", value = address) // FULL address — never truncated
                ConfirmRow(label = "Amount", value = "$amountDgb DGB")
                if (amountFiat.isNotBlank()) {
                    ConfirmRow(label = "≈ USD", value = "$$amountFiat")
                }
                val feeDgb = feeEstimate / 100_000_000.0
                ConfirmRow(
                    label = "Network fee",
                    value = String.format("%.8f DGB", feeDgb)
                )

                Spacer(modifier = Modifier.height(8.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Verify the full address above carefully.\nTransactions are irreversible.",
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
                        Text("Cancel")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Send", fontWeight = FontWeight.Bold)
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

// ── Success screen ───────────────────────────────────────────────────────────

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
            contentDescription = "Success",
            tint = DigiByteGreen,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Transaction Sent!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Transaction ID:",
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
            Text("Copy TXID", color = DigiByteAccent)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
