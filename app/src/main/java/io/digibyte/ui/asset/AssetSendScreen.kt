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

    var recipientAddress by remember { mutableStateOf("") }
    var quantityInput by remember { mutableStateOf("") }
    var selectedFeeTier by remember { mutableStateOf(1) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var quantityError by remember { mutableStateOf<String?>(null) }

    // ── Confirmation dialog ───────────────────────────────────────────────
    if (showConfirmDialog && asset != null) {
        AssetSendConfirmDialog(
            asset = asset!!,
            recipientAddress = recipientAddress,
            quantityInput = quantityInput,
            feeTierLabel = feeTierLabel(selectedFeeTier),
            onConfirm = {
                // sendAsset() is a stub — show informational snackbar instead of broadcasting
                showConfirmDialog = false
            },
            onCancel = { showConfirmDialog = false }
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

        // ── "Coming soon" banner ─────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = DigiByteAccent.copy(alpha = 0.10f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = DigiByteAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Asset sending coming soon. The UI flow is complete — transaction " +
                           "broadcasting will be enabled in the next release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DigiByteAccent,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

            // ── DGB fee selector ──────────────────────────────────────────
            Text(
                text = "DGB Carrier Fee",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Asset transactions require DGB for network fees",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 1, 2).forEach { tier ->
                    AssetFeeTierChip(
                        label = feeTierLabel(tier),
                        satPerKb = feeTierSatPerKb(tier),
                        selected = selectedFeeTier == tier,
                        onClick = { selectedFeeTier = tier },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

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
    feeTierLabel: String,
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
                AssetConfirmRow(label = "DGB Fee Tier", value = feeTierLabel)

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Coming soon note
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DigiByteAccent.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Asset sending is coming soon. Your transaction details have been " +
                               "validated but will not be broadcast yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DigiByteAccent,
                        modifier = Modifier.padding(10.dp),
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp
                    )
                }

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
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
                    ) {
                        Text("Confirm", fontWeight = FontWeight.Bold)
                    }
                }
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

// ── Fee tier chip ────────────────────────────────────────────────────────────

@Composable
private fun AssetFeeTierChip(
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

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun feeTierLabel(tier: Int) = when (tier) {
    0 -> "Slow"
    1 -> "Normal"
    2 -> "Fast"
    else -> "Normal"
}

private fun feeTierSatPerKb(tier: Int) = when (tier) {
    0 -> 1_000L
    1 -> 5_000L
    2 -> 20_000L
    else -> 5_000L
}
