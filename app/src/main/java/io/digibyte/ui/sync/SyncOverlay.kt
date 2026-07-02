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
 * During active sync: renders only the DigiRunner mini-game. The verbose
 * block / percent / ETA / match-count readout lives in [SyncProgressCard],
 * which is the single source of truth for user-facing sync progress. Having
 * a second progress bar here (with its own 2s poll cadence) caused two
 * numbers to bounce against each other because peer tip churn and polling
 * skew produced different snapshots of the same underlying state.
 *
 * When synced: renders the "Play DigiRunner" button.
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
        var showGame by remember { mutableStateOf(true) }

        // Game uses the authoritative syncState.progress directly; no separate
        // native polling so the physics-gated progress animation can't drift
        // against SyncProgressCard's percent.
        val progress = if (syncState is SyncState.Syncing) {
            syncState.progress.coerceIn(0f, 1f)
        } else 0f

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
            // Progress bar and status text intentionally removed — see
            // SyncProgressCard in WalletScreen for the live readout.
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
