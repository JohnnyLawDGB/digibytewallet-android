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
 * How a transaction row is classified on the activity list. DGB is the plain
 * native transfer; DIGIDOLLAR and DIGIASSET carry a labeled chip so the three
 * asset kinds are visually distinct.
 */
enum class TxKind { DGB, DIGIDOLLAR, DIGIASSET }

/**
 * Pure classifier for a wallet transaction. Asset takes precedence — an asset
 * carrier tx is flagged on the entity ([TransactionEntity.isAssetTx]); otherwise
 * a nonzero native DigiDollar type (1=MINT/2=TRANSFER/3=REDEEM from
 * [io.digibyte.core.bridge.NativeBridge.digiDollarTxType]) marks it DigiDollar;
 * everything else is plain DGB.
 */
fun classifyTxKind(isAssetTx: Boolean, digiDollarType: Int): TxKind = when {
    isAssetTx -> TxKind.DIGIASSET
    digiDollarType > 0 -> TxKind.DIGIDOLLAR
    else -> TxKind.DGB
}

/**
 * Single row in the transaction list. Tapping triggers [onClick]. [kind] labels
 * the row DigiDollar / DigiAsset (DGB shows no chip).
 */
@Composable
fun TransactionItem(
    tx: TransactionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TxKind = TxKind.DGB,
    // Pre-formatted type amount for a non-DGB row: DigiDollar "$X.XX" / DigiAsset
    // "N Tokens". Null → show the plain DGB amount (also the fallback for DGB rows).
    typedAmount: String? = null,
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

    // DigiDollar / DigiAsset rows show their type amount ("$1.00" / "20 Tokens")
    // instead of the near-zero on-chain DGB value; everything else shows DGB.
    val amountText = if (kind != TxKind.DGB && typedAmount != null) {
        "$amountPrefix$typedAmount"
    } else {
        "$amountPrefix$amountFormatted DGB"
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isSend) "Sent" else "Received",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (kind != TxKind.DGB) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TypeChip(kind)
                    }
                }
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
                    text = amountText,
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

/**
 * Small colored chip labeling a non-DGB transaction's kind (DigiDollar or a
 * DigiAsset). Mirrors [ConfirmationsBadge]'s shape/alpha treatment so the row
 * reads consistently.
 */
@Composable
private fun TypeChip(kind: TxKind) {
    val (label, color) = when (kind) {
        TxKind.DIGIDOLLAR -> "DigiDollar" to Color(0xFF00A389) // DigiDollar teal-green
        TxKind.DIGIASSET  -> "DigiAsset" to Color(0xFF7E57C2)  // DigiAsset purple
        TxKind.DGB        -> return                            // no chip for plain DGB
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.18f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color
        )
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
