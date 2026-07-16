package io.digibyte.ui.asset

import android.content.ClipboardManager
import android.content.Context
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
import io.digibyte.core.model.OwnedAsset
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteNavy
import io.digibyte.ui.theme.DigiByteRed

@Composable
fun AssetSendScreen(
    assetId: String,
    onNavigateBack: () -> Unit,
    viewModel: AssetViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(assetId) {
        viewModel.selectAsset(assetId)
    }

    val asset by viewModel.selectedAsset.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val isCustomFee by viewModel.isCustomFee.collectAsStateWithLifecycle()
    val customFeeInput by viewModel.customFeeInput.collectAsStateWithLifecycle()
    val estimatedFeeSat by viewModel.estimatedFeeSat.collectAsStateWithLifecycle()
    val feeWarning by viewModel.feeWarning.collectAsStateWithLifecycle()
    val feeRatePerKb by viewModel.feeRatePerKb.collectAsStateWithLifecycle()

    var recipientAddress by remember { mutableStateOf("") }
    var quantityInput by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var quantityError by remember { mutableStateOf<String?>(null) }

    // Close confirm dialog once the send either succeeds or fails so the
    // user sees the terminal state banner rendered below the form.
    LaunchedEffect(sendState) {
        if (sendState is AssetViewModel.SendState.Success ||
            sendState is AssetViewModel.SendState.Failure) {
            showConfirmDialog = false
        }
    }

    // ── Confirmation dialog ───────────────────────────────────────────────
    if (showConfirmDialog && asset != null) {
        AssetSendConfirmDialog(
            asset = asset!!,
            recipientAddress = recipientAddress,
            quantityInput = quantityInput,
            feeSats = estimatedFeeSat,
            sending = sendState is AssetViewModel.SendState.Sending,
            onConfirm = {
                viewModel.sendAssetTransfer(
                    toAddress = recipientAddress,
                    quantityInput = quantityInput,
                    feePerKb = feeRatePerKb,
                )
            },
            onCancel = {
                showConfirmDialog = false
                viewModel.resetSendState()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Send Asset",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Terminal result banner (success / failure) ───────────────────
        when (val s = sendState) {
            is AssetViewModel.SendState.Success -> SendResultBanner(
                success = true,
                title = "Transaction broadcast",
                detail = "txid ${s.txid.take(12)}…${s.txid.takeLast(8)}",
                onDismiss = { viewModel.resetSendState() }
            )
            is AssetViewModel.SendState.Failure -> SendResultBanner(
                success = false,
                title = "Send failed",
                detail = s.message,
                onDismiss = { viewModel.resetSendState() }
            )
            else -> {}
        }
        if (sendState !is AssetViewModel.SendState.Idle) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Asset info header ────────────────────────────────────────────
        asset?.let { ownedAsset ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Stars,
                        contentDescription = null,
                        tint = DigiByteAccent,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = ownedAsset.metadata?.name
                                ?: ownedAsset.assetId.take(12) + "…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Balance: ${formatAssetQuantity(ownedAsset.quantity, ownedAsset.metadata?.decimals ?: 0)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Recipient address ─────────────────────────────────────────
            Text(
                text = "Recipient Address",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            val addressBorderColor = when {
                addressError != null -> DigiByteRed
                recipientAddress.isNotBlank() && addressError == null -> DigiByteGreen
                else -> MaterialTheme.colorScheme.outline
            }

            OutlinedTextField(
                value = recipientAddress,
                onValueChange = {
                    recipientAddress = it
                    addressError = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, addressBorderColor, RoundedCornerShape(8.dp)),
                placeholder = {
                    Text("dgb1q… or D…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val text = cb?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotBlank()) {
                                recipientAddress = text
                                addressError = null
                            }
                        }) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = DigiByteAccent
                            )
                        }
                        IconButton(onClick = { /* QR scan: Phase 2 Task 9 */ }) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                tint = DigiByteAccent
                            )
                        }
                    }
                },
                isError = addressError != null,
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            if (addressError != null) {
                Text(
                    text = addressError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteRed,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Quantity input ────────────────────────────────────────────
            val decimals = ownedAsset.metadata?.decimals ?: 0
            Text(
                text = "Quantity",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = quantityInput,
                onValueChange = {
                    quantityInput = it
                    quantityError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (decimals > 0) "0.${"0".repeat(decimals)}" else "0",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    // Max — convenience for sending the entire balance.
                    // Renders the user's quantity in the asset's display
                    // decimals so the input round-trips through the same
                    // scale-to-internal logic the ViewModel uses on submit.
                    TextButton(
                        onClick = {
                            quantityInput = formatBalanceForInput(
                                ownedAsset.quantity, decimals,
                            )
                            quantityError = null
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(
                            "MAX",
                            color = DigiByteAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                },
                suffix = {
                    Text(
                        text = ownedAsset.metadata?.symbol ?: "tokens",
                        color = DigiByteAccent,
                        fontWeight = FontWeight.Bold
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number
                ),
                isError = quantityError != null,
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            if (quantityError != null) {
                Text(
                    text = quantityError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteRed,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── DGB network fee ───────────────────────────────────────────
            // An asset send is a regular DGB transaction (pins an asset UTXO
            // + carries an OP_RETURN marker), so the fee is a regular DGB
            // fee: default 100 sat/byte (min relay, ~15s confirm) with an
            // optional custom TOTAL-DGB override — identical UX to Send DGB.
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
            Text(
                text = "Asset transactions require DGB for network fees",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            if (isCustomFee) {
                OutlinedTextField(
                    value = customFeeInput,
                    onValueChange = { viewModel.customFeeInput.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.00040000") },
                    suffix = { Text("DGB", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    isError = feeWarning is AssetFeeWarning.ZeroFee
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
                is AssetFeeWarning.BelowRelay -> Text(
                    text = "⚠ Below minimum relay fee — transaction may not broadcast",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFA000)
                )
                is AssetFeeWarning.ZeroFee -> Text(
                    text = "Fee required",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteRed
                )
                is AssetFeeWarning.None -> Text(
                    text = "Confirms in ~15 seconds",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteGreen
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── DGB cost preview ─────────────────────────────────────────
            // Updates as the user types quantity / edits the fee so the DGB
            // outflow is visible before the confirm dialog ever opens.
            CostPreviewCard(
                quantityInput = quantityInput,
                ownedAsset = ownedAsset,
                feeSats = estimatedFeeSat,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── Review button ─────────────────────────────────────────────
            Button(
                onClick = {
                    var valid = true
                    if (recipientAddress.isBlank()) {
                        addressError = "Enter a recipient address"
                        valid = false
                    }
                    if (quantityInput.isBlank() || quantityInput.toDoubleOrNull() == null ||
                        quantityInput.toDouble() <= 0) {
                        quantityError = "Enter a valid quantity"
                        valid = false
                    }
                    if (valid) showConfirmDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Review & Send", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } ?: run {
            // Asset not loaded yet
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = DigiByteAccent)
            }
        }
    }
}

// ── Confirmation dialog ──────────────────────────────────────────────────────

@Composable
private fun AssetSendConfirmDialog(
    asset: OwnedAsset,
    recipientAddress: String,
    quantityInput: String,
    feeSats: Long,
    sending: Boolean,
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
                    text = "Confirm Asset Send",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(20.dp))

                AssetConfirmRow(
                    label = "Asset",
                    value = asset.metadata?.name ?: asset.assetId.take(12) + "…"
                )
                AssetConfirmRow(label = "Quantity", value = "$quantityInput ${asset.metadata?.symbol ?: "tokens"}")
                // Full address — never truncated per security requirement
                AssetConfirmRow(label = "To", value = recipientAddress)
                AssetConfirmRow(
                    label = "Network fee",
                    value = String.format("%.8f DGB", feeSats / 100_000_000.0)
                )

                Spacer(modifier = Modifier.height(12.dp))
                CostPreviewCard(
                    quantityInput = quantityInput,
                    ownedAsset = asset,
                    feeSats = feeSats,
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Verify the full address above carefully.\nAsset transfers are irreversible.",
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
                        modifier = Modifier.weight(1f),
                        enabled = !sending,
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = !sending,
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Text("Confirm", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ── Terminal result banner (success/failure) ────────────────────────────────

@Composable
private fun SendResultBanner(
    success: Boolean,
    title: String,
    detail: String,
    onDismiss: () -> Unit,
) {
    val bg = if (success) DigiByteGreen.copy(alpha = 0.15f) else DigiByteRed.copy(alpha = 0.15f)
    val accent = if (success) DigiByteGreen else DigiByteRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = accent)
            }
        }
    }
}

@Composable
private fun AssetConfirmRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
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

// ── Helpers ──────────────────────────────────────────────────────────────────

private const val DA_MARKER_SATS_UI: Long = 700L

/** Render an internal-units quantity back to a decimal string suitable
 *  for the OutlinedTextField. Keeps round-trip parity with the
 *  ViewModel's `scaleToInternalUnits` so MAX → submit is exact. */
private fun formatBalanceForInput(quantity: Long, decimals: Int): String {
    if (decimals <= 0) return quantity.toString()
    val scaled = java.math.BigDecimal(quantity).movePointLeft(decimals).stripTrailingZeros()
    // BigDecimal.toString may produce "1E+1" for trailing zeros; toPlainString avoids that.
    return scaled.toPlainString()
}

/**
 * Card showing the DGB outflow breakdown for the in-progress send.
 *
 * Mirrors the math the AssetCoinSelector + sendAsset path uses on submit:
 *   - 1 marker (700 sats) for the recipient
 *   - 1 marker (700 sats) for asset change, only if the user is sending
 *     less than their full balance for this asset
 *   - The selected fee tier's estimated network fee
 *
 * Doesn't account for DGB-fee-input contribution (the 700 sats already in
 * the asset UTXO partly funds the markers); that's a wash from the user's
 * perspective and complicates the display, so we surface gross outflow.
 */
@Composable
private fun CostPreviewCard(
    quantityInput: String,
    ownedAsset: io.digibyte.core.model.OwnedAsset,
    feeSats: Long,
) {
    val decimals = ownedAsset.metadata?.decimals ?: 0
    val typedInternalQty = parseQuantityToInternal(quantityInput, decimals)

    // Asset change emitted iff user is sending less than their full balance
    // (and the input parsed as a valid positive number).
    val needsAssetChange = typedInternalQty != null &&
        typedInternalQty in 1 until ownedAsset.quantity
    val markerCount = if (needsAssetChange) 2 else 1
    val markerSats = DA_MARKER_SATS_UI * markerCount
    val totalSats = markerSats + feeSats

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "DGB cost preview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            CostRow("Recipient marker", "${formatSats(DA_MARKER_SATS_UI)} sats")
            if (needsAssetChange) {
                CostRow("Asset-change marker", "${formatSats(DA_MARKER_SATS_UI)} sats")
            }
            CostRow("Network fee (est.)", "≈ ${formatSats(feeSats)} sats")
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(6.dp))
            CostRow(
                label = "Total DGB",
                value = "≈ ${formatSats(totalSats)} sats (${formatDgb(totalSats)} DGB)",
                emphasize = true,
            )
            // Partial-transfer hint — shows the user the change UTXO they'll
            // hold after the send so the new partial path doesn't surprise.
            if (needsAssetChange && typedInternalQty != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val keptInternal = ownedAsset.quantity - typedInternalQty
                val symbol = ownedAsset.metadata?.symbol ?: "units"
                Text(
                    text = "${formatAssetQuantity(keptInternal, decimals)} $symbol stays in your wallet",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent,
                )
            }
        }
    }
}

@Composable
private fun CostRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Parse a user's decimal string to internal asset units. Mirrors
 *  AssetViewModel.scaleToInternalUnits but lives in the Composable layer
 *  so the cost preview can react before the user hits Review. Returns
 *  null on any parse error, including more decimals than the asset
 *  supports. */
private fun parseQuantityToInternal(input: String, decimals: Int): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val decimal = trimmed.toBigDecimalOrNull() ?: return null
    if (decimal.signum() < 0) return null
    if (decimal.scale() > decimals) return null
    val scaled = decimal.movePointRight(decimals)
        .setScale(0, java.math.RoundingMode.UNNECESSARY)
    return try {
        scaled.longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

private fun formatSats(sats: Long): String = "%,d".format(sats)
private fun formatDgb(sats: Long): String =
    java.text.DecimalFormat("0.########").format(sats / 100_000_000.0)
