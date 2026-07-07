@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.digibyte.core.digidollar.RedemptionService
import io.digibyte.ui.theme.DigiByteAccent

/**
 * The wallet's open DigiDollar collateral positions (issue #10): what each
 * Mint locked up, when it unlocks, and — once the timelock has expired — a
 * Redeem action that burns the minted DigiDollar and returns the collateral.
 * Testnet-only surface until the 4.0.0 mainnet unlock (the navigation entry
 * is gated, and RedemptionService refuses mainnet regardless).
 */
@Composable
fun DigiDollarPositionsScreen(
    onBack: () -> Unit,
    viewModel: PositionsViewModel = hiltViewModel(),
) {
    val positions by viewModel.positions.collectAsState()
    val redeemState by viewModel.redeemState.collectAsState()
    var confirming by remember { mutableStateOf<RedemptionService.CollateralPosition?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(redeemState) {
        when (val s = redeemState) {
            is PositionsViewModel.RedeemState.Success -> {
                snackbarHostState.showSnackbar(
                    "Redeemed — ${WalletViewModel.formatSatoshis(s.collateralReturnedSats)} returned",
                )
                viewModel.dismissRedeemResult()
            }
            is PositionsViewModel.RedeemState.Failed -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.dismissRedeemResult()
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("DigiDollar Positions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val rows = positions) {
            null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> if (rows.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No open positions.\nMint DigiDollar to lock DGB collateral here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rows, key = { "${it.position.txidHex}:${it.position.vout}" }) { row ->
                        PositionCard(
                            row = row,
                            redeeming = redeemState is PositionsViewModel.RedeemState.Redeeming,
                            onRedeem = { confirming = row.position },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { position ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Redeem position?") },
            text = {
                Text(
                    "Burns ${WalletViewModel.formatDigiDollar(position.ddCents)} of DigiDollar " +
                        "and returns ${WalletViewModel.formatSatoshis(position.valueSats)} " +
                        "of collateral to this wallet.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.redeem(position)
                }) { Text("Redeem") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PositionCard(
    row: PositionsViewModel.PositionRow,
    redeeming: Boolean,
    onRedeem: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    WalletViewModel.formatDigiDollar(row.position.ddCents),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = DigiByteAccent,
                )
                if (row.unlocked) {
                    Button(onClick = onRedeem, enabled = !redeeming) {
                        Text(if (redeeming) "Redeeming…" else "Redeem")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Collateral: ${WalletViewModel.formatSatoshis(row.position.valueSats)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (row.unlocked) {
                    "Unlocked — ready to redeem"
                } else {
                    "Unlocks at block %,d — %,d blocks to go"
                        .format(row.position.lockHeight, row.blocksRemaining)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (row.unlocked) {
                    DigiByteAccent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
