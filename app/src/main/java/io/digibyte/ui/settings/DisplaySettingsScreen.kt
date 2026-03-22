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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue

private val FIAT_CURRENCIES = listOf(
    "USD" to "US Dollar",
    "EUR" to "Euro",
    "GBP" to "British Pound",
    "JPY" to "Japanese Yen",
    "AUD" to "Australian Dollar",
    "CAD" to "Canadian Dollar",
    "CHF" to "Swiss Franc",
    "CNY" to "Chinese Yuan",
    "KRW" to "South Korean Won",
    "BRL" to "Brazilian Real",
    "MXN" to "Mexican Peso",
    "INR" to "Indian Rupee",
    "ZAR" to "South African Rand",
    "SEK" to "Swedish Krona",
    "NOK" to "Norwegian Krone",
)

private enum class ThemeChoice { DARK, LIGHT, SYSTEM }

@Composable
fun DisplaySettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentCurrency by viewModel.fiatCurrency.collectAsState()

    // Theme preference is stored in a simple in-process state for now (Phase 2: persist to Room)
    var themeChoice by remember { mutableStateOf(ThemeChoice.DARK) }
    var showCurrencyMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var snackMessage by remember { mutableStateOf<String?>(null) }
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
                title = { Text("Display", color = Color.White) },
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
                SettingsCategory(title = "Currency") {
                    val currencyLabel = FIAT_CURRENCIES
                        .firstOrNull { it.first == currentCurrency }
                        ?.let { "${it.first} — ${it.second}" }
                        ?: currentCurrency

                    Box {
                        SettingsRow(
                            icon = Icons.Default.AttachMoney,
                            iconTint = Color(0xFF4CAF50),
                            title = "Fiat Currency",
                            subtitle = currencyLabel,
                            onClick = { showCurrencyMenu = true }
                        )
                        DropdownMenu(
                            expanded = showCurrencyMenu,
                            onDismissRequest = { showCurrencyMenu = false },
                            modifier = Modifier
                                .background(Color(0xFF1A2742))
                                .heightIn(max = 300.dp)
                        ) {
                            FIAT_CURRENCIES.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                code,
                                                color = if (code == currentCurrency) DigiByteAccent else Color.White,
                                                fontWeight = if (code == currentCurrency) FontWeight.SemiBold else FontWeight.Normal,
                                                modifier = Modifier.width(40.dp)
                                            )
                                            Text(
                                                name,
                                                color = if (code == currentCurrency) DigiByteAccent else Color(0xFF8899AA),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    },
                                    trailingIcon = if (code == currentCurrency) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                null,
                                                tint = DigiByteAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        viewModel.setFiatCurrency(code)
                                        showCurrencyMenu = false
                                        snackMessage = "Currency set to $code"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Theme ────────────────────────────────────────────────────────
            item {
                SettingsCategory(title = "Theme") {
                    Card(
                        shape = RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ThemeOption(
                                label = "Dark",
                                description = "Dark blue theme (recommended)",
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
                                label = "Light",
                                description = "Light theme",
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
                                label = "System Default",
                                description = "Follow system dark/light mode",
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
                    text = "Theme changes will take full effect in a future update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF546E7A),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // ── Price Source ─────────────────────────────────────────────
            item {
                SettingsCategory(title = "Price Source") {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // External API — active and selected
                            PriceSourceOption(
                                label = "External API",
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
                                label = "On-Chain Oracle",
                                description = "Available when v9 launches on mainnet",
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
                                label = "Auto",
                                description = "Available when v9 launches on mainnet",
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
                    text = "On-chain oracle price feeds require DigiByte v9 on mainnet.",
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
