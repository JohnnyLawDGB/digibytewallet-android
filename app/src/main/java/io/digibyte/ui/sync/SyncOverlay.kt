package io.digibyte.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.digibyte.core.model.SyncState
import io.digibyte.game.DigiRunnerGame

/**
 * SyncOverlay — shown inside the WalletScreen while the node is syncing.
 *
 * When syncing it shows:
 *  1. The DigiRunner mini-game (tap to jump, collect DGB coins)
 *  2. A "Skip game" button to collapse to progress-bar-only view
 *  3. A LinearProgressIndicator + block-height text (always visible)
 */
@Composable
fun SyncOverlay(
    syncState: SyncState,
    modifier: Modifier = Modifier
) {
    when (syncState) {
        is SyncState.Syncing -> {
            var showGame by remember { mutableStateOf(true) }

            Column(modifier = modifier) {
                if (showGame) {
                    DigiRunnerGame(
                        syncProgress = syncState.progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { showGame = false },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text  = "Skip game",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Progress bar — always visible while syncing
                LinearProgressIndicator(
                    progress = { syncState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Text(
                    text     = "Syncing: ${(syncState.progress * 100).toInt()}%  —  Block ${syncState.blockHeight}",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        is SyncState.Rescanning -> {
            var showGame by remember { mutableStateOf(true) }

            Column(modifier = modifier) {
                if (showGame) {
                    DigiRunnerGame(
                        syncProgress = 0.9f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { showGame = false },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "Skip game",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Text(
                    text = "Verifying transactions\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Not syncing — show nothing
        else -> {}
    }
}
