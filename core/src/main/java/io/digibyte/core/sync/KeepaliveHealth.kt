package io.digibyte.core.sync

/**
 * What the keepalive watchdog should do about the keepalive coroutine.
 *
 * Extracted from SyncService so the decision is testable without an Android Service. The
 * dispatching itself (handing the blocking native sweep to its own thread) is not expressible
 * here; this covers only the choice the watchdog makes each tick.
 */
enum class KeepaliveAction {
    /** Healthy, or not yet started. Do nothing. */
    NONE,

    /** The job is gone (finished, cancelled, never launched). Start one. */
    RESPAWN_DEAD,

    /** The job claims to be alive but has not ticked. Replace it. */
    RESPAWN_STALE,

    /**
     * Stale, and the job we already tried to cancel STILL has not completed, repeatedly.
     *
     * This is the case that used to be indistinguishable from RESPAWN_STALE and caused real harm.
     * Job.cancel() cannot interrupt a thread inside a JNI call, so the old coroutine keeps its
     * Dispatchers.Default thread indefinitely. Launching a replacement that blocks in the same
     * place leaks another thread every watchdog cycle, and the shared Default pool is small —
     * starving every other coroutine in the service, including the ones driving the CF scan.
     * Measured on a Note 8 on 2026-08-04: four respawns in six minutes, exactly 90s apart.
     *
     * Stop replacing it and report it instead.
     */
    GIVE_UP_WEDGED,
}

/**
 * @param jobExists          a keepalive job has been launched at least once
 * @param jobActive          Job.isActive
 * @param jobCompleted       Job.isCompleted — false after cancel() if the thread is stuck
 * @param msSinceLastTick    age of the last tick stamp; negative when never stamped
 * @param staleThresholdMs   how old a tick may get before the loop counts as frozen
 * @param wedgedStreak       consecutive prior attempts where the old job never completed
 * @param wedgedLimit        how many of those to tolerate before giving up
 */
fun keepaliveAction(
    jobExists: Boolean,
    jobActive: Boolean,
    jobCompleted: Boolean,
    msSinceLastTick: Long,
    staleThresholdMs: Long,
    wedgedStreak: Int,
    wedgedLimit: Int,
): KeepaliveAction {
    if (!jobExists || !jobActive) return KeepaliveAction.RESPAWN_DEAD

    // A negative age means "never stamped" — a freshly respawned loop that has not reached its
    // first tick yet. Treating that as stale would respawn it on every watchdog pass and it would
    // never get far enough to stamp anything.
    if (msSinceLastTick < 0L || msSinceLastTick <= staleThresholdMs) return KeepaliveAction.NONE

    // Stale. Whether replacing it is safe depends on whether the LAST replacement worked.
    if (!jobCompleted && wedgedStreak + 1 >= wedgedLimit) return KeepaliveAction.GIVE_UP_WEDGED

    return KeepaliveAction.RESPAWN_STALE
}
