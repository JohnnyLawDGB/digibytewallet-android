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
import androidx.compose.ui.res.stringResource
import io.digibyte.R

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val peerCount by viewModel.peerCount.collectAsStateWithLifecycle()
    val hasPeers = peerCount > 0

    // DigiDollar is LIVE on both networks — the mainnet softfork activated
    // 2026-07-18 (testnet since testnet26). The receive chip is always active;
    // the address prefix follows the running network (TD… testnet / DD… mainnet).
    val ddActive = true
    val onTestnet = remember { isTestnet(context) }

    // Address format: 0=legacy(D), 2=bech32/SegWit(dgb1q), 4=Taproot/P2TR(dgb1p),
    // 3=DigiDollar(TD…) — default to bech32/SegWit.
    var addressFormat by remember { mutableIntStateOf(2) }

    // Pre-derive each format once so toggling the chip doesn't re-run the JNI
    // key-derivation path. getReceiveAddress format: 0 = legacy, 2 = bech32 P2WPKH,
    // 3 = Taproot P2TR; DigiDollar comes from getDigiDollarReceiveAddress().
    val unavailable = stringResource(R.string.receive_address_unavailable)
    // Hoisted: used inside click/share lambdas, which are not composable.
    val clipLabelDd = stringResource(R.string.receive_clip_dd)
    val clipLabelDgb = stringResource(R.string.receive_clip_dgb)
    val shareDd = stringResource(R.string.receive_share_dd)
    val shareDgb = stringResource(R.string.receive_share_dgb)
    val shareTitle = stringResource(R.string.receive_share_title)
    val legacyAddress = remember(unavailable) { viewModel.getReceiveAddress(0, 0) ?: unavailable }
    val bech32Address = remember(unavailable) { viewModel.getReceiveAddress(0, 2) ?: unavailable }
    val taprootAddress = remember(unavailable) { viewModel.getReceiveAddress(0, 3) ?: unavailable }
    val digiDollarAddress = remember {
        if (ddActive) viewModel.getDigiDollarReceiveAddress() ?: unavailable else null
    }
    val address = when (addressFormat) {
        0 -> legacyAddress
        4 -> taprootAddress
        3 -> digiDollarAddress ?: bech32Address
        else -> bech32Address
    }
    val isDigiDollar = addressFormat == 3 && digiDollarAddress != null

    // Optional amount for the QR URI
    var amountInput by remember { mutableStateOf("") }

    val qrContent by remember(address, amountInput, isDigiDollar) {
        derivedStateOf {
            // DigiDollar addresses are not DGB-URI targets — QR carries the raw TD… address.
            val sats = amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
            if (!isDigiDollar && sats != null && sats > 0) {
                DigiByteUri.encode(address = address, amountSats = sats)
            } else {
                address
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = if (isDigiDollar) stringResource(R.string.receive_title_dd) else stringResource(R.string.receive_title_dgb),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                    text = stringResource(R.string.receive_no_peers),
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
            text = if (isDigiDollar) stringResource(R.string.receive_address_dd) else stringResource(R.string.receive_address_dgb),
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
                    text = address,
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

        // Address format toggle — wraps to a second line if the chips don't fit.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = addressFormat == 2,
                onClick = { addressFormat = 2 },
                label = { Text(stringResource(R.string.receive_fmt_segwit), fontSize = 12.sp) }
            )
            FilterChip(
                selected = addressFormat == 0,
                onClick = { addressFormat = 0 },
                label = { Text(stringResource(R.string.receive_fmt_legacy), fontSize = 12.sp) }
            )
            FilterChip(
                selected = addressFormat == 4,
                onClick = { addressFormat = 4 },
                label = { Text(stringResource(R.string.receive_fmt_taproot), fontSize = 12.sp) }
            )
            FilterChip(
                selected = addressFormat == 3 && ddActive,
                enabled = ddActive,
                onClick = { if (ddActive) addressFormat = 3 },
                label = {
                    Text(
                        stringResource(if (onTestnet) R.string.receive_fmt_dd_testnet else R.string.receive_fmt_dd),
                        fontSize = 12.sp
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Copy + Share buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipLabel = if (isDigiDollar) clipLabelDd else clipLabelDgb
                    cb?.setPrimaryClip(ClipData.newPlainText(clipLabel, address))
                    showCopied = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (showCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.common_copy),
                    tint = if (showCopied) DigiByteGreen else DigiByteAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(if (showCopied) R.string.common_copied else R.string.common_copy),
                    color = if (showCopied) DigiByteGreen else DigiByteAccent
                )
            }

            Button(
                onClick = {
                    val shareText = buildString {
                        if (isDigiDollar) {
                            // DigiDollar addresses aren't DGB-URI targets — share the raw address only.
                            append(shareDd.format(address))
                        } else {
                            append(shareDgb.format(address))
                            val sats = amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
                            if (sats != null && sats > 0) {
                                append("\n${DigiByteUri.encode(address, sats)}")
                            }
                        }
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.common_share),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.common_share))
            }
        }

        // Amount-in-QR is a DGB payment-request feature (digibyte: URI). DigiDollar
        // addresses aren't DGB-URI targets — the QR carries the raw DD address only —
        // so this whole section is hidden when the DigiDollar format is selected, and
        // replaced with a short DD note instead of a misleading "DGB" amount field.
        if (!isDigiDollar) {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))

            // Optional amount input
            Text(
                text = stringResource(R.string.receive_request_amount),
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
                label = { Text(stringResource(R.string.receive_amount_label)) },
                shape = RoundedCornerShape(8.dp)
            )

            if (amountInput.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.receive_qr_encodes,
                        DigiByteUri.encode(
                            address,
                            amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() },
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.receive_dd_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
