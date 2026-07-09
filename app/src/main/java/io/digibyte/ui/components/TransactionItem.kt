package io.digibyte.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Confirmations at which a transaction is shown as settled ("Confirmed"). DigiByte
 * mines ~every 15s, so 4 confirmations ≈ 1 minute. Historical txs re-synced during
 * catch-up are already deep (high confirmations), so this latency only applies to
 * genuinely-new incoming transactions — legitimate settlement time.
 */
const val CONFIRMED_THRESHOLD = 4

/**
 * Single row in the transaction list. Tapping triggers [onClick].
 */
@Composable
fun TransactionItem(
    tx: TransactionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSend = tx.amount < 0
    val amountAbs = kotlin.math.abs(tx.amount)
    val dgb = amountAbs / 100_000_000.0
    val amountFormatted = NumberFormat.getInstance().apply {
        maximumFractionDigits = 8
        minimumFractionDigits = 2
    }.format(dgb)

    val amountColor: Color = if (isSend) DigiByteRed else DigiByteGreen
    val amountPrefix = if (isSend) "- " else "+ "

    val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        .format(Date(tx.timestamp * 1000L))

    // Show first 8 + ellipsis + last 8 chars of the counterpart address
    val address = if (isSend) tx.toAddress else tx.fromAddress
    val addressShort = if (address.length > 20) {
        "${address.take(8)}…${address.takeLast(8)}"
    } else address

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon
            Surface(
                shape = CircleShape,
                color = if (isSend) DigiByteRed.copy(alpha = 0.15f)
                        else DigiByteGreen.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (isSend) Icons.Default.ArrowUpward
                                      else Icons.Default.ArrowDownward,
                        contentDescription = if (isSend) "Sent" else "Received",
                        tint = if (isSend) DigiByteRed else DigiByteGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Description + address + date
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSend) "Sent" else "Received",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = addressShort,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Amount + confirmations badge
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$amountPrefix$amountFormatted DGB",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = amountColor,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                ConfirmationsBadge(tx.confirmations)
            }
        }
    }
}

@Composable
private fun ConfirmationsBadge(confirmations: Int) {
    val (label, color) = when {
        confirmations == 0 -> "Unconfirmed" to MaterialTheme.colorScheme.error
        confirmations < CONFIRMED_THRESHOLD -> "$confirmations conf." to Color(0xFFFFA726) // amber
        else               -> "Confirmed" to DigiByteGreen
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.18f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
