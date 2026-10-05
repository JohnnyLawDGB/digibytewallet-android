package app.aroundtheblock.wallet.ui.components

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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aroundtheblock.wallet.core.asset.AssetTxAmount
import app.aroundtheblock.wallet.core.db.entity.TransactionEntity
import app.aroundtheblock.wallet.ui.asset.assetAmountText
import app.aroundtheblock.wallet.ui.theme.DigiByteGreen
import app.aroundtheblock.wallet.ui.theme.DigiByteRed
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import app.aroundtheblock.wallet.R

/**
 * Confirmations at which a transaction is shown as settled (stringResource(R.string.tx_confirmed)). DigiByte
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
 * [app.aroundtheblock.wallet.core.bridge.NativeBridge.digiDollarTxType]) marks it DigiDollar;
 * everything else is plain DGB.
 */
fun classifyTxKind(isAssetTx: Boolean, digiDollarType: Int): TxKind = when {
    isAssetTx -> TxKind.DIGIASSET
    digiDollarType > 0 -> TxKind.DIGIDOLLAR
    else -> TxKind.DGB
}

/**
 * The amount a non-DGB row shows in place of its on-chain DGB value: a DigiDollar amount as
 * formatted, or an asset amount, worded where it is shown ([assetAmountText]).
 */
sealed interface TypedAmount {
    data class Formatted(val text: String) : TypedAmount
    data class Asset(val amount: AssetTxAmount) : TypedAmount
}

/** Share of the row's free width the amount column may take when the row carries a kind chip. */
private const val CHIP_ROW_AMOUNT_SHARE = 0.45f

/** Share of the row's free width the amount column may take on a plain DGB row. */
private const val PLAIN_ROW_AMOUNT_SHARE = 0.65f

/**
 * Widest the amount column may be, in pixels, when [availablePx] is the width the row has left
 * after its fixed parts (icon and spacers). The rest stays with the left column: the direction
 * label, the kind chip when [hasChip], the address and the date. A long asset name is cut with an
 * ellipsis instead of squeezing the label and the chip until they break one letter per line.
 */
internal fun amountColumnMaxWidth(availablePx: Int, hasChip: Boolean): Int {
    if (availablePx <= 0) return 0
    val share = if (hasChip) CHIP_ROW_AMOUNT_SHARE else PLAIN_ROW_AMOUNT_SHARE
    return (availablePx * share).toInt()
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
    // Type amount for a non-DGB row: DigiDollar "$X.XX" / DigiAsset "N CHANG".
    // Null → show the plain DGB amount (also the fallback for DGB rows).
    typedAmount: TypedAmount? = null,
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

    // DigiDollar / DigiAsset rows show their type amount ("$1.00" / "20 CHANG")
    // instead of the near-zero on-chain DGB value; everything else shows DGB.
    val typedText: String? = when (typedAmount) {
        is TypedAmount.Formatted -> typedAmount.text
        is TypedAmount.Asset -> assetAmountText(typedAmount.amount)
        null -> null
    }
    val amountText = if (kind != TxKind.DGB && typedText != null) {
        amountPrefix + typedText
    } else {
        "$amountPrefix$amountFormatted DGB"
    }

    // DateDisplay, not a pattern string: a pattern pins American day/month order even when the
    // locale is German, which is how this rendered "Aug. 24, 2026" in a German build.
    val dateStr = app.aroundtheblock.wallet.core.DateDisplay.date(tx.timestamp * 1000L)

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
                        contentDescription = stringResource(if (isSend) R.string.txd_sent else R.string.txd_received),
                        tint = if (isSend) DigiByteRed else DigiByteGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Description + address + date
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The label is measured first and keeps its word whole; the chip takes
                    // what is left of the column and is cut with an ellipsis, never wrapped.
                    Text(
                        text = stringResource(if (isSend) R.string.txd_sent else R.string.txd_received),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (kind != TxKind.DGB) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TypeChip(kind, modifier = Modifier.weight(1f, fill = false))
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

            // Amount + confirmations badge. Capped (amountColumnMaxWidth) so a long asset name
            // is cut with an ellipsis rather than taking the left column's width.
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.layout { measurable, constraints ->
                    val capped = if (!constraints.hasBoundedWidth) constraints else {
                        val cap = amountColumnMaxWidth(constraints.maxWidth, hasChip = kind != TxKind.DGB)
                        constraints.copy(minWidth = minOf(constraints.minWidth, cap), maxWidth = cap)
                    }
                    val placeable = measurable.measure(capped)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
            ) {
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = amountColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
private fun TypeChip(kind: TxKind, modifier: Modifier = Modifier) {
    val (label, color) = when (kind) {
        TxKind.DIGIDOLLAR -> "DigiDollar" to Color(0xFF00A389) // DigiDollar teal-green
        TxKind.DIGIASSET  -> "DigiAsset" to Color(0xFF7E57C2)  // DigiAsset purple
        TxKind.DGB        -> return                            // no chip for plain DGB
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.18f),
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConfirmationsBadge(confirmations: Int) {
    val (label, color) = when {
        confirmations == 0 -> stringResource(R.string.txd_unconfirmed) to MaterialTheme.colorScheme.error
        confirmations < CONFIRMED_THRESHOLD -> stringResource(R.string.tx_conf_short, confirmations) to Color(0xFFFFA726) // amber
        else               -> stringResource(R.string.tx_confirmed) to DigiByteGreen
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
