package io.digibyte.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.model.OwnedAsset
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy

@Composable
fun AssetListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAsset: (String) -> Unit,
    viewModel: AssetViewModel = hiltViewModel()
) {
    val assets by viewModel.ownedAssets.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DigiByteNavy, DigiByteBlue.copy(alpha = 0.85f))
                    )
                )
                .padding(top = 8.dp, bottom = 16.dp, start = 4.dp, end = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(
                    text = "DigiAssets",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (assets.isEmpty()) {
            // ── Empty state ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Stars,
                        contentDescription = null,
                        tint = DigiByteAccent.copy(alpha = 0.4f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "No DigiAssets found",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "DigiAssets are tokens issued on the DigiByte blockchain. " +
                               "Assets you receive will appear here automatically once your wallet " +
                               "finishes syncing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    val context = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://digiscope.me/assets/create"),
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = DigiByteAccent,
                        ),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Create DigiAsset on digiscope.me",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        } else {
            // ── Asset grid ────────────────────────────────────────────────
            Text(
                text = "${assets.size} asset${if (assets.size != 1) "s" else ""} found",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(assets, key = { it.assetId }) { asset ->
                    AssetCard(
                        asset = asset,
                        onClick = {
                            viewModel.selectAsset(asset.assetId)
                            onNavigateToAsset(asset.assetId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetCard(
    asset: OwnedAsset,
    onClick: () -> Unit
) {
    // "unresolved:<txid>" is the M1.1 placeholder for assets we've detected
    // natively but can't yet derive a real asset-id for (transfers, pre-M3).
    // Render a friendly short label instead of the raw placeholder.
    val isUnresolved = asset.assetId.startsWith("unresolved:")
    val displayName = asset.metadata?.name
        ?: if (isUnresolved) {
            "DigiAsset " + asset.assetId.substringAfter("unresolved:").take(6)
        } else {
            asset.assetId.take(8) + "…"
        }

    val symbol = asset.metadata?.symbol
    val firstLetter = displayName.firstOrNull()?.uppercaseChar() ?: 'A'

    // Subtitle replaces symbol when we have no metadata. Gives the user a
    // concrete "why can't I see the name" signal instead of silent empty.
    val subtitle = symbol ?: if (asset.metadata == null) "metadata offline" else null

    // Pick a deterministic accent color based on the asset ID hash
    val colorIndex = (asset.assetId.hashCode() and 0x7FFFFFFF) % assetIconColors.size
    val iconColor = assetIconColors[colorIndex]

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Icon / thumbnail ─────────────────────────────────────
            AssetIcon(
                imageUrl = asset.metadata?.imageUrl,
                firstLetter = firstLetter,
                iconColor = iconColor,
                size = 56.dp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Asset name ───────────────────────────────────────────
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ── Subtitle (symbol when known, offline hint otherwise) ─────
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (subtitle == symbol) DigiByteAccent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Quantity chip ────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DigiByteAccent.copy(alpha = 0.12f)
            ) {
                Text(
                    text = formatAssetQuantity(asset.quantity, asset.metadata?.decimals ?: 0),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = DigiByteAccent
                )
            }
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

/** Format a raw quantity with the asset's decimal places. */
internal fun formatAssetQuantity(quantity: Long, decimals: Int): String {
    if (decimals <= 0) return quantity.toString()
    val divisor = Math.pow(10.0, decimals.toDouble())
    val value = quantity / divisor
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.${decimals}f", value).trimEnd('0').trimEnd('.')
    }
}
