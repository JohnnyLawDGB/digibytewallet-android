package io.digibyte.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.model.SyncState
import io.digibyte.ui.components.BalanceDisplay
import io.digibyte.ui.components.TransactionItem
import io.digibyte.ui.sync.SyncOverlay
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@Composable
fun WalletScreen(
    onNavigateSend: () -> Unit,
    onNavigateReceive: () -> Unit,
    onNavigateScan: () -> Unit,
    onNavigateTx: (String) -> Unit,
    onNavigateAssets: () -> Unit = {},
    viewModel: WalletViewModel = hiltViewModel()
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val fiatBalance by viewModel.fiatBalance.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // ── Hero balance card ─────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(DigiByteNavy, DigiByteBlue.copy(alpha = 0.85f))
                        )
                    )
                    .padding(top = 32.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BalanceDisplay(
                        fiatAmount = fiatBalance,
                        dgbAmount = WalletViewModel.formatSatoshis(balance)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sync state indicator
                    SyncIndicator(syncState)
                }
            }
        }

        // ── Action buttons ────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WalletActionButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = "Send",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateSend
                )
                WalletActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Receive",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateReceive
                )
                WalletActionButton(
                    icon = Icons.Default.CropFree,
                    label = "Scan",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateScan
                )
                WalletActionButton(
                    icon = Icons.Default.Stars,
                    label = "Assets",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateAssets
                )
            }
        }

        // ── DigiRunner sync game / progress bar ──────────────────────────
        item {
            SyncOverlay(
                syncState = syncState,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }

        // ── Price feed card ───────────────────────────────────────────────
        item {
            price?.let { p ->
                PriceFeedCard(
                    priceUsd = p.priceUsd,
                    change24h = p.change24h,
                    source = p.source,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // ── Recent activity header ────────────────────────────────────────
        item {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── Transaction list ──────────────────────────────────────────────
        if (transactions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transactions yet.\nSend or receive DGB to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(
                items = transactions.take(20),
                key = { it.txid }
            ) { tx ->
                TransactionItem(
                    tx = tx,
                    onClick = { onNavigateTx(tx.txid) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun WalletActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = DigiByteAccent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PriceFeedCard(
    priceUsd: Double,
    change24h: Double,
    source: String,
    modifier: Modifier = Modifier
) {
    val priceFormatted = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 6
        maximumFractionDigits = 6
    }.format(priceUsd)

    val changeFormatted = String.format("%.2f", abs(change24h))
    val isPositive = change24h >= 0.0
    val changeColor = if (isPositive) Color(0xFF4CAF50) else Color(0xFFE53935)
    val changePrefix = if (isPositive) "▲" else "▼"

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DGB / USD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$$priceFormatted",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$changePrefix $changeFormatted%",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = changeColor
                )
                Text(
                    text = "via $source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncIndicator(syncState: SyncState) {
    when (syncState) {
        is SyncState.Syncing -> {
            val pct = (syncState.progress * 100).toInt()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = DigiByteAccent
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Syncing $pct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent.copy(alpha = 0.85f)
                )
            }
        }
        is SyncState.Complete -> {
            Text(
                text = "Synced",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50).copy(alpha = 0.85f)
            )
        }
        is SyncState.Failed -> {
            Text(
                text = "Sync failed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
        }
        else -> { /* Idle — show nothing */ }
    }
}
