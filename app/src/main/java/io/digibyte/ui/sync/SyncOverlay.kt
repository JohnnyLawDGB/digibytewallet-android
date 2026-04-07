package io.digibyte.ui.sync

import androidx.compose.foundation.layout.*
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
 * Shows:
 *  1. The DigiRunner mini-game during any non-Complete state
 *  2. Progress bar + block height
 *  3. "Play DigiRunner" button when fully synced
 */
@Composable
fun SyncOverlay(
    syncState: SyncState,
    onPlayGame: () -> Unit = {},
    onScoreSubmit: ((score: Int, distance: Int, coins: Int, livesRemaining: Int) -> Unit)? = null,
    onShowLeaderboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isComplete = syncState is SyncState.Complete

    if (!isComplete && syncState !is SyncState.Failed) {
        // Active sync — show game + progress
        var showGame by remember { mutableStateOf(true) }

        // Poll block height for progress display
        val blockHeight = remember { mutableLongStateOf(0L) }
        val estHeight = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                blockHeight.longValue = io.digibyte.core.bridge.NativeBridge.getLastBlockHeight()
                estHeight.longValue = io.digibyte.core.bridge.NativeBridge.getEstimatedBlockHeight()
                kotlinx.coroutines.delay(2000L)
            }
        }

        val progress = if (estHeight.longValue > 0 && blockHeight.longValue > 0) {
            (blockHeight.longValue.toFloat() / estHeight.longValue.toFloat()).coerceIn(0f, 1f)
        } else if (syncState is SyncState.Syncing) {
            syncState.progress
        } else 0f

        val block = blockHeight.longValue.let {
            if (it > 0) it
            else if (syncState is SyncState.Syncing) syncState.blockHeight
            else 0L
        }

        Column(modifier = modifier) {
            if (showGame) {
                DigiRunnerGame(
                    syncProgress = progress,
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
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            val statusText = when {
                block > 0 && estHeight.longValue > 0 ->
                    "Syncing: ${(progress * 100).toInt()}% — Block $block"
                block > 0 -> "Scanning blockchain — Block $block"
                else -> "Connecting to network\u2026"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    } else {
        // Fully synced — show Play DigiRunner button
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
                Text("\uD83C\uDFAE  Play DigiRunner")
            }
        }
    }
}
