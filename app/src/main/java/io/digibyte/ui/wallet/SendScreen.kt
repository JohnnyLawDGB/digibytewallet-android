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
    onScanQr: ((String) -> Unit) -> Unit = {},
    viewModel: SendViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val address by viewModel.address.collectAsStateWithLifecycle()
    val addressValid by viewModel.addressValid.collectAsStateWithLifecycle()
    val amountDgb by viewModel.amountDgb.collectAsStateWithLifecycle()
    val amountFiat by viewModel.amountFiat.collectAsStateWithLifecycle()
    val selectedFeeTier by viewModel.selectedFeeTier.collectAsStateWithLifecycle()
    val feeEstimate by viewModel.feeEstimate.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val validationError by viewModel.validationError.collectAsStateWithLifecycle()

    var inputIsDgb by remember { mutableStateOf(true) }

    // ── Success overlay ───────────────────────────────────────────────────
    if (sendState is SendState.Success) {
        SendSuccessScreen(
            txid = (sendState as SendState.Success).txid,
            onDone = {
                viewModel.resetState()
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
            feeEstimate = feeEstimate,
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Send DGB",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Address input ─────────────────────────────────────────────────
        Text(
            text = "Recipient Address",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        val addressBorderColor = when (addressValid) {
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
            placeholder = { Text("dgb1q… or D…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
            isError = addressValid == false,
            shape = RoundedCornerShape(8.dp)
        )

        if (addressValid == false) {
            Text(
                text = "Invalid DigiByte address",
                style = MaterialTheme.typography.labelSmall,
                color = DigiByteRed,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Amount input ──────────────────────────────────────────────────
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

        Spacer(modifier = Modifier.height(24.dp))

        // ── Fee tier selector ─────────────────────────────────────────────
        Text(
            text = "Network Fee",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0, 1, 2).forEach { tier ->
                FeeTierChip(
                    label = viewModel.feeTierLabel(tier),
                    satPerKb = viewModel.feeTierSatPerKb(tier),
                    selected = selectedFeeTier == tier,
                    onClick = { viewModel.selectedFeeTier.value = tier },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val feeKb = feeEstimate / 1000.0
        Text(
            text = "Estimated: ${String.format("%.0f", feeKb.coerceAtLeast(0.001) * 1000)} sat/KB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Validation error ──────────────────────────────────────────────
        if (validationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = validationError!!,
                style = MaterialTheme.typography.bodySmall,
                color = DigiByteRed
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Confirm button ────────────────────────────────────────────────
        val isSending = sendState is SendState.Sending
        Button(
            onClick = { viewModel.requestConfirm() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isSending,
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

// ── Fee tier chip ────────────────────────────────────────────────────────────

@Composable
private fun FeeTierChip(
    label: String,
    satPerKb: Long,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) DigiByteAccent.copy(alpha = 0.18f)
                         else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) DigiByteAccent else MaterialTheme.colorScheme.outline

    Surface(
        onClick = onClick,
        modifier = modifier.border(1.dp, borderColor, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) DigiByteAccent else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = "${satPerKb / 1000} sat/B",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
