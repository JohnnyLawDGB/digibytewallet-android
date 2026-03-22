package io.digibyte.ui.digiid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.db.entity.DigiIdHistoryEntity
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteNavy
import io.digibyte.ui.theme.DigiByteRed
import java.text.SimpleDateFormat
import java.util.*

/**
 * Digi-ID tab — main screen showing a Scan button and login history.
 *
 * Navigation: tap "Scan" → navigates to the shared QR scanner which, on a
 * digiid:// scan, will route to DigiIdConfirmScreen.
 */
@Composable
fun DigiIdScreen(
    onNavigateScan: () -> Unit,
    viewModel: DigiIdViewModel = hiltViewModel()
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── Header card ───────────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DigiByteNavy)
                    .padding(top = 40.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Digi-ID",
                        tint = DigiByteAccent,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Digi-ID",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Authenticate with DigiByte",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }

        // ── Scan button ───────────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onNavigateScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DigiByteAccent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Scan to Authenticate",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        // ── History header ────────────────────────────────────────────────
        item {
            Text(
                text = "Recent Logins",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        // ── History items ─────────────────────────────────────────────────
        if (history.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logins yet.\nScan a Digi-ID QR code to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(history, key = { it.id }) { entry ->
                DigiIdHistoryRow(entry = entry)
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DigiIdHistoryRow(entry: DigiIdHistoryEntity) {
    val timeAgo = remember(entry.timestamp) { formatTimeAgo(entry.timestamp) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Icon(
            imageVector = if (entry.success) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (entry.success) "Success" else "Failed",
            tint = if (entry.success) DigiByteGreen else DigiByteRed,
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.domain,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimeAgo(epochSeconds: Long): String {
    val nowSec = System.currentTimeMillis() / 1000
    val diff = nowSec - epochSeconds
    return when {
        diff < 60     -> "Just now"
        diff < 3600   -> "${diff / 60}m ago"
        diff < 86400  -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            .format(Date(epochSeconds * 1000))
    }
}
