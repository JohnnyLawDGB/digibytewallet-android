@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import androidx.compose.ui.res.stringResource
import io.digibyte.R

/**
 * Currency CODES only. The human-readable name comes from [java.util.Currency], which already
 * knows every one of these in every locale the platform ships — so the picker reads "Euro" in
 * English, "欧元" in Chinese and "Евро" in Russian without us hand-translating fifteen names into
 * eleven languages, and without them drifting out of step as the list grows.
 */
private val FIAT_CURRENCY_CODES = listOf(
    "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY",
    "KRW", "BRL", "MXN", "INR", "ZAR", "SEK", "NOK",
)




private enum class ThemeChoice { DARK, LIGHT, SYSTEM }

@Composable
fun DisplaySettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currencySetFmt = stringResource(R.string.dsp_currency_set)
    // The SAME store the wallet reads. This screen used to write WalletConfig.fiatCurrency in
    // Room, which nothing else read — so it showed USD while the home screen showed BTC.
    val currencyContext = androidx.compose.ui.platform.LocalContext.current
    var currentCurrency by remember {
        mutableStateOf(
            io.digibyte.ui.wallet.DisplayCurrencyStore.resolve(
                pref = io.digibyte.ui.wallet.DisplayCurrencyStore.storedPref(currencyContext),
                legacy = null,
            )
        )
    }

    // Theme preference is stored in a simple in-process state for now (Phase 2: persist to Room)
    var themeChoice by remember { mutableStateOf(ThemeChoice.DARK) }
    var showCurrencyMenu by remember { mutableStateOf(false) }

    var snackMessage by remember { mutableStateOf<String?>(null) }

    if (showCurrencyMenu) {
        io.digibyte.ui.components.CurrencyPickerSheet(
            selected = currentCurrency,
            onSelect = { picked ->
                io.digibyte.ui.wallet.DisplayCurrencyStore.save(currencyContext, picked)
                currentCurrency = picked
                showCurrencyMenu = false
                snackMessage = currencySetFmt.format(picked.name)
            },
            onDismiss = { showCurrencyMenu = false },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_display), color = Color.White) },
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
            // ── Fiat currency ────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.dsp_currency)) {
                    SettingsRow(
                        icon = Icons.Default.AttachMoney,
                        iconTint = Color(0xFF4CAF50),
                        title = stringResource(R.string.dsp_fiat_currency),
                        subtitle = stringResource(
                            R.string.dsp_currency_subtitle,
                            currentCurrency.name,
                            io.digibyte.ui.components.currencyDisplayName(currentCurrency),
                        ),
                        onClick = { showCurrencyMenu = true }
                    )
                }
            }

            // ── Theme ────────────────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.dsp_theme)) {
                    Card(
                        shape = RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ThemeOption(
                                label = stringResource(R.string.dsp_dark),
                                description = stringResource(R.string.dsp_dark_sub),
                                icon = Icons.Default.DarkMode,
                                iconTint = DigiByteBlue,
                                selected = themeChoice == ThemeChoice.DARK,
                                onSelect = { themeChoice = ThemeChoice.DARK }
                            )
                            HorizontalDivider(
                                color = Color(0xFF243352),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 70.dp)
                            )
                            ThemeOption(
                                label = stringResource(R.string.dsp_light),
                                description = stringResource(R.string.dsp_light_sub),
                                icon = Icons.Default.LightMode,
                                iconTint = Color(0xFFFFD700),
                                selected = themeChoice == ThemeChoice.LIGHT,
                                onSelect = { themeChoice = ThemeChoice.LIGHT }
                            )
                            HorizontalDivider(
                                color = Color(0xFF243352),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 70.dp)
                            )
                            ThemeOption(
                                label = stringResource(R.string.dsp_system),
                                description = stringResource(R.string.dsp_system_sub),
                                icon = Icons.Default.SettingsBrightness,
                                iconTint = Color(0xFF8899AA),
                                selected = themeChoice == ThemeChoice.SYSTEM,
                                onSelect = { themeChoice = ThemeChoice.SYSTEM }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dsp_theme_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF546E7A),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // ── Price Source ─────────────────────────────────────────────
            item {
                SettingsCategory(title = stringResource(R.string.dsp_price_source)) {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // External API — active and selected
                            PriceSourceOption(
                                label = stringResource(R.string.dsp_external_api),
                                description = "CoinGecko · Binance",
                                selected = true,
                                enabled = true,
                                onSelect = {}
                            )
                            HorizontalDivider(
                                color = Color(0xFF243352),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 56.dp)
                            )
                            // On-Chain Oracle — greyed out, v9 mainnet pending
                            PriceSourceOption(
                                label = stringResource(R.string.dsp_onchain_oracle),
                                description = stringResource(R.string.dsp_v9_pending),
                                selected = false,
                                enabled = false,
                                onSelect = {}
                            )
                            HorizontalDivider(
                                color = Color(0xFF243352),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 56.dp)
                            )
                            // Auto — greyed out
                            PriceSourceOption(
                                label = stringResource(R.string.dsp_auto),
                                description = stringResource(R.string.dsp_v9_pending),
                                selected = false,
                                enabled = false,
                                onSelect = {}
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.dsp_oracle_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF546E7A),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PriceSourceOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    val labelColor = when {
        !enabled -> Color(0xFF546E7A)
        selected  -> Color.White
        else      -> Color(0xFFB0BEC5)
    }
    val descColor = if (enabled) Color(0xFF8899AA) else Color(0xFF3D5066)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descColor
            )
        }

        RadioButton(
            selected = selected,
            onClick = if (enabled) onSelect else null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = DigiByteAccent,
                unselectedColor = if (enabled) Color(0xFF546E7A) else Color(0xFF2D3D52),
                disabledUnselectedColor = Color(0xFF2D3D52),
                disabledSelectedColor = DigiByteAccent
            )
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    selected: Boolean,
    onSelect: () -> Unit
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
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8899AA)
            )
        }

        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = DigiByteAccent,
                unselectedColor = Color(0xFF546E7A)
            )
        )
    }
}
