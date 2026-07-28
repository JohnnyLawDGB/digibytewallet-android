package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy

/**
 * RecoveryDateScreen — lets the user pick the approximate wallet creation date.
 * The date maps to a checkpoint timestamp that gets passed to recoverWallet(),
 * enabling the SPV scan to start from that point rather than genesis.
 *
 * Checkpoints are extracted from BRChainParams.h BRMainNetCheckpoints[].
 */
@Composable
fun RecoveryDateScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var selectedOption by remember { mutableStateOf<RecoveryDateOption?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = DigiByteAccent,
                modifier = Modifier.size(48.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "When did you create this wallet?",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "This helps us skip unnecessary blockchain scanning and sync faster.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(32.dp))

            // Date range options derived from BRChainParams.h checkpoints
            recoveryDateOptions.forEach { option ->
                val isSelected = selectedOption == option
                RecoveryDateCard(
                    option = option,
                    isSelected = isSelected,
                    onClick = { selectedOption = option }
                )
                Spacer(Modifier.height(10.dp))
            }

            // "I don't remember" option — full rescan from genesis
            val fullRescan = RecoveryDateOption(
                label = "I don't remember",
                subtitle = "Full blockchain scan (slowest — use if unsure)",
                timestamp = 1389388394L // DigiByte genesis block timestamp
            )
            val isFullRescanSelected = selectedOption == fullRescan

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A2E))
                    .border(
                        1.5.dp,
                        if (isFullRescanSelected) DigiByteAccent else Color(0xFF243352),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { selectedOption = fullRescan }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = null,
                        tint = if (isFullRescanSelected) DigiByteAccent else Color(0xFF546E7A),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = fullRescan.label,
                            color = if (isFullRescanSelected) DigiByteAccent else Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = fullRescan.subtitle,
                            color = Color(0xFF546E7A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Honesty banner — the recovery scan starts from the chosen
            // year forward, so any UTXO created before that year (and not
            // yet spent) will not be included in the wallet's balance. If
            // the user picks "I don't remember", the full chain is scanned
            // (slow but complete).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FFAA00), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color(0xFFFFAA00),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Transactions older than the selected year will not be detected. " +
                            "If your wallet may have older history, choose an earlier year — or " +
                            "\"I don't remember\" for full coverage (slower).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFCC66)
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    val ts = selectedOption?.timestamp ?: fullRescan.timestamp
                    viewModel.setRecoveryTimestamp(ts)
                    viewModel.recoverWallet { success ->
                        if (success) {
                            navController.navigate("pin_setup") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    }
                },
                enabled = selectedOption != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(
                    text = "Start Recovery",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            if (uiState is OnboardingUiState.Loading) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = DigiByteAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Restoring wallet...", color = Color(0xFFB0BEC5), fontSize = 14.sp)
                }
            }

            if (uiState is OnboardingUiState.Error) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = (uiState as OnboardingUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            // Deep-restore depth gate (spec Part 3c): a plain, honest refusal when
            // the chosen restore is deeper than this build can scan on-device.
            // Not an error state — a "coming in a future update" message.
            if (uiState is OnboardingUiState.TooDeep) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33FFAA00), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFAA00),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = OnboardingUiState.RESTORE_TOO_DEEP_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFCC66),
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryDateCard(
    option: RecoveryDateOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) DigiByteNavy else Color(0xFF1A2742))
            .border(
                1.5.dp,
                if (isSelected) DigiByteAccent else Color(0xFF243352),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = DigiByteAccent,
                    unselectedColor = Color(0xFF546E7A)
                )
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = option.label,
                    color = if (isSelected) Color.White else Color(0xFFB0BEC5),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 15.sp
                )
                if (option.subtitle.isNotEmpty()) {
                    Text(
                        text = option.subtitle,
                        color = Color(0xFF546E7A),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// ── Checkpoint data ────────────────────────────────────────────────────────────

data class RecoveryDateOption(
    val label: String,
    val subtitle: String = "",
    val timestamp: Long  // Unix epoch seconds — passed to recoverWallet()
)

/**
 * Yearly checkpoint ranges derived from BRMainNetCheckpoints[] in BRChainParams.h.
 * Timestamps represent the START of that year window (the lowest checkpoint ts in that range).
 * The SPV scan starts from this timestamp and works forward.
 *
 * Source timestamps (from BRChainParams.h):
 *   2014: genesis 1389388394 (Jan 10 2014)
 *   2015: block 1380000 → 1447731080 (Nov 2015)  — use Jan 2015 (pre-checkpoint)
 *   2016: block 3000000 → 1472667779 (Sep 2016)   — use Jan 2016
 *   2017: block 4255555 → 1491329321 (Apr 2017)   — use Jan 2017
 *   2018: block 5862666 → 1515304798 (Jan 2018)
 *   2019: block 9000000 → 1562079462 (Jul 2019)   — use Jan 2019
 *   2020: block 10000000 → 1577042521 (Jan 2020)
 *   2021: block 12000000 → 1606947788 (Dec 2020)  — use Jan 2021
 *   2022: block 13510000 → 1629510979 (Aug 2021)  — use Jan 2022
 *   2023: block 16000000 → 1666719839 (Oct 2022)  — use Jan 2023
 *   2024: block 18000000 → 1696633305 (Oct 2023)  — use Jan 2024
 *   2025: block 20000000 → 1726553537 (Sep 2024)  — use Jan 2025
 *   2026: block 21500000 → 1748982571 (Jun 2025)  — use Jan 2026
 */
val recoveryDateOptions: List<RecoveryDateOption> = listOf(
    RecoveryDateOption(
        label = "2026",
        subtitle = "Jan 2026 onwards",
        timestamp = 1767225600L  // 2026-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2025",
        subtitle = "Jan 2025 – Dec 2025",
        timestamp = 1735689600L  // 2025-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2024",
        subtitle = "Jan 2024 – Dec 2024",
        timestamp = 1704067200L  // 2024-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2023",
        subtitle = "Jan 2023 – Dec 2023",
        timestamp = 1672531200L  // 2023-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2022",
        subtitle = "Jan 2022 – Dec 2022",
        timestamp = 1640995200L  // 2022-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2021",
        subtitle = "Jan 2021 – Dec 2021",
        timestamp = 1609459200L  // 2021-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2020",
        subtitle = "Jan 2020 – Dec 2020",
        timestamp = 1577836800L  // 2020-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2019",
        subtitle = "Jan 2019 – Dec 2019",
        timestamp = 1546300800L  // 2019-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2018",
        subtitle = "Jan 2018 – Dec 2018",
        timestamp = 1514764800L  // 2018-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2017",
        subtitle = "Jan 2017 – Dec 2017",
        timestamp = 1483228800L  // 2017-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2016",
        subtitle = "Jan 2016 – Dec 2016",
        timestamp = 1451606400L  // 2016-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2015",
        subtitle = "Jan 2015 – Dec 2015",
        timestamp = 1420070400L  // 2015-01-01 00:00:00 UTC
    ),
    RecoveryDateOption(
        label = "2014",
        subtitle = "DigiByte launched January 2014",
        timestamp = 1389388394L  // Genesis block timestamp
    ),
)
