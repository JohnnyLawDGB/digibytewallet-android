@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.digibyte.digidollar.LockTiers
import io.digibyte.digidollar.MintBuilder
import io.digibyte.ui.theme.DigiByteAccent

/**
 * The Mint calculator + confirm screen (issue #11): enter a USD DigiDollar
 * amount, pick a Lock tier, see the DGB collateral it will lock (live Oracle
 * price), and confirm to Mint. On success the collateral shows up as a new
 * Position. Reached from the Positions screen; testnet-only until 4.0.0.
 */
@Composable
fun MintScreen(
    onBack: () -> Unit,
    onMinted: () -> Unit,
    viewModel: MintViewModel = hiltViewModel(),
) {
    val amountUsd by viewModel.amountUsd.collectAsState()
    val tierIndex by viewModel.tierIndex.collectAsState()
    val status by viewModel.status.collectAsState()
    val statusLoaded by viewModel.statusLoaded.collectAsState()
    val mintState by viewModel.mintState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var confirming by remember { mutableStateOf(false) }

    val cents = MintViewModel.parseUsdToCents(amountUsd)
    val amountValid = cents != null && MintViewModel.mintAmountValid(cents)
    val tier = LockTiers.byIndex(tierIndex)
    val liveStatus = status
    val collateralSats = if (amountValid && liveStatus != null) {
        MintViewModel.previewCollateralSats(
            cents!!, tier, liveStatus.priceMicroUsd, liveStatus.dcaMultiplierBps,
        )
    } else {
        null
    }

    LaunchedEffect(mintState) {
        when (val s = mintState) {
            is MintViewModel.MintState.Failed -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.dismissResult()
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Mint DigiDollar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Amount", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = amountUsd,
                onValueChange = viewModel::onAmountChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0.00") },
                prefix = { Text("$", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = amountUsd.isNotBlank() && !amountValid,
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (amountUsd.isNotBlank() && !amountValid) {
                    "Enter an amount between \$100 and \$100,000"
                } else {
                    "DigiDollar minted, backed by locked DGB collateral"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (amountUsd.isNotBlank() && !amountValid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(16.dp))
            Text("Lock period", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            TierDropdown(tierIndex = tierIndex, onSelect = viewModel::selectTier)

            Spacer(Modifier.height(16.dp))
            CollateralPreview(
                statusLoaded = statusLoaded,
                collateralSats = collateralSats,
                oraclePriceMicroUsd = liveStatus?.priceMicroUsd,
                tierLabel = tier.label,
                onRetry = viewModel::refreshStatus,
            )

            Spacer(Modifier.height(24.dp))
            val minting = mintState is MintViewModel.MintState.Minting
            Button(
                onClick = { confirming = true },
                enabled = amountValid && collateralSats != null && !minting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (minting) "Minting…" else "Mint DigiDollar")
            }
        }
    }

    if (confirming && cents != null && collateralSats != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Mint ${WalletViewModel.formatDigiDollar(cents)}?") },
            text = {
                Text(
                    "Locks ${WalletViewModel.formatSatoshis(collateralSats)} of collateral " +
                        "for ${tier.label}. The collateral returns to this wallet when you " +
                        "redeem the position after it unlocks.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.mint()
                }) { Text("Mint") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }

    (mintState as? MintViewModel.MintState.Success)?.let { success ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("DigiDollar minted") },
            text = {
                Text(
                    "Locked ${WalletViewModel.formatSatoshis(success.collateralSats)} of " +
                        "collateral. The position unlocks at block %,d — redeem it there to "
                            .format(success.unlockHeight) +
                        "reclaim your DGB.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissResult()
                    onMinted()
                }) { Text("View positions") }
            },
        )
    }
}

@Composable
private fun TierDropdown(tierIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val tier = LockTiers.byIndex(tierIndex)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = "${tier.label}  ·  ${tier.ratioPercent}% collateral",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LockTiers.ALL.forEach { t ->
                DropdownMenuItem(
                    text = { Text("${t.label}  ·  ${t.ratioPercent}% collateral") },
                    onClick = {
                        onSelect(t.index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CollateralPreview(
    statusLoaded: Boolean?,
    collateralSats: Long?,
    oraclePriceMicroUsd: Long?,
    tierLabel: String,
    onRetry: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when {
                statusLoaded == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  Fetching the Oracle price…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                statusLoaded == false -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Price unavailable — Mint is blocked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }

                collateralSats == null -> Text(
                    "Enter an amount to preview the required collateral.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    PreviewRow("Collateral to lock", WalletViewModel.formatSatoshis(collateralSats))
                    Spacer(Modifier.height(6.dp))
                    PreviewRow(
                        "Network fee",
                        WalletViewModel.formatSatoshis(MintBuilder.DEFAULT_FEE_SATS),
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    PreviewRow(
                        "Total from wallet",
                        WalletViewModel.formatSatoshis(collateralSats + MintBuilder.DEFAULT_FEE_SATS),
                        bold = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Locked for $tierLabel" +
                            (oraclePriceMicroUsd?.let {
                                "  ·  Oracle price \$%.4f/DGB".format(it / 1_000_000.0)
                            } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String, bold: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (bold) DigiByteAccent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
