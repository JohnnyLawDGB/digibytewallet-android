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
import androidx.compose.ui.res.stringResource
import io.digibyte.R

@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    val derivationPath = try {
        NativeBridge.getDerivationPath()
    } catch (_: Exception) { "m/84'/20'/0'" }

    fun openUrl(url: String) = io.digibyte.ui.util.openExternalUrl(context, url)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_about), color = Color.White) },
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
                            text = stringResource(R.string.ab_powered_by),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8899AA)
                        )
                    }
                }
            }

            // ── Wallet Info ──────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.ab_wallet_info)) {
                    WalletInfoRow(
                        label = stringResource(R.string.ab_wallet_type),
                        value = stringResource(R.string.ab_type_value),
                        description = stringResource(R.string.ab_type_desc)
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = stringResource(R.string.ab_hd_path),
                        value = derivationPath,
                        description = stringResource(R.string.ab_path_desc)
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = stringResource(R.string.ab_address_format),
                        value = stringResource(R.string.ab_format_value),
                        description = stringResource(R.string.ab_format_desc)
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = stringResource(R.string.set_cat_network),
                        value = stringResource(R.string.ab_network_value),
                        description = stringResource(R.string.ab_network_desc)
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = stringResource(R.string.set_security),
                        value = stringResource(R.string.ab_security_value),
                        description = stringResource(R.string.ab_security_desc)
                    )
                    SettingsRowDivider()
                    WalletInfoRow(
                        label = stringResource(R.string.ab_sync_method),
                        value = stringResource(R.string.ab_sync_value),
                        description = stringResource(R.string.ab_sync_desc)
                    )
                }
            }

            // ── Build info ───────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.ab_build_info)) {
                    AboutInfoRow(
                        icon = Icons.Default.BuildCircle,
                        iconTint = Color(0xFF4CAF50),
                        label = stringResource(R.string.ab_version),
                        value = "v$versionName"
                    )
                    SettingsRowDivider()
                    AboutInfoRow(
                        icon = Icons.Default.Security,
                        iconTint = DigiByteAccent,
                        label = stringResource(R.string.set_security),
                        value = stringResource(R.string.ab_security_build)
                    )
                    SettingsRowDivider()
                    AboutInfoRow(
                        icon = Icons.Default.Memory,
                        iconTint = Color(0xFF8899AA),
                        label = stringResource(R.string.ab_core),
                        value = stringResource(R.string.ab_core_value)
                    )
                }
            }

            // ── Links ────────────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.ab_open_source)) {
                    AboutLinkRow(
                        icon = Icons.Default.Code,
                        iconTint = DigiByteBlue,
                        label = stringResource(R.string.ab_source_code),
                        subtitle = "github.com/JohnnyLawDGB/digibytewallet-android",
                        onClick = { openUrl("https://github.com/JohnnyLawDGB/digibytewallet-android") }
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        icon = Icons.Default.Gavel,
                        iconTint = Color(0xFF8899AA),
                        label = stringResource(R.string.ab_licenses),
                        subtitle = stringResource(R.string.ab_licenses_sub),
                        onClick = { openUrl("https://github.com/JohnnyLawDGB/digibytewallet-android/blob/phase1-modernization/LICENSE") }
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        icon = Icons.Default.Forum,
                        iconTint = Color(0xFF4CAF50),
                        label = stringResource(R.string.ab_community),
                        subtitle = "digibyte.org",
                        onClick = { openUrl("https://digibyte.org") }
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        icon = Icons.Default.BugReport,
                        iconTint = Color(0xFFFF9800),
                        label = stringResource(R.string.ab_report_bug),
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
                        stringResource(R.string.ab_mit),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF546E7A),
                        fontSize = 11.sp
                    )
                    Text(
                        stringResource(R.string.ab_tagline),
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
