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
import io.digibyte.ui.components.rememberSpendAuth
import kotlinx.coroutines.launch
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteNavy
import io.digibyte.ui.theme.DigiByteRed
import androidx.compose.ui.res.stringResource
import io.digibyte.R

@Composable
fun AssetSendScreen(
    assetId: String,
    onNavigateBack: () -> Unit,
    prefillAddress: String = "",
    /** Raw asset units carried by a `digibyte:…?assetId=&assetAmount=` transfer request. */
    prefillQuantity: String = "",
    onScanQr: () -> Unit = {},
    viewModel: AssetViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val spendAuth = rememberSpendAuth()
    spendAuth.Dialogs()
    // Hoisted: used inside the confirm lambda, which is not composable.
    val authTitle = stringResource(R.string.send_confirm_title)
    val authSubtitle = stringResource(R.string.send_auth_subtitle)

    LaunchedEffect(assetId) {
        viewModel.selectAsset(assetId)
    }

    val asset by viewModel.selectedAsset.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    // Hoisted: used inside onClick lambdas and non-composable string fallbacks.
    val tokensLabel = stringResource(R.string.as_tokens)
    val errRecipient = stringResource(R.string.as_err_recipient)
    val errQuantity = stringResource(R.string.as_err_quantity)
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

    // Pre-fill the recipient from a scanned QR (routed back via the shared
    // qr_scanner → asset_send?address=…).
    LaunchedEffect(prefillAddress) {
        if (prefillAddress.isNotBlank()) {
            recipientAddress = prefillAddress
            addressError = null
        }
    }

    // Pre-fill the quantity from an asset transfer request. Pre-filled, NOT locked: the
    // request is untrusted input, so the user must still be able to see and change what
    // leaves their wallet before signing.
    LaunchedEffect(prefillQuantity) {
        if (prefillQuantity.isNotBlank()) {
            quantityInput = prefillQuantity
            quantityError = null
        }
    }

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
                coroutineScope.launch {
                    val activity = context as? androidx.fragment.app.FragmentActivity
                    if (spendAuth.authorize(activity, authTitle, authSubtitle)) {
                        viewModel.sendAssetTransfer(
                            toAddress = recipientAddress,
                            quantityInput = quantityInput,
                            feePerKb = feeRatePerKb,
                        )
                    } else {
                        showConfirmDialog = false
                        viewModel.resetSendState()
                    }
                }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = stringResource(R.string.as_send_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // What the screen should actually be showing. Extracted to core/asset so the rule
        // is unit-tested rather than only observable by sending a real asset on a device —
        // which is how the "still spinning after a successful send" report was found.
        val screenState = io.digibyte.core.asset.AssetSendScreenState.of(
            sendSucceeded = sendState is AssetViewModel.SendState.Success,
            assetLoaded = asset != null,
        )

        // ── Terminal result banner (success / failure) ───────────────────
        when (val s = sendState) {
            is AssetViewModel.SendState.Success -> SendResultBanner(
                success = true,
                title = stringResource(R.string.as_broadcast),
                detail = "txid ${s.txid.take(12)}…${s.txid.takeLast(8)}",
                onDismiss = { viewModel.resetSendState() }
            )
            is AssetViewModel.SendState.Failure -> SendResultBanner(
                success = false,
                title = stringResource(R.string.as_send_failed),
                detail = s.message,
                onDismiss = { viewModel.resetSendState() }
            )
            else -> {}
        }
        if (sendState !is AssetViewModel.SendState.Idle) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // A completed send is TERMINAL. Previously the form stayed populated and re-armed
        // underneath the success banner, so the screen read as an invitation to send the
        // same transaction again — and the section below showed a permanent spinner,
        // because the asset it was "loading" had just been sent away and would never come
        // back. Show the way out instead, and stop rendering the form entirely.
        if (screenState == io.digibyte.core.asset.AssetSendScreenState.DONE) {
            Button(
                onClick = {
                    viewModel.resetSendState()
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteAccent),
            ) {
                Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            return@Column
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
                            text = stringResource(R.string.as_balance, formatAssetQuantity(ownedAsset.quantity, ownedAsset.metadata?.decimals ?: 0)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Recipient address ─────────────────────────────────────────
            Text(
                text = stringResource(R.string.send_recipient_label),
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
                    Text(stringResource(R.string.send_hint_dgb), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                contentDescription = stringResource(R.string.send_paste),
                                tint = DigiByteAccent
                            )
                        }
                        IconButton(onClick = onScanQr) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = stringResource(R.string.send_scan_qr),
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
                text = stringResource(R.string.as_quantity),
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
                            stringResource(R.string.send_max),
                            color = DigiByteAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                },
                suffix = {
                    Text(
                        text = ownedAsset.metadata?.symbol ?: tokensLabel,
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
            Text(
                text = stringResource(R.string.as_fee_note),
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
                    text = stringResource(R.string.send_fee_below_min),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFA000)
                )
                is AssetFeeWarning.ZeroFee -> Text(
                    text = stringResource(R.string.send_fee_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteRed
                )
                is AssetFeeWarning.None -> Text(
                    text = stringResource(R.string.send_confirms_15s),
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
                        addressError = errRecipient
                        valid = false
                    }
                    if (quantityInput.isBlank() || quantityInput.toDoubleOrNull() == null ||
                        quantityInput.toDouble() <= 0) {
                        quantityError = errQuantity
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
                Text(stringResource(R.string.send_review), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } ?: run {
            // Only reachable in LOADING now — the DONE case returns above, so this can no
            // longer spin forever over an asset that was successfully sent away.
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
                    text = stringResource(R.string.as_confirm_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(20.dp))

                AssetConfirmRow(
                    label = stringResource(R.string.as_asset),
                    value = asset.metadata?.name ?: asset.assetId.take(12) + "…"
                )
                AssetConfirmRow(
                    label = stringResource(R.string.as_quantity),
                    value = "$quantityInput ${asset.metadata?.symbol ?: stringResource(R.string.as_tokens)}",
                )
                // Full address — never truncated per security requirement
                AssetConfirmRow(label = stringResource(R.string.send_to), value = recipientAddress)
                AssetConfirmRow(
                    label = stringResource(R.string.send_network_fee_row),
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
                    text = stringResource(R.string.as_verify_warning),
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
                        Text(stringResource(R.string.common_cancel))
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
                            Text(stringResource(R.string.as_confirm), fontWeight = FontWeight.Bold)
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
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_dismiss), tint = accent)
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

/** Marker value shown in the cost preview. Single source of truth — the same
 *  constant the send path emits — so the preview can never drift from the
 *  actual on-chain marker (it was hardcoded at the old 700 and understated the
 *  DGB cost after the dust-driven bump to 6,000). */
private val DA_MARKER_SATS_UI: Long = io.digibyte.core.asset.send.DA_MARKER_SATS

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
 *   - 1 marker ([DA_MARKER_SATS_UI] sats) for the recipient
 *   - 1 marker ([DA_MARKER_SATS_UI] sats) for asset change, only if the user
 *     is sending less than their full balance for this asset
 *   - The estimated network fee (size-aware, at the chosen fee rate)
 *
 * Doesn't account for DGB-fee-input contribution (the marker sats already in
 * the asset UTXO partly fund the new markers); that's a wash from the user's
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
                text = stringResource(R.string.as_cost_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            CostRow(stringResource(R.string.as_recipient_marker), "${formatSats(DA_MARKER_SATS_UI)} sats")
            if (needsAssetChange) {
                CostRow(stringResource(R.string.as_change_marker), "${formatSats(DA_MARKER_SATS_UI)} sats")
            }
            CostRow(stringResource(R.string.as_network_fee_est), "≈ ${formatSats(feeSats)} sats")
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(6.dp))
            CostRow(
                label = stringResource(R.string.as_total_dgb),
                value = "≈ ${formatSats(totalSats)} sats (${formatDgb(totalSats)} DGB)",
                emphasize = true,
            )
            // Partial-transfer hint — shows the user the change UTXO they'll
            // hold after the send so the new partial path doesn't surprise.
            if (needsAssetChange && typedInternalQty != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val keptInternal = ownedAsset.quantity - typedInternalQty
                val symbol = ownedAsset.metadata?.symbol ?: stringResource(R.string.as_units)
                Text(
                    text = stringResource(R.string.as_stays, formatAssetQuantity(keptInternal, decimals), symbol),
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
