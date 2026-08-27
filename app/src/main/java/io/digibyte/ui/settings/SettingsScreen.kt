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
import io.digibyte.BuildConfig
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
                io.digibyte.ui.locale.LocaleController.set(localeContext, entry)
                currentLanguage = entry
                showLanguagePicker = false
                // Resources are resolved when a context is created, so a running screen keeps the
                // old language until it is rebuilt. Recreate rather than leave the user tapping a
                // setting that appears to do nothing.
                (localeContext as? android.app.Activity)?.recreate()
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
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
        }

        item {
            SettingsCategory(title = "Security & Privacy") {
                SettingsRow(
                    icon = Icons.Default.Lock,
                    iconTint = DigiByteAccent,
                    title = "Security",
                    subtitle = "PIN, biometric auth, seed phrase, auto-lock",
                    onClick = { navController.navigate("settings_security") }
                )
            }
        }

        item {
            SettingsCategory(title = "Network") {
                SettingsRow(
                    icon = Icons.Default.WifiTethering,
                    iconTint = Color(0xFF4CAF50),
                    title = "Network Info",
                    subtitle = "Peers, sync status, block height",
                    onClick = { navController.navigate("settings_network") }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.BatteryChargingFull,
                    iconTint = Color(0xFF4CAF50),
                    title = "Background sync",
                    subtitle = "Allow unrestricted battery so sync keeps running with the screen off",
                    onClick = { io.digibyte.util.BatteryOptimization.openBatterySettings(context) }
                )
            }
        }

        item {
            SettingsCategory(title = "Appearance") {
                SettingsRow(
                    icon = Icons.Default.Palette,
                    iconTint = Color(0xFFFF9800),
                    title = "Display",
                    subtitle = "Fiat currency, theme",
                    onClick = { navController.navigate("settings_display") }
                )
                // Subtitle shows the CURRENT language in its own script, so someone who has
                // landed in a language they cannot read can still recognise this row and get
                // back out of it.
                SettingsRow(
                    icon = Icons.Default.Language,
                    iconTint = Color(0xFF4CAF50),
                    title = "Language",
                    subtitle = currentLanguage?.endonym ?: "Follow device language",
                    onClick = { showLanguagePicker = true }
                )
            }
        }

        item {
            SettingsCategory(title = "Recovery") {
                SettingsRow(
                    icon = Icons.Default.CloudSync,
                    iconTint = Color(0xFF26C6DA),
                    title = "Scan for missing funds",
                    subtitle = "Query a DGB node for UTXOs on wallet addresses",
                    onClick = { navController.navigate("settings_reconcile") }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.Savings,
                    iconTint = Color(0xFF26C6DA),
                    title = "Recover funds from another wallet",
                    subtitle = "Sweep coins from old/other derivation paths into this wallet",
                    onClick = { navController.navigate("recover_funds") }
                )
            }
        }

        item {
            SettingsCategory(title = "Info") {
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    iconTint = Color(0xFFFF6D00),
                    title = "Beta updates",
                    subtitle = if (betaUpdatesEnabled)
                        "On — you'll be offered pre-release builds for testing"
                    else
                        "Off — only stable releases are offered",
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
                    title = "About",
                    subtitle = "Version, licenses, open source",
                    onClick = { navController.navigate("settings_about") }
                )
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    iconTint = Color(0xFFFF6D00),
                    title = "Report a bug / feedback",
                    subtitle = "Send an issue or idea — device & version info is filled in for you",
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
                SettingsCategory(title = "Advanced / Developer") {
                    SettingsRow(
                        icon = Icons.Default.BugReport,
                        iconTint = Color(0xFFFF6D00),
                        title = "Network",
                        subtitle = if (networkTestnetEnabled)
                            "Testnet — test chain, same recovery phrase"
                        else
                            "Mainnet — production chain",
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
                    text = if (goingToTestnet) "Switch to Testnet?" else "Switch to Mainnet?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (goingToTestnet)
                        "The app will restart and sync the test chain. Your recovery phrase is unchanged."
                    else
                        "The app will restart and sync the main chain. Your recovery phrase is unchanged.",
                    color = Color(0xFFB0BEC5)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNetworkConfirmDialog = false
                    viewModel.setNetworkTestnet(goingToTestnet)
                }) {
                    Text("Restart", color = Color(0xFFFF6D00))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNetworkConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF8899AA))
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
