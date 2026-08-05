package io.digibyte.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE DEFECT (Note 8, 2026-08-04). The keepalive watchdog answered every stale tick with
 * `cancel() + launch`. That is correct for a coroutine frozen by Doze and WRONG for one parked in
 * a blocking JNI call: cancel() cannot interrupt a thread inside JNI, so the old coroutine keeps
 * its Dispatchers.Default thread forever and the replacement wedges in exactly the same place.
 *
 * Observed: four respawns, EXACTLY 90 seconds apart (10s to the new loop's first tick, plus the
 * 80s stale window), each logging "no tick in 80s". One leaked thread per cycle out of a pool of
 * roughly one-per-core — which starves every other coroutine in the service, including the ones
 * driving the CF scan. So the "recovery" was actively converting a stall into an outage.
 *
 * The red arm here is [respawningBlindlyLeaksAThreadEveryCycle], which encodes the OLD policy and
 * shows it never stops replacing a job that never completes.
 */
class KeepaliveHealthTest {

    private val STALE = 60_000L
    private val LIMIT = 2

    // ---- the defect ----------------------------------------------------------------------

    @Test
    fun `a stale job whose predecessor never completed eventually gives up`() {
        // First stale pass: nothing wedged yet, replacing it is reasonable.
        assertEquals(
            KeepaliveAction.RESPAWN_STALE,
            keepaliveAction(
                jobExists = true, jobActive = true, jobCompleted = false,
                msSinceLastTick = 80_000L, staleThresholdMs = STALE,
                wedgedStreak = 0, wedgedLimit = LIMIT,
            )
        )
        // Second: the previous one STILL has not completed. Another copy would leak another
        // thread and wedge identically.
        assertEquals(
            KeepaliveAction.GIVE_UP_WEDGED,
            keepaliveAction(
                jobExists = true, jobActive = true, jobCompleted = false,
                msSinceLastTick = 80_000L, staleThresholdMs = STALE,
                wedgedStreak = 1, wedgedLimit = LIMIT,
            )
        )
    }

    @Test
    fun `respawningBlindlyLeaksAThreadEveryCycle`() {
        // THE OLD POLICY, encoded: stale -> always respawn, regardless of whether the job we just
        // cancelled ever completed. Run it over the observed sequence and count replacements.
        fun oldPolicy(stale: Boolean) = if (stale) KeepaliveAction.RESPAWN_STALE else KeepaliveAction.NONE

        var oldRespawns = 0
        var newRespawns = 0
        // Twenty watchdog passes against a job that is permanently stuck in JNI: always active,
        // never completed, never ticking.
        for (i in 0 until 20) {
            if (oldPolicy(true) == KeepaliveAction.RESPAWN_STALE) oldRespawns++
            val a = keepaliveAction(
                jobExists = true, jobActive = true, jobCompleted = false,
                msSinceLastTick = 80_000L, staleThresholdMs = STALE,
                wedgedStreak = newRespawns, wedgedLimit = LIMIT,
            )
            if (a == KeepaliveAction.RESPAWN_STALE) newRespawns++
        }
        assertEquals("the old policy replaces the job on every single pass", 20, oldRespawns)
        assertEquals("the new policy stops before exhausting the thread pool", LIMIT - 1, newRespawns)
    }

    // ---- and it must not break the cases the watchdog exists for --------------------------

    @Test
    fun `a genuinely dead job is respawned`() {
        assertEquals(
            KeepaliveAction.RESPAWN_DEAD,
            keepaliveAction(
                jobExists = true, jobActive = false, jobCompleted = true,
                msSinceLastTick = 5_000L, staleThresholdMs = STALE,
                wedgedStreak = 0, wedgedLimit = LIMIT,
            )
        )
    }

    @Test
    fun `a never-launched job is respawned`() {
        assertEquals(
            KeepaliveAction.RESPAWN_DEAD,
            keepaliveAction(
                jobExists = false, jobActive = false, jobCompleted = false,
                msSinceLastTick = -1L, staleThresholdMs = STALE,
                wedgedStreak = 0, wedgedLimit = LIMIT,
            )
        )
    }

    @Test
    fun `a frozen job that DID complete on cancel is still replaced indefinitely`() {
        // The Doze case the watchdog was built for: cancel actually works, the thread comes back,
        // so replacing it costs nothing and must keep working forever.
        repeat(50) {
            assertEquals(
                KeepaliveAction.RESPAWN_STALE,
                keepaliveAction(
                    jobExists = true, jobActive = true, jobCompleted = true,
                    msSinceLastTick = 90_000L, staleThresholdMs = STALE,
                    wedgedStreak = 0, wedgedLimit = LIMIT,
                )
            )
        }
    }

    @Test
    fun `a healthy ticking job is left alone`() {
        assertEquals(
            KeepaliveAction.NONE,
            keepaliveAction(
                jobExists = true, jobActive = true, jobCompleted = false,
                msSinceLastTick = 10_000L, staleThresholdMs = STALE,
                wedgedStreak = 0, wedgedLimit = LIMIT,
            )
        )
    }

    @Test
    fun `a freshly respawned job that has not ticked yet is not immediately re-killed`() {
        // lastTick is reset to "never" on respawn. Reading that as infinitely stale would respawn
        // it on every pass and it would never survive long enough to stamp anything.
        assertEquals(
            KeepaliveAction.NONE,
            keepaliveAction(
                jobExists = true, jobActive = true, jobCompleted = false,
                msSinceLastTick = -1L, staleThresholdMs = STALE,
                wedgedStreak = 0, wedgedLimit = LIMIT,
            )
        )
    }
}
