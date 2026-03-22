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
import io.digibyte.core.model.DigiByteUri
import io.digibyte.ui.components.QrCodeDisplay
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen

@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // Generate fresh address — index 0 by default (production: derive from wallet gap limit)
    val address = remember { viewModel.getReceiveAddress(0) ?: "Address unavailable" }

    // Optional amount for the QR URI
    var amountInput by remember { mutableStateOf("") }

    val qrContent by remember(address, amountInput) {
        derivedStateOf {
            val sats = amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
            if (sats != null && sats > 0) {
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
                text = "Receive DGB",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

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
            text = "Your DigiByte Address",
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

        Spacer(modifier = Modifier.height(12.dp))

        // Copy + Share buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("DGB address", address))
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
                        append("My DigiByte address: $address")
                        val sats = amountInput.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
                        if (sats != null && sats > 0) {
                            append("\n${DigiByteUri.encode(address, sats)}")
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

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(20.dp))

        // Optional amount input
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

        Spacer(modifier = Modifier.height(24.dp))
    }
}
