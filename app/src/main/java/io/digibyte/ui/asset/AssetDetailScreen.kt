package io.digibyte.ui.asset

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.model.OwnedAsset
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.util.openExternalUrl
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteNavy
import io.digibyte.ui.theme.DigiByteRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.res.stringResource
import io.digibyte.R

// MARKETPLACE_URL ("https://diginexum.trade/") was REMOVED. It was a stale hardcoded host that
// served a certificate issued for CN=digiassets.info, so tapping Marketplace handed the user a
// full-page browser security warning — on an asset screen, in a wallet. It also carried no asset
// id and merely opened a homepage. The button now opens the asset's own page inside the app's
// Market section; see DigistampUris.explorerUrlFor.

@Composable
fun AssetDetailScreen(
    assetId: String,
    onNavigateBack: () -> Unit,
    onNavigateSend: (String) -> Unit,
    onNavigateMarketplace: (String) -> Unit = {},
    viewModel: AssetViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // Ensure the correct asset is selected
    LaunchedEffect(assetId) {
        viewModel.selectAsset(assetId)
    }

    val asset by viewModel.selectedAsset.collectAsStateWithLifecycle()
    val history by viewModel.assetHistory.collectAsStateWithLifecycle()
    val owned by viewModel.ownedAssets.collectAsStateWithLifecycle()

    var showMediaViewer by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(DigiByteNavy, DigiByteBlue.copy(alpha = 0.85f))
                        )
                    )
                    .padding(top = 8.dp, bottom = 20.dp, start = 4.dp, end = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        text = stringResource(R.string.ad_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Asset not found guard ────────────────────────────────────────
        if (asset == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = DigiByteAccent)
                }
            }
            return@LazyColumn
        }

        val ownedAsset = asset!!
        val meta = ownedAsset.metadata
        val displayName = meta?.name ?: ownedAsset.assetId.take(12) + "…"
        val colorIndex = (ownedAsset.assetId.hashCode() and 0x7FFFFFFF) % 8

        // ── Hero card ─────────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    // Large icon. Tapping it opens the artwork full screen — but only when
                    // there IS artwork: the fallback is a generated letter tile, and making
                    // that tappable would promise a view that has nothing to show.
                    val iconColor = assetIconColors[colorIndex]
                    val hasArtwork = remember(meta?.imageUrl) {
                        AssetImageResolver.resolve(meta?.imageUrl) != null
                    }
                    AssetIcon(
                        imageUrl = meta?.imageUrl,
                        firstLetter = displayName.firstOrNull()?.uppercaseChar() ?: 'A',
                        iconColor = iconColor,
                        size = 72.dp,
                        modifier = if (hasArtwork) {
                            Modifier.clickable { showMediaViewer = true }
                        } else {
                            Modifier
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    if (!meta?.symbol.isNullOrBlank()) {
                        Text(
                            text = meta!!.symbol!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DigiByteAccent
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quantity owned
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DigiByteAccent.copy(alpha = 0.12f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.ad_balance),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatAssetQuantity(
                                    ownedAsset.quantity,
                                    meta?.decimals ?: 0
                                ),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp
                                ),
                                color = DigiByteAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Asset ID — truncated but copyable
                    AssetIdRow(assetId = ownedAsset.assetId, context = context)
                }
            }
        }

        // ── Info section ──────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.ad_info),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!meta?.description.isNullOrBlank()) {
                        AssetInfoRow(label = stringResource(R.string.ad_description), value = meta!!.description!!)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    AssetInfoRow(
                        label = stringResource(R.string.ad_total_supply),
                        value = if ((meta?.totalSupply ?: 0) > 0)
                            formatAssetQuantity(meta!!.totalSupply, meta.decimals)
                        else stringResource(R.string.ad_unknown)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Same metadata document, a different assetId — the signature of a copied
                    // asset, because the CID is the one thing a new issuance CAN reuse verbatim.
                    // Worded as a fact, never a verdict: the wallet cannot tell which of two
                    // assets is genuine (the earliest issuance it happens to HOLD need not be the
                    // earliest that exists), so it states what it observed and puts the verified
                    // issuer right beneath, which is what actually settles it.
                    val duplicates = remember(assetId, owned) {
                        io.digibyte.core.asset.AssetDuplicateDetector.assetsSharingMetadata(
                            assetId = assetId,
                            held = owned.mapNotNull { it.metadata },
                        )
                    }
                    if (duplicates.isNotEmpty()) {
                        AssetInfoRow(
                            label = stringResource(R.string.ad_identical_metadata),
                            value = if (duplicates.size == 1)
                                stringResource(R.string.ad_dup_one)
                            else
                                stringResource(R.string.ad_dup_many, duplicates.size),
                            context = context
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Shown ONLY when the chain proves it — the owner of input[0] of the
                    // issuance transaction, which is the outpoint the assetId is derived from.
                    // This row used to display whatever the minter wrote into the metadata, which
                    // is the one field a forger most wants a wallet to repeat back. When there is
                    // no proof the row is absent, deliberately: absent beats wrong.
                    if (!meta?.issuerAddress.isNullOrBlank()) {
                        AssetInfoRow(
                            label = stringResource(R.string.ad_issuer_verified),
                            value = meta!!.issuerAddress!!,
                            truncate = true,
                            copyable = true,
                            context = context
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    AssetInfoRow(
                        label = stringResource(R.string.ad_divisibility),
                        value = when (val d = meta?.decimals ?: 0) {
                            0 -> stringResource(R.string.ad_indivisible)
                            else -> stringResource(R.string.ad_decimals, d)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    AssetInfoRow(label = stringResource(R.string.ad_utxos_held), value = "${ownedAsset.utxoCount}")
                }
            }
        }

        // ── Action buttons ────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Marketplace button
                OutlinedButton(
                    onClick = { onNavigateMarketplace(ownedAsset.assetId) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DigiByteAccent
                    )
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.ad_marketplace), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                // Send button
                Button(
                    onClick = { onNavigateSend(ownedAsset.assetId) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DigiByteBlue
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.wallet_send), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // ── Transfer history ──────────────────────────────────────────────
        item {
            Text(
                text = stringResource(R.string.ad_transfer_history),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (history.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.ad_no_transfers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(history, key = { it.txid }) { tx ->
                AssetTransferItem(
                    tx = tx,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)
                )
            }
        }
    }

    // Rendered OUTSIDE the LazyColumn: a Dialog is its own window, and placing it in a
    // lazily-composed item would tie the open viewer to that item staying composed — it
    // would vanish if the user scrolled the header off screen.
    if (showMediaViewer) {
        AssetMediaViewer(
            imageUrl = asset?.metadata?.imageUrl,
            onDismiss = { showMediaViewer = false },
        )
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun AssetIdRow(assetId: String, context: Context) {
    var copied by remember { mutableStateOf(false) }
    val assetIdClipLabel = stringResource(R.string.ad_asset_id)

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500L)
            copied = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text = "ID: ${assetId.take(12)}…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(
            onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText(assetIdClipLabel, assetId))
                copied = true
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.ad_copy_asset_id),
                tint = if (copied) DigiByteGreen else DigiByteAccent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun AssetInfoRow(
    label: String,
    value: String,
    truncate: Boolean = false,
    copyable: Boolean = false,
    context: Context? = null
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500L)
            copied = false
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
                    text = if (truncate && value.length > 24) "${value.take(12)}…${value.takeLast(8)}" else value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (copyable && context != null) {
                IconButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText(label, value))
                        copied = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.common_copy),
                        tint = if (copied) DigiByteGreen else DigiByteAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetTransferItem(
    tx: TransactionEntity,
    modifier: Modifier = Modifier
) {
    val isSend = tx.amount < 0
    val amountColor = if (isSend) DigiByteRed else DigiByteGreen
    val amountPrefix = stringResource(if (isSend) R.string.txd_sent else R.string.txd_received)

    val dateStr = remember(tx.timestamp) {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            .format(Date(tx.timestamp * 1000L))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSend) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = amountPrefix,
                tint = amountColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = amountPrefix,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = amountColor
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${tx.txid.take(8)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val assetIconColors = listOf(
    DigiByteAccent,
    androidx.compose.ui.graphics.Color(0xFF4CAF50),
    androidx.compose.ui.graphics.Color(0xFFFFA726),
    androidx.compose.ui.graphics.Color(0xFFAB47BC),
    androidx.compose.ui.graphics.Color(0xFFEF5350),
    androidx.compose.ui.graphics.Color(0xFF26C6DA),
    androidx.compose.ui.graphics.Color(0xFFFFCA28),
    androidx.compose.ui.graphics.Color(0xFF66BB6A),
)
