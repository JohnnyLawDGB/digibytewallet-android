@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue

@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    val derivationPath = try {
        NativeBridge.getDerivationPath()
    } catch (_: Exception) { "m/84'/20'/0'" }

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
                        Image(
                            painter = painterResource(id = io.digibyte.R.drawable.dgb_symbol),
                            contentDescription = "DigiByte",
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "DigiByte Wallet",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v$versionName",
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

            // ── Wallet Info ──────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Wallet Info") {
                    WalletInfoRow(
                        label = "Wallet Type",
                        value = "HD Wallet (BIP84)",
                        description = "Hierarchical Deterministic wallet created from a seed phrase. Can generate many addresses from one backup — like having unlimited account numbers from one master key."
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = "HD Path",
                        value = derivationPath,
                        description = "The derivation path used to generate your addresses. 84 = BIP84 standard, 20 = DigiByte coin type (SLIP44), 0 = first account. This ensures any BIP84-compatible wallet can restore your addresses from the same seed phrase. Compatible with Ian Coleman's BIP39 tool."
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = "Address Format",
                        value = "SegWit (Bech32)",
                        description = "Uses modern SegWit addresses starting with dgb1. These are more efficient and have lower fees than older Legacy addresses starting with D."
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = "Network",
                        value = "Mainnet",
                        description = "Mainnet is the real DigiByte network where DGB has actual value. Testnet is for developers to test without real money."
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = "Security",
                        value = "Self-Custodial",
                        description = "Your keys never leave this device. No company or server holds your funds — only you control your wallet. If you lose your seed phrase, nobody can recover it for you."
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = "Sync Method",
                        value = "SPV (Bloom Filters)",
                        description = "Simplified Payment Verification — syncs directly with the DigiByte P2P network without downloading the full blockchain. Bloom filters protect your privacy by not revealing exactly which addresses you own."
                    )
                }
            }

            // ── Build info ───────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Build Info") {
                    AboutInfoRow(
                        icon = Icons.Default.BuildCircle,
                        iconTint = Color(0xFF4CAF50),
                        label = "Version",
                        value = "v$versionName"
                    )
                    SettingsRowDivider()
                    AboutInfoRow(
                        icon = Icons.Default.Security,
                        iconTint = DigiByteAccent,
                        label = "Security",
                        value = "AES-256-GCM seed encryption, hardware-backed Keystore"
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
                        subtitle = "MIT License — open source",
                        onClick = { openUrl("https://github.com/JohnnyLawDGB/digibytewallet-android/blob/phase1-modernization/LICENSE") }
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
private fun WalletInfoRow(
    label: String,
    value: String,
    description: String
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8899AA)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0D1B2E)
            ) {
                Text(
                    text = description,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8899AA),
                    lineHeight = 18.sp
                )
            }
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
