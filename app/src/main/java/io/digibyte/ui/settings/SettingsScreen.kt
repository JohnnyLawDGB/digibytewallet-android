package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import io.digibyte.BuildConfig
import io.digibyte.R
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue

@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val localeContext = androidx.compose.ui.platform.LocalContext.current
    var showLanguagePicker by remember { mutableStateOf(false) }
    var currentLanguage by remember {
        mutableStateOf(io.digibyte.ui.locale.LocaleController.current(localeContext))
    }

    if (showLanguagePicker) {
        io.digibyte.ui.locale.LanguagePickerSheet(
            selected = currentLanguage,
            onSelect = { entry ->
                currentLanguage = entry
                showLanguagePicker = false
                io.digibyte.ui.locale.LocaleController.apply(localeContext, entry)
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    // Dev-gate: the Advanced/Developer section (network toggle) only renders
    // in a debug build OR the digiTestnet flavor — a mainnet release build
    // never shows it, never even composes the toggle. BuildConfig.FLAVOR is
    // "mainnet" / "digiTestnet" per the `network` flavor dimension.
    val isDevBuild = BuildConfig.DEBUG || BuildConfig.FLAVOR == "digiTestnet"
    val networkTestnetEnabled by viewModel.networkTestnetEnabled.collectAsStateWithLifecycle()
    val betaUpdatesEnabled by viewModel.betaUpdatesEnabled.collectAsStateWithLifecycle()
    // CF-gated sync stage — attached to the bug-report deep link so intake knows
    // whether the user's issue was mid-sync or fully synced.
    val syncFrontier by viewModel.syncFrontier.collectAsStateWithLifecycle()
    var pendingNetworkTestnet by remember { mutableStateOf(false) }
    var showNetworkConfirmDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.set_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
        }

        item {
            SettingsCategory(title = stringResource(R.string.set_cat_security)) {
                SettingsRow(
                    icon = Icons.Default.Lock,
                    iconTint = DigiByteAccent,
                    title = stringResource(R.string.set_security),
                    subtitle = stringResource(R.string.set_security_sub),
                    onClick = { navController.navigate("settings_security") }
                )
            }
        }

        item {
            SettingsCategory(title = stringResource(R.string.set_cat_network)) {
                SettingsRow(
                    icon = Icons.Default.WifiTethering,
                    iconTint = Color(0xFF4CAF50),
                    title = stringResource(R.string.set_network_info),
                    subtitle = stringResource(R.string.set_network_info_sub),
                    onClick = { navController.navigate("settings_network") }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.BatteryChargingFull,
                    iconTint = Color(0xFF4CAF50),
                    title = stringResource(R.string.set_background_sync),
                    subtitle = stringResource(R.string.set_background_sync_sub),
                    onClick = { io.digibyte.util.BatteryOptimization.openBatterySettings(context) }
                )
            }
        }

        item {
            SettingsCategory(title = stringResource(R.string.set_cat_appearance)) {
                SettingsRow(
                    icon = Icons.Default.Palette,
                    iconTint = Color(0xFFFF9800),
                    title = stringResource(R.string.set_display),
                    subtitle = stringResource(R.string.set_display_sub),
                    onClick = { navController.navigate("settings_display") }
                )
                // Subtitle shows the CURRENT language in its own script, so someone who has
                // landed in a language they cannot read can still recognise this row and get
                // back out of it.
                SettingsRow(
                    icon = Icons.Default.Language,
                    iconTint = Color(0xFF4CAF50),
                    title = stringResource(R.string.set_language),
                    subtitle = currentLanguage?.endonym ?: stringResource(R.string.set_language_follow),
                    onClick = { showLanguagePicker = true }
                )
            }
        }

        item {
            SettingsCategory(title = stringResource(R.string.set_cat_recovery)) {
                SettingsRow(
                    icon = Icons.Default.CloudSync,
                    iconTint = Color(0xFF26C6DA),
                    title = stringResource(R.string.set_scan_funds),
                    subtitle = stringResource(R.string.set_scan_funds_sub),
                    onClick = { navController.navigate("settings_reconcile") }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.Savings,
                    iconTint = Color(0xFF26C6DA),
                    title = stringResource(R.string.set_recover_other),
                    subtitle = stringResource(R.string.set_recover_other_sub),
                    onClick = { navController.navigate("recover_funds") }
                )
            }
        }

        item {
            SettingsCategory(title = stringResource(R.string.set_cat_info)) {
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    iconTint = Color(0xFFFF6D00),
                    title = stringResource(R.string.set_beta),
                    subtitle = if (betaUpdatesEnabled)
                        stringResource(R.string.set_beta_on)
                    else
                        stringResource(R.string.set_beta_off),
                    onClick = { viewModel.setBetaUpdatesEnabled(!betaUpdatesEnabled) },
                    trailing = {
                        Switch(
                            checked = betaUpdatesEnabled,
                            onCheckedChange = { viewModel.setBetaUpdatesEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFFF6D00)
                            )
                        )
                    }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = DigiByteBlue,
                    title = stringResource(R.string.set_about),
                    subtitle = stringResource(R.string.set_about_sub),
                    onClick = { navController.navigate("settings_about") }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    iconTint = Color(0xFFFF6D00),
                    title = stringResource(R.string.set_report_bug),
                    subtitle = stringResource(R.string.set_report_bug_sub),
                    onClick = {
                        val url = buildBugReportUrl(context, syncFrontier.stage.name)
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
        }

        // Dev-only: never rendered in a mainnet release build (see isDevBuild above).
        if (isDevBuild) {
            item {
                SettingsCategory(title = stringResource(R.string.set_cat_advanced)) {
                    SettingsRow(
                        icon = Icons.Default.BugReport,
                        iconTint = Color(0xFFFF6D00),
                        title = stringResource(R.string.set_cat_network),
                        subtitle = if (networkTestnetEnabled)
                            stringResource(R.string.set_net_testnet)
                        else
                            stringResource(R.string.set_net_mainnet),
                        onClick = {
                            pendingNetworkTestnet = !networkTestnetEnabled
                            showNetworkConfirmDialog = true
                        },
                        trailing = {
                            Switch(
                                checked = networkTestnetEnabled,
                                onCheckedChange = { checked ->
                                    pendingNetworkTestnet = checked
                                    showNetworkConfirmDialog = true
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF6D00)
                                )
                            )
                        }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "DigiByte Wallet v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF546E7A),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }
    }

    // Confirm dialog — flipping the Network toggle restarts the app (design
    // doc §4.7 / §7.2), so we gate the actual write+restart behind an explicit
    // confirmation rather than firing it straight off the Switch/row tap.
    if (showNetworkConfirmDialog) {
        val goingToTestnet = pendingNetworkTestnet
        AlertDialog(
            onDismissRequest = { showNetworkConfirmDialog = false },
            containerColor = Color(0xFF1A2742),
            title = {
                Text(
                    text = if (goingToTestnet) stringResource(R.string.set_switch_testnet) else stringResource(R.string.set_switch_mainnet),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (goingToTestnet)
                        stringResource(R.string.set_switch_testnet_body)
                    else
                        stringResource(R.string.set_switch_mainnet_body),
                    color = Color(0xFFB0BEC5)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNetworkConfirmDialog = false
                    viewModel.setNetworkTestnet(goingToTestnet)
                }) {
                    Text(stringResource(R.string.set_restart), color = Color(0xFFFF6D00))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNetworkConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = Color(0xFF8899AA))
                }
            }
        )
    }
}

@Composable
fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF546E7A),
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742))
        ) {
            Column { content() }
        }
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    subtitleColor: Color = Color(0xFF8899AA),
    trailing: @Composable (() -> Unit)? = null
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor
            )
        }

        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF546E7A),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        color = Color(0xFF243352),
        thickness = 0.5.dp
    )
}
