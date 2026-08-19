package io.digibyte.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order a mid-session peer-manager recreate must run in.
 *
 * A recovery path that just calls `forceReconnect()` + `startSync()` rebuilds the native
 * manager from the STALE cold-start `g_savedBlocks` — populated once at launch and never
 * refreshed — so the chain floors to the wallet birth checkpoint and the CF scan re-arms at
 * `cf_birth_height`. Measured on a Note 8: 24,052,509 -> 22,650,000, then ~6 hours of
 * re-scanning to get back.
 *
 * The fix is sequencing, not new machinery: refresh the near-tip window BEFORE the rebuild
 * consumes it, and restore the CF ledger AFTER the new manager exists.
 */
class RecreateSequenceTest {

    @Test fun the_four_steps_run_in_the_order_the_rebuild_requires() = runTest {
        val calls = mutableListOf<String>()

        RecreateSequence.run(
            flushPersistedState = {},
            reloadBlocksNearTip = { calls += "reload"; true },
            forceReconnect = { calls += "forceReconnect" },
            startSync = { calls += "startSync" },
            restoreLedgerAndSnap = { calls += "restoreLedger" },
        )

        assertEquals(listOf("reload", "forceReconnect", "startSync", "restoreLedger"), calls)
    }

    /**
     * Reloading the window must happen BEFORE the rebuild, because the rebuild is what
     * consumes it. Running it afterwards would leave the manager already floored — the
     * whole bug.
     */
    @Test fun the_window_is_refreshed_before_the_rebuild_consumes_it() = runTest {
        val calls = mutableListOf<String>()

        RecreateSequence.run(
            flushPersistedState = {},
            reloadBlocksNearTip = { calls += "reload"; true },
            forceReconnect = { calls += "forceReconnect" },
            startSync = { calls += "startSync" },
            restoreLedgerAndSnap = { calls += "restoreLedger" },
        )

        assertTrue(calls.indexOf("reload") < calls.indexOf("startSync"))
        assertTrue(calls.indexOf("startSync") < calls.indexOf("restoreLedger"))
    }

    /**
     * No saved window (fresh wallet, or a preflight that deliberately discarded it) must
     * NOT abort the recovery — the recreate still has to happen, we simply cannot improve
     * where it resumes.
     */
    @Test fun a_missing_window_still_recreates() = runTest {
        val calls = mutableListOf<String>()

        val result = RecreateSequence.run(
            flushPersistedState = {},
            reloadBlocksNearTip = { calls += "reload"; false },
            forceReconnect = { calls += "forceReconnect" },
            startSync = { calls += "startSync" },
            restoreLedgerAndSnap = { calls += "restoreLedger" },
        )

        assertEquals(listOf("reload", "forceReconnect", "startSync", "restoreLedger"), calls)
        assertEquals(false, result.windowReloaded)
    }

    /**
     * These run during recovery, when things are already going wrong. A throw in one step
     * must not strand the wallet with a half-rebuilt manager — the remaining steps still
     * run, and the failure is reported rather than swallowed silently.
     */
    @Test fun a_failing_step_does_not_abort_the_rest() = runTest {
        val calls = mutableListOf<String>()

        val result = RecreateSequence.run(
            flushPersistedState = {},
            reloadBlocksNearTip = { calls += "reload"; throw IllegalStateException("no disk") },
            forceReconnect = { calls += "forceReconnect" },
            startSync = { calls += "startSync" },
            restoreLedgerAndSnap = { calls += "restoreLedger" },
        )

        assertEquals(listOf("reload", "forceReconnect", "startSync", "restoreLedger"), calls)
        assertEquals(false, result.windowReloaded)
        assertTrue(result.failures.any { it.contains("reload") })
    }

    @Test fun a_clean_run_reports_no_failures() = runTest {
        val result = RecreateSequence.run(
            flushPersistedState = {},
            reloadBlocksNearTip = { true },
            forceReconnect = {},
            startSync = {},
            restoreLedgerAndSnap = {},
        )

        assertTrue(result.failures.isEmpty())
        assertEquals(true, result.windowReloaded)
    }

    /**
     * The reload and the ledger restore both read the LAST PERSISTED snapshot. The freshest
     * state this process knows lives in memory — the saved-blocks window is held in
     * `lastSavedBlocksData` until a save boundary, and the CF scan ledger sits in
     * `pendingCfLedger` until the coalesced writer's 20s tick. A recreate destroys the native
     * manager, so anything not on disk when it does is simply gone.
     *
     * Without this step the fix is only partial: it turns a 1.4M-block floor into a
     * one-save-interval give-back, paid again on every recovery. Flushing first makes the
     * disk copy the freshest copy, so the reload restores where the scan actually was.
     */
    @Test fun live_state_is_flushed_to_disk_before_the_reload_reads_it() = runTest {
        val calls = mutableListOf<String>()

        RecreateSequence.run(
            flushPersistedState = { calls += "flush" },
            reloadBlocksNearTip = { calls += "reload"; true },
            forceReconnect = { calls += "forceReconnect" },
            startSync = { calls += "startSync" },
            restoreLedgerAndSnap = { calls += "restoreLedger" },
        )

        assertEquals(
            listOf("flush", "reload", "forceReconnect", "startSync", "restoreLedger"),
            calls,
        )
    }

    /**
     * A failed flush costs at most the un-flushed interval; refusing to rebuild would leave
     * the wallet with a dead manager. So it is reported, not fatal — same rule as every other
     * step here.
     */
    @Test fun a_failed_flush_still_rebuilds_and_is_reported() = runTest {
        val calls = mutableListOf<String>()

        val result = RecreateSequence.run(
            flushPersistedState = { throw IllegalStateException("disk full") },
            reloadBlocksNearTip = { calls += "reload"; true },
            forceReconnect = { calls += "forceReconnect" },
            startSync = { calls += "startSync" },
            restoreLedgerAndSnap = { calls += "restoreLedger" },
        )

        assertEquals(listOf("reload", "forceReconnect", "startSync", "restoreLedger"), calls)
        assertTrue(result.failures.any { it.startsWith("flush:") })
    }
}
