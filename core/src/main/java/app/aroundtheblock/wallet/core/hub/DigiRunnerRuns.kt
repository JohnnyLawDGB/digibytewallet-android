package app.aroundtheblock.wallet.core.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DIGIRUNNER-RUNID: the server-issued run behind each DigiRunner score.
 *
 * [onRunStarted] is called as each run starts (the first one and every "Play Again"); it asks the
 * server for that run's runId in the background and forgets the previous one, so a runId is never
 * reused across runs. [submit] sends the score with the current run's runId, waiting at most
 * [runIdWaitMs] for a run call still in flight. A run call that fails, throws or never answers leaves
 * the score to go out without a runId: play is never blocked and nothing here throws but cancellation.
 *
 * The runId is never logged (LogRedactionSourceGateTest).
 */
class DigiRunnerRuns(
    private val scope: CoroutineScope,
    private val startRun: suspend () -> String?,
    private val submitScore: suspend (score: Int, distance: Int, coins: Int, livesRemaining: Int, runId: String?) -> Boolean,
    private val runIdWaitMs: Long = RUN_ID_WAIT_MS,
) {
    companion object {
        /** How long a submit waits for its run's runId before it goes out without one. */
        const val RUN_ID_WAIT_MS = 5_000L
    }

    @Volatile
    private var current: Deferred<String?>? = null

    fun onRunStarted() {
        current = scope.async {
            try {
                startRun()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun submit(score: Int, distance: Int, coins: Int, livesRemaining: Int): Boolean {
        val run = current
        val runId = run?.let { withTimeoutOrNull(runIdWaitMs) { it.await() } }
        return submitScore(score, distance, coins, livesRemaining, runId)
    }
}
