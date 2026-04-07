package io.digibyte.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.digibyte.core.model.SyncState
import io.digibyte.game.DigiRunnerGame
import io.digibyte.ui.theme.DigiByteBlue

/**
 * SyncOverlay — shown inside the WalletScreen while the node is syncing.
 *
 * When syncing it shows:
 *  1. The DigiRunner mini-game (tap to jump, collect DGB coins)
 *  2. A "Skip game" button to collapse to progress-bar-only view
 *  3. A LinearProgressIndicator + block-height text (always visible)
 *
 * When idle/complete it shows a "Play DigiRunner" button so the user
 * can launch the standalone game at any time.
 */
@Composable
fun SyncOverlay(
    syncState: SyncState,
    onPlayGame: () -> Unit = {},
    onScoreSubmit: ((score: Int, distance: Int, coins: Int, livesRemaining: Int) -> Unit)? = null,
    onShowLeaderboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    when (syncState) {
        is SyncState.Syncing -> {
            var showGame by remember { mutableStateOf(true) }

            Column(modifier = modifier) {
                if (showGame) {
                    DigiRunnerGame(
                        syncProgress = syncState.progress,
                        onScoreSubmit = onScoreSubmit,
                        onShowLeaderboard = onShowLeaderboard,
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
            val blockHeight = remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    blockHeight.longValue = io.digibyte.core.bridge.NativeBridge.getLastBlockHeight()
                    kotlinx.coroutines.delay(2000L)
                }
            }

            Column(modifier = modifier) {
                if (showGame) {
                    DigiRunnerGame(
                        syncProgress = 0.9f,
                        onScoreSubmit = onScoreSubmit,
                        onShowLeaderboard = onShowLeaderboard,
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

                val block = blockHeight.longValue
                Text(
                    text = if (block > 0) "Scanning blockchain — Block $block" else "Verifying transactions\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Not syncing — show Play DigiRunner button
        else -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                OutlinedButton(
                    onClick = onPlayGame,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DigiByteBlue
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play DigiRunner")
                }
            }
        }
    }
}
