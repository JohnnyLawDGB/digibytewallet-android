package app.aroundtheblock.wallet.core.hub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIGIRUNNER-RUNID: every score submit carries the runId the server issued for that exact run; a
 * new run (the first one and every "Play Again") asks for a new runId and never reuses the last;
 * a /run call that fails, throws or never answers leaves the submit to go out without a runId.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DigiRunnerRunsTest {

    private class Submitted(val score: Int, val runId: String?)

    private class Harness(scope: TestScope, val ids: MutableList<() -> String?>) {
        var starts = 0
        val submitted = mutableListOf<Submitted>()
        val runs = DigiRunnerRuns(
            scope = scope.backgroundScope,
            startRun = { starts++; ids.removeAt(0)() },
            submitScore = { score, _, _, _, runId -> submitted += Submitted(score, runId); true },
        )
    }

    private val first = "0123456789abcdef0123456789abcdef"
    private val second = "fedcba9876543210fedcba9876543210"

    @Test fun `a start then a submit carries the runId the server issued for that run`() = runTest {
        val h = Harness(this, mutableListOf({ first }))
        h.runs.onRunStarted()
        advanceUntilIdle()
        assertTrue(h.runs.submit(h.runs.capture(), 10, 1000, 0, 2))
        assertEquals(1, h.starts)
        assertEquals(listOf(first), h.submitted.map { it.runId })
    }

    @Test fun `Play Again asks the server for a new runId and the next submit carries it`() = runTest {
        val h = Harness(this, mutableListOf({ first }, { second }))
        h.runs.onRunStarted()
        advanceUntilIdle()
        h.runs.submit(h.runs.capture(), 10, 1000, 0, 2)
        h.runs.onRunStarted() // Play Again
        advanceUntilIdle()
        h.runs.submit(h.runs.capture(), 20, 2000, 0, 1)
        assertEquals(2, h.starts)
        assertEquals(listOf(first, second), h.submitted.map { it.runId })
    }

    @Test fun `a Play Again whose run call fails never reuses the previous runId`() = runTest {
        val h = Harness(this, mutableListOf({ first }, { null }))
        h.runs.onRunStarted()
        advanceUntilIdle()
        h.runs.onRunStarted()
        advanceUntilIdle()
        h.runs.submit(h.runs.capture(), 20, 2000, 0, 1)
        assertEquals(listOf<String?>(null), h.submitted.map { it.runId })
    }

    @Test fun `a failed run call still submits, without a runId`() = runTest {
        val h = Harness(this, mutableListOf({ null }))
        h.runs.onRunStarted()
        advanceUntilIdle()
        assertTrue(h.runs.submit(h.runs.capture(), 10, 1000, 0, 2))
        assertEquals(1, h.submitted.size)
        assertNull(h.submitted.single().runId)
    }

    @Test fun `a run call that throws neither crashes nor blocks the submit`() = runTest {
        val h = Harness(this, mutableListOf({ throw IllegalStateException("offline") }))
        h.runs.onRunStarted()
        advanceUntilIdle()
        assertTrue(h.runs.submit(h.runs.capture(), 10, 1000, 0, 2))
        assertNull(h.submitted.single().runId)
    }

    @Test fun `a run call that never answers holds the submit only for the bounded wait`() = runTest {
        val never = CompletableDeferred<String?>()
        var starts = 0
        val submitted = mutableListOf<String?>()
        val runs = DigiRunnerRuns(
            scope = backgroundScope,
            startRun = { starts++; never.await() },
            submitScore = { _, _, _, _, runId -> submitted += runId; true },
            runIdWaitMs = 1_000,
        )
        runs.onRunStarted()
        val before = currentTime
        runs.submit(runs.capture(), 10, 1000, 0, 2)
        assertEquals("the submit waited for the bounded wait, and no longer", 1_000L, currentTime - before)
        assertEquals(1, starts)
        assertEquals(listOf<String?>(null), submitted)
    }

    @Test fun `a submit before any run started goes out without a runId`() = runTest {
        val h = Harness(this, mutableListOf())
        h.runs.submit(h.runs.capture(), 10, 1000, 0, 2)
        assertEquals(0, h.starts)
        assertNull(h.submitted.single().runId)
    }

    @Test fun `a submit made while the run call is still answering waits for its runId`() = runTest {
        val gate = CompletableDeferred<String?>()
        val submitted = mutableListOf<String?>()
        val runs = DigiRunnerRuns(
            scope = backgroundScope,
            startRun = { gate.await() },
            submitScore = { _, _, _, _, runId -> submitted += runId; true },
        )
        runs.onRunStarted()
        val captured = runs.capture()
        val pending = async { runs.submit(captured, 10, 1000, 0, 2) }
        testScheduler.advanceTimeBy(100)
        gate.complete(first)
        pending.await()
        assertEquals(listOf<String?>(first), submitted)
    }

    @Test fun `a submit captured before a Play Again carries the old run's runId, not the new one's`() = runTest {
        val h = Harness(this, mutableListOf({ first }, { second }))
        h.runs.onRunStarted()
        advanceUntilIdle()
        val tapped = h.runs.capture() // Submit Score tapped ...
        h.runs.onRunStarted()         // ... then Play Again, before the submit coroutine ran
        advanceUntilIdle()
        h.runs.submit(tapped, 10, 1000, 0, 2)
        assertEquals(listOf<String?>(first), h.submitted.map { it.runId })
    }
}
