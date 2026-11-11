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
 * reused across runs. [capture] takes the run being scored at the moment its score is submitted,
 * synchronously in the tap, and [submit] sends the score with that run's runId, waiting at most
 * [runIdWaitMs] for a run call still in flight. Capturing in the tap matters: read later, inside
 * the coroutine, a Play Again tapped before it is dispatched would bind the old score to the new
 * run's runId, and the server refuses that honest score. A run call that fails, throws or never answers leaves
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

    /** The run being played now, as a submit made at this moment must use it. */
    class Run internal constructor(internal val runId: Deferred<String?>?)

    fun capture(): Run = Run(current)

    suspend fun submit(run: Run, score: Int, distance: Int, coins: Int, livesRemaining: Int): Boolean {
        val runId = run.runId?.let { withTimeoutOrNull(runIdWaitMs) { it.await() } }
        return submitScore(score, distance, coins, livesRemaining, runId)
    }
}
