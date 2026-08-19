package io.digibyte.core.sync

/**
 * The order a mid-session peer-manager recreate must run in.
 *
 * A recovery path that simply calls `forceReconnect()` then `startSync()` rebuilds the
 * native manager from the STALE cold-start `g_savedBlocks` — populated once at launch and
 * never refreshed from the advancing chain — so `manager->lastBlock` floors to the wallet
 * birth checkpoint and auto-fetch re-arms at `cf_birth_height`. Measured on a Note 8:
 * a scan at 24,052,509 dropped to 22,650,000 and spent ~6 hours climbing back.
 *
 * The fix is sequencing rather than new machinery:
 *
 *   0. flush live state to disk — the steps below read the last PERSISTED snapshot, and
 *      the rebuild destroys whatever was only in memory;
 *   1. refresh the near-tip window — BEFORE the rebuild, because the rebuild consumes it;
 *   2. mark the manager for recreate;
 *   3. rebuild it;
 *   4. restore the CF ledger and snap the resume cursor — AFTER the new manager exists.
 *
 * Kept here, free of Android and of NativeBridge, so the ordering is a unit test rather
 * than something only observable on a device mid-failure.
 */
object RecreateSequence {

    /**
     * @param windowReloaded whether a near-tip window actually reached the core. False is
     *   normal (fresh wallet, or a preflight that deliberately discarded the window) — it
     *   means the recreate could not improve where the scan resumes, not that it failed.
     * @param failures one entry per step that threw, named, so a partial recovery is
     *   reported instead of silently swallowed.
     */
    data class Result(val windowReloaded: Boolean, val failures: List<String>)

    /**
     * Every step runs even if an earlier one throws. These paths execute during recovery,
     * when something has already gone wrong; aborting halfway would leave the wallet with a
     * manager that was marked for rebuild and never rebuilt — a worse state than the one
     * being recovered from.
     */
    suspend fun run(
        flushPersistedState: suspend () -> Unit,
        reloadBlocksNearTip: suspend () -> Boolean,
        forceReconnect: suspend () -> Unit,
        startSync: suspend () -> Unit,
        restoreLedgerAndSnap: suspend () -> Unit,
    ): Result {
        val failures = mutableListOf<String>()
        var reloaded = false

        // Step 0. Both restore steps below read the last PERSISTED snapshot, while the
        // freshest state this process has is still in memory (the saved-blocks window until
        // its save boundary, the CF scan ledger until the coalesced writer's next tick).
        // The rebuild destroys the native manager, so anything not on disk by then is gone
        // — and without this the fix only shrinks the give-back to one save interval,
        // charged again on every recovery. No default: skipping the flush must be a choice
        // a call site writes down, not one it inherits.
        try {
            flushPersistedState()
        } catch (t: Throwable) {
            failures += "flush: ${t.message}"
        }

        try {
            reloaded = reloadBlocksNearTip()
        } catch (t: Throwable) {
            failures += "reload: ${t.message}"
        }
        try {
            forceReconnect()
        } catch (t: Throwable) {
            failures += "forceReconnect: ${t.message}"
        }
        try {
            startSync()
        } catch (t: Throwable) {
            failures += "startSync: ${t.message}"
        }
        try {
            restoreLedgerAndSnap()
        } catch (t: Throwable) {
            failures += "restoreLedger: ${t.message}"
        }

        return Result(windowReloaded = reloaded, failures = failures)
    }
}
