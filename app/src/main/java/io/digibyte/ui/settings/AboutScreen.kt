@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue

@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
            )
        },
        containerColor = Color(0xFF0A1628)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // ── App identity ─────────────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CurrencyBitcoin,
                            contentDescription = null,
                            tint = DigiByteAccent,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "DigiByte Wallet",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v3.0.0-alpha1",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DigiByteAccent
                        )
                        Text(
                            text = "Powered by DigiByte Core 8.26",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8899AA)
                        )
                    }
                }
            }

            // ── Build info ───────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Build Info") {
                    AboutInfoRow(
                        icon = Icons.Default.BuildCircle,
                        iconTint = Color(0xFF4CAF50),
                        label = "Build Type",
                        value = "Debug (alpha)"
                    )
                    SettingsRowDivider()
                    AboutInfoRow(
                        icon = Icons.Default.Security,
                        iconTint = DigiByteAccent,
                        label = "Security",
                        value = "Reproducible builds, multi-party attestation"
                    )
                    SettingsRowDivider()
                    AboutInfoRow(
                        icon = Icons.Default.Memory,
                        iconTint = Color(0xFF8899AA),
                        label = "DigiByte Core",
                        value = "8.26 (SPV node)"
                    )
                }
            }

            // ── Links ────────────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Open Source") {
                    AboutLinkRow(
                        icon = Icons.Default.Code,
                        iconTint = DigiByteBlue,
                        label = "Source Code",
                        subtitle = "github.com/JohnnyLawDGB/digibytewallet-android",
                        onClick = { openUrl("https://github.com/JohnnyLawDGB/digibytewallet-android") }
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        icon = Icons.Default.Gavel,
                        iconTint = Color(0xFF8899AA),
                        label = "Licenses",
                        subtitle = "Open source licenses used in this app",
                        onClick = { openUrl("https://github.com/JohnnyLawDGB/digibytewallet-android/blob/main/LICENSES.md") }
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        icon = Icons.Default.Forum,
                        iconTint = Color(0xFF4CAF50),
                        label = "DigiByte Community",
                        subtitle = "digibyte.org",
                        onClick = { openUrl("https://digibyte.org") }
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        icon = Icons.Default.BugReport,
                        iconTint = Color(0xFFFF9800),
                        label = "Report a Bug",
                        subtitle = "github.com/JohnnyLawDGB/digibytewallet-android/issues",
                        onClick = { openUrl("https://github.com/JohnnyLawDGB/digibytewallet-android/issues") }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "This software is provided under the MIT License.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF546E7A),
                        fontSize = 11.sp
                    )
                    Text(
                        "DigiByte is a decentralized blockchain and digital currency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF546E7A),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutInfoRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconTint.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8899AA)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconTint.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8899AA)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = Color(0xFF546E7A),
            modifier = Modifier.size(18.dp)
        )
    }
}
