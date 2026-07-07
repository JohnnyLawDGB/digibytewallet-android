package io.digibyte.ui.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.digibyte.core.isTestnet
import io.digibyte.core.model.DigiByteUri
import io.digibyte.ui.components.QrCodeDisplay
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen

@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val peerCount by viewModel.peerCount.collectAsStateWithLifecycle()
    val hasPeers = peerCount > 0

    // Address format: 0=legacy(D), 2=bech32(dgb1q) — default to bech32
    var addressFormat by remember { mutableIntStateOf(2) }

    // Pre-derive both formats once so toggling the chip doesn't re-run the
    // JNI key-derivation path. format 0 = legacy D-prefix, format 2 = bech32.
    val legacyAddress = remember { viewModel.getReceiveAddress(0, 0) ?: "Address unavailable" }
    val bech32Address = remember { viewModel.getReceiveAddress(0, 2) ?: "Address unavailable" }
    val address = if (addressFormat == 0) legacyAddress else bech32Address

    // DigiDollar is testnet-only until 4.0.0, so the TD/DD receive address is
    // only offered on testnet builds (and only if key derivation succeeds).
    val onTestnet = remember { isTestnet(context) }
    val ddAddress = remember { if (onTestnet) viewModel.getDigiDollarReceiveAddress() else null }
    var showDigiDollar by remember { mutableStateOf(false) }
    val isDd = showDigiDollar && ddAddress != null
    val displayAddress = if (isDd) ddAddress!! else address

    // Optional amount for the QR URI (DGB only — DigiDollar has no amount URI)
    var amountInput by remember { mutableStateOf("") }

    val qrContent by remember(displayAddress, amountInput, isDd) {
        derivedStateOf {
            val sats = amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
            if (!isDd && sats != null && sats > 0) {
                DigiByteUri.encode(address = displayAddress, amountSats = sats)
            } else {
                displayAddress
            }
        }
    }

    var showCopied by remember { mutableStateOf(false) }

    // Reset "Copied!" after 2 seconds
    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(2000L)
            showCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (isDd) "Receive DigiDollar" else "Receive DGB",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Asset toggle — only when a DigiDollar address is available (testnet).
        if (ddAddress != null) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !showDigiDollar,
                    onClick = { showDigiDollar = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("DigiByte") }
                SegmentedButton(
                    selected = showDigiDollar,
                    onClick = { showDigiDollar = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("DigiDollar") }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Informational banner when SPV has no connected peers. The address
        // is still safe to share — incoming transactions settle on chain
        // regardless — but the user should know that balance and
        // confirmation updates won't flow until peers reconnect.
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
                    text = "No peers connected. Incoming transactions won't appear here until the wallet reconnects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFCC66)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // QR code
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.wrapContentSize()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                QrCodeDisplay(
                    content = qrContent,
                    size = 220.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Address display (selectable + copyable)
        Text(
            text = if (isDd) "Your DigiDollar Address" else "Your DigiByte Address",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            SelectionContainer {
                Text(
                    text = displayAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    ),
                    color = DigiByteAccent,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Address format toggle — DGB only; DigiDollar has a single address form.
        if (!isDd) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = addressFormat == 2,
                    onClick = { addressFormat = 2 },
                    label = { Text("SegWit (dgb1q…)", fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 8.dp)
                )
                FilterChip(
                    selected = addressFormat == 0,
                    onClick = { addressFormat = 0 },
                    label = { Text("Legacy (D…)", fontSize = 12.sp) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Copy + Share buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val label = if (isDd) "DigiDollar address" else "DGB address"
                    cb?.setPrimaryClip(ClipData.newPlainText(label, displayAddress))
                    showCopied = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (showCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (showCopied) DigiByteGreen else DigiByteAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showCopied) "Copied!" else "Copy",
                    color = if (showCopied) DigiByteGreen else DigiByteAccent
                )
            }

            Button(
                onClick = {
                    val shareText = buildString {
                        if (isDd) {
                            append("My DigiDollar address: $displayAddress")
                        } else {
                            append("My DigiByte address: $displayAddress")
                            val sats = amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
                            if (sats != null && sats > 0) {
                                append("\n${DigiByteUri.encode(displayAddress, sats)}")
                            }
                        }
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Address"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share")
            }
        }

        // Optional amount request — DGB only (DigiDollar has no amount URI).
        if (!isDd) {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Request Specific Amount (Optional)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = amountInput,
                onValueChange = { amountInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0.00000000") },
                suffix = { Text("DGB", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                label = { Text("Amount") },
                shape = RoundedCornerShape(8.dp)
            )

            if (amountInput.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "QR encodes: ${DigiByteUri.encode(address, amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() })}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
