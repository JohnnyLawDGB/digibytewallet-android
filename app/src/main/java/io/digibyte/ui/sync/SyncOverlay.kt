package io.digibyte.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.digibyte.core.model.SyncStage
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
    stage: SyncStage,
    progress: Float,
    onPlayGame: () -> Unit = {},
    onScoreSubmit: ((score: Int, distance: Int, coins: Int, livesRemaining: Int) -> Unit)? = null,
    onShowLeaderboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // CF-gated stage — matches the main SyncProgressCard, so the game/Play
    // button never says "synced" while the wallet is still catching up.
    val isComplete = stage == SyncStage.Synced

    if (!isComplete && stage != SyncStage.Failed) {
        var showGame by remember { mutableStateOf(true) }

        // Game uses the CF-gated progress fraction directly (same source as
        // SyncProgressCard) so the physics-gated animation can't drift against
        // the card's percent.
        val gameProgress = progress.coerceIn(0f, 1f)

        Column(modifier = modifier) {
            if (showGame) {
                DigiRunnerGame(
                    syncProgress = gameProgress,
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
