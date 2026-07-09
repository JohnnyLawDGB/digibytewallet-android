package io.digibyte.ui.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.ui.components.CONFIRMED_THRESHOLD
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@Composable
fun TransactionDetailScreen(
    txid: String,
    onNavigateBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val allTxs by viewModel.transactions.collectAsStateWithLifecycle()
    val tx = allTxs.firstOrNull { it.txid == txid }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Transaction Details",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }

        if (tx == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Transaction not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        TransactionDetailContent(tx = tx, onNavigateBack = onNavigateBack)
    }
}

@Composable
private fun TransactionDetailContent(
    tx: TransactionEntity,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    val isSend = tx.amount < 0
    val amountAbs = abs(tx.amount)
    val dgb = amountAbs / 100_000_000.0
    val feeDgb = tx.fee / 100_000_000.0

    val amountColor: Color = if (isSend) DigiByteRed else DigiByteGreen
    val amountPrefix = if (isSend) "- " else "+ "

    val amountFormatted = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 8
        maximumFractionDigits = 8
    }.format(dgb)

    val feeFormatted = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 8
        maximumFractionDigits = 8
    }.format(feeDgb)

    val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
        .format(Date(tx.timestamp * 1000L))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Status + amount hero
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isSend) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = if (isSend) "Sent" else "Received",
                    tint = amountColor,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$amountPrefix$amountFormatted DGB",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = amountColor,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                ConfirmationsBadge(tx.confirmations)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Details card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // TXID — full, copyable
                DetailRow(
                    label = "Transaction ID",
                    value = tx.txid,
                    copyable = true,
                    context = context
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = "Block Height", value = if (tx.blockHeight > 0) tx.blockHeight.toString() else "Pending")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = "Confirmations", value = when {
                    tx.confirmations == 0 -> "Unconfirmed"
                    tx.confirmations >= CONFIRMED_THRESHOLD -> "${tx.confirmations} (Final)"
                    else -> tx.confirmations.toString()
                })
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = "Timestamp", value = dateStr)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = "Network Fee", value = "$feeFormatted DGB")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (tx.toAddress.isNotBlank()) {
                    DetailRow(
                        label = "To Address",
                        value = tx.toAddress,
                        copyable = true,
                        context = context
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (tx.fromAddress.isNotBlank()) {
                    DetailRow(
                        label = "From Address",
                        value = tx.fromAddress,
                        copyable = true,
                        context = context
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // View on block explorer
        Button(
            onClick = {
                io.digibyte.ui.util.openExternalUrl(
                    context, "https://chainz.cryptoid.info/dgb/tx.dws?${tx.txid}",
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DigiByteAccent)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("View on Explorer", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Wallet")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    copyable: Boolean = false,
    context: Context? = null
) {
    var showCopied by remember { mutableStateOf(false) }

    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(1500L)
            showCopied = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (copyable && context != null) {
                IconButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText(label, value))
                        showCopied = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (showCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (showCopied) DigiByteGreen else DigiByteAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationsBadge(confirmations: Int) {
    val (label, color) = when {
        confirmations == 0 -> "Unconfirmed" to MaterialTheme.colorScheme.error
        confirmations < CONFIRMED_THRESHOLD -> "$confirmations confirmation${if (confirmations > 1) "s" else ""}" to Color(0xFFFFA726)
        else               -> "Confirmed ($confirmations)" to DigiByteGreen
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color
        )
    }
}
