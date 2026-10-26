package app.aroundtheblock.wallet.ui.wallet

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
import app.aroundtheblock.wallet.core.asset.AssetTxAmount
import app.aroundtheblock.wallet.core.db.entity.TransactionEntity
import app.aroundtheblock.wallet.ui.asset.assetAmountText
import app.aroundtheblock.wallet.ui.components.CONFIRMED_THRESHOLD
import app.aroundtheblock.wallet.ui.components.TxKind
import app.aroundtheblock.wallet.ui.components.TypedAmount
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteGreen
import app.aroundtheblock.wallet.ui.theme.DigiByteRed
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import androidx.compose.ui.res.stringResource
import app.aroundtheblock.wallet.R

/**
 * The asset amount the details of [kind] lead with, or null when they lead with DGB. Only an asset
 * transaction whose amount the activity list resolved has one; the list and the details read the
 * same entry, so the two never disagree.
 */
internal fun assetAmountForDetail(kind: TxKind?, typed: TypedAmount?): AssetTxAmount? =
    if (kind == TxKind.DIGIASSET) (typed as? TypedAmount.Asset)?.amount else null

@Composable
fun TransactionDetailScreen(
    txid: String,
    onNavigateBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val allTxs by viewModel.transactions.collectAsStateWithLifecycle()
    val tx = allTxs.firstOrNull { it.txid == txid }
    // The activity list's own classification and amounts: an asset transaction leads with the
    // asset it moved, not with the DGB marker value.
    val txKinds by viewModel.txKinds.collectAsStateWithLifecycle()
    val txTypedAmounts by viewModel.txTypedAmounts.collectAsStateWithLifecycle()

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = stringResource(R.string.txd_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }

        if (tx == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.txd_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        TransactionDetailContent(
            tx = tx,
            assetAmount = assetAmountForDetail(txKinds[tx.txid], txTypedAmounts[tx.txid]),
            onNavigateBack = onNavigateBack,
        )
    }
}

@Composable
private fun TransactionDetailContent(
    tx: TransactionEntity,
    /** The asset and quantity an asset transaction moved; null for any other transaction. */
    assetAmount: AssetTxAmount?,
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

    val dateStr = app.aroundtheblock.wallet.core.DateDisplay.dateTime(tx.timestamp * 1000L)

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
                    contentDescription = stringResource(if (isSend) R.string.txd_sent else R.string.txd_received),
                    tint = amountColor,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (assetAmount != null) {
                    // An asset transaction: the asset and quantity first, the asset's name when
                    // the headline shows its symbol, and the DGB the transaction moved under them.
                    Text(
                        text = amountPrefix + assetAmountText(assetAmount),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = amountColor,
                        textAlign = TextAlign.Center
                    )
                    val name = assetAmount.name
                    if (name != null && name != assetAmount.label) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = "$amountPrefix$amountFormatted DGB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "$amountPrefix$amountFormatted DGB",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = amountColor,
                        textAlign = TextAlign.Center
                    )
                }
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
                    label = stringResource(R.string.txd_transaction_id),
                    value = tx.txid,
                    copyable = true,
                    context = context
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(
                    label = stringResource(R.string.txd_block_height),
                    value = if (tx.blockHeight > 0) tx.blockHeight.toString()
                            else stringResource(R.string.txd_pending),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = stringResource(R.string.txd_confirmations), value = when {
                    tx.confirmations == 0 -> stringResource(R.string.txd_unconfirmed)
                    tx.confirmations >= CONFIRMED_THRESHOLD ->
                        stringResource(R.string.txd_confirmations_final, tx.confirmations)
                    else -> tx.confirmations.toString()
                })
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = stringResource(R.string.txd_timestamp), value = dateStr)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow(label = stringResource(R.string.txd_network_fee), value = "$feeFormatted DGB")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (tx.toAddress.isNotBlank()) {
                    DetailRow(
                        label = stringResource(R.string.txd_to_address),
                        value = tx.toAddress,
                        copyable = true,
                        context = context
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (tx.fromAddress.isNotBlank()) {
                    DetailRow(
                        label = stringResource(R.string.txd_from_address),
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
                app.aroundtheblock.wallet.ui.util.openExternalUrl(
                    context, "https://chainz.cryptoid.info/dgb/tx.dws?${tx.txid}",
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DigiByteAccent)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.txd_view_explorer), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.txd_back_to_wallet))
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
                        contentDescription = stringResource(R.string.common_copy),
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
        confirmations == 0 ->
            stringResource(R.string.txd_unconfirmed) to MaterialTheme.colorScheme.error
        confirmations < CONFIRMED_THRESHOLD ->
            stringResource(R.string.txd_confirmations_count, confirmations) to Color(0xFFFFA726)
        else ->
            stringResource(R.string.txd_confirmed_n, confirmations) to DigiByteGreen
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
