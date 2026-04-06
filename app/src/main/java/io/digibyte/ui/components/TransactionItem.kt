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
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Single row in the transaction list. Tapping triggers [onClick].
 */
@Composable
fun TransactionItem(
    tx: TransactionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Self-send: nearly all DGB comes back (only the fee leaves).
    // Regular send with change also has received > 0, so check if the
    // net loss (sent - received) is just the fee (< 1 DGB threshold).
    val netLoss = tx.sent - tx.received
    val isSelfSend = tx.sent > 0 && tx.received > 0 && netLoss > 0 && netLoss < 100_000_000L
    val isSend = !isSelfSend && tx.amount < 0

    // For self-sends, amount = received - sent = -(fee). Show the fee
    // since that's the only DGB that actually left the wallet.
    val amountAbs = if (isSelfSend) tx.fee else kotlin.math.abs(tx.amount)
    val dgb = amountAbs / 100_000_000.0
    val amountFormatted = NumberFormat.getInstance().apply {
        maximumFractionDigits = 8
        minimumFractionDigits = 2
    }.format(dgb)

    val amountColor: Color = when {
        isSelfSend -> DigiByteAccent
        isSend     -> DigiByteRed
        else       -> DigiByteGreen
    }
    val amountPrefix = when {
        isSelfSend -> "Fee: "
        isSend     -> "- "
        else       -> "+ "
    }

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
                color = when {
                    isSelfSend -> DigiByteAccent.copy(alpha = 0.15f)
                    isSend     -> DigiByteRed.copy(alpha = 0.15f)
                    else       -> DigiByteGreen.copy(alpha = 0.15f)
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = when {
                            isSelfSend -> Icons.Default.ArrowDownward
                            isSend     -> Icons.Default.ArrowUpward
                            else       -> Icons.Default.ArrowDownward
                        },
                        contentDescription = when {
                            isSelfSend -> "Self-transfer"
                            isSend     -> "Sent"
                            else       -> "Received"
                        },
                        tint = when {
                            isSelfSend -> DigiByteAccent
                            isSend     -> DigiByteRed
                            else       -> DigiByteGreen
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Description + address + date
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isSelfSend -> "Sent to self"
                        isSend     -> "Sent"
                        else       -> "Received"
                    },
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
        confirmations < 6  -> "$confirmations conf." to Color(0xFFFFA726) // amber
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
