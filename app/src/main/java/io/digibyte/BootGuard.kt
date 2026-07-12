package io.digibyte

import android.content.Context

/**
 * Startup crash-loop breaker. Survives NATIVE crashes (SIGSEGV/SIGABRT) that a
 * try/catch can't — e.g. corrupt `saved_blocks` bytes fed to the native block
 * deserializer, or a corrupt encrypted DB — by detecting the failure on the
 * NEXT launch instead of trying to catch the signal.
 *
 * Mechanism: [beginBoot] bumps an on-disk counter (committed synchronously)
 * at the very start of Application.onCreate, before any risky native/DB work.
 * [markBootHealthy] resets it once the process has run stably past the risky
 * window. If a boot dies mid-startup the counter is never reset, so the next
 * launch sees the elevated count and recovers by wiping regenerable data —
 * graduated so the cheap fix is tried first, and the wallet seed/PIN are NEVER
 * touched (see [StaleDataWiper]).
 *
 * Counter semantics (a boot that completes healthily nets to 0):
 *   >= [THRESHOLD_BLOBS] dead boots  -> wipe saved sync state (header re-sync)
 *   >= [THRESHOLD_DB]    dead boots  -> also wipe the encrypted DB
 */
object BootGuard {
    private const val PREFS = "dgb_boot_guard"
    private const val KEY_PENDING = "pending_boots"

    /** Dead boots before the first (cheap) recovery. 2 so a single one-off crash
     *  (a fluke OOM/ANR-kill in the risky window) doesn't cost an unnecessary
     *  re-sync; a genuinely corrupt store crashes every boot and trips this fast. */
    const val THRESHOLD_BLOBS = 2

    /** Dead boots before escalating to a full DB wipe (sync-blob wipe didn't help). */
    const val THRESHOLD_DB = 4

    /** Reset the counter this long after onCreate. Any early-startup crash (native
     *  lib load, DB open, saved-blocks deserialize) kills the whole process well
     *  within this window, so the reset never fires for a crashing boot. */
    const val HEALTHY_AFTER_MS = 12_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Call FIRST in Application.onCreate. Recovers if prior boots died, then
     * records this boot attempt durably (commit, not apply) before any risky
     * init runs. Every wipe here preserves the seed + PIN.
     */
    fun beginBoot(context: Context) {
        val p = prefs(context)
        val pending = p.getInt(KEY_PENDING, 0)

        if (pending >= THRESHOLD_DB) {
            android.util.Log.e("BootGuard",
                "SAFE MODE: $pending consecutive dead boots — wiping sync state + DB (seed preserved)")
            StaleDataWiper.wipeSyncBlobs(context)
            StaleDataWiper.wipeDatabase(context)
        } else if (pending >= THRESHOLD_BLOBS) {
            android.util.Log.e("BootGuard",
                "SAFE MODE: $pending consecutive dead boots — wiping sync state (seed preserved)")
            StaleDataWiper.wipeSyncBlobs(context)
        }

        // Record this attempt durably BEFORE any risky work, so a crash below
        // leaves the counter elevated for the next launch to see.
        p.edit().putInt(KEY_PENDING, pending + 1).commit()
    }

    /**
     * Call once the process has cleared the risky startup window (from a delayed
     * task in onCreate, and eagerly after saved blocks load successfully). Resets
     * the counter so healthy boots never accumulate toward a wipe.
     */
    fun markBootHealthy(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_PENDING, 0) != 0) {
            android.util.Log.i("BootGuard", "Boot healthy — clearing crash-loop counter")
            p.edit().putInt(KEY_PENDING, 0).apply()
        }
    }
}
