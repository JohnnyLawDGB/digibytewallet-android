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
 *
 * ## Restore-crash bracket ([beginRestore]/[markRestoreHealthy]/[recoverFromCrashedRestoreIfNeeded])
 *
 * The counter above only catches crashes in the PRE-unlock startup window (native
 * lib load, DB open) — [markBootHealthy] fires ~12s after onCreate, well before a
 * user has usually unlocked. A corrupt persisted blob that only crashes the
 * POST-unlock wallet-restore (`recoverWalletFromBytes` / `loadSavedBlocks` fed a
 * corrupt `saved_transactions`/`saved_blocks` blob) would crash AFTER that timer
 * already reset `pending_boots`, so it would never trip the counter above and the
 * device would crash on every launch forever.
 *
 * This is a SEPARATE flag/counter (`restore_pending` / `restore_crash_count`),
 * distinct from `pending_boots`, bracketing specifically the post-unlock restore:
 *   1. [beginRestore] sets `restore_pending = true` (commit) at the START of the
 *      restore, before `recoverWalletFromBytes`/`loadSavedBlocks` run.
 *   2. [markRestoreHealthy] clears it once the restore's risky window has
 *      genuinely completed (after `loadSavedBlocks` returns, not just after
 *      `recoverWalletFromBytes` — the corrupt-blob crash lives in the block
 *      deserializer).
 *   3. [recoverFromCrashedRestoreIfNeeded], called in `onCreate` right after
 *      [beginBoot] (before any UI/unlock can start a restore), checks whether
 *      `restore_pending` was STILL true — meaning a restore began last launch and
 *      never reached step 2, i.e. it crashed. If so it wipes the corrupt sync
 *      blobs (escalating to the DB on a repeat crash) and clears the flag.
 *
 * IDLE-SAFETY INVARIANT: if [beginRestore] was never called (the user opened the
 * app and never unlocked), `restore_pending` stays false, so
 * [recoverFromCrashedRestoreIfNeeded] is a pure no-op ([RestoreRecovery.None]). A
 * user idling on the lock screen across launches is NEVER wiped.
 */
object BootGuard {
    private const val PREFS = "dgb_boot_guard"
    private const val KEY_PENDING = "pending_boots"
    private const val KEY_RESTORE_PENDING = "restore_pending"
    private const val KEY_RESTORE_CRASH_COUNT = "restore_crash_count"

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

    /** Restore crashes before escalating to a full DB wipe (mirrors [THRESHOLD_DB]'s
     *  role for the boot counter, but scoped to the restore bracket — a repeat
     *  restore crash means the sync-blob wipe alone didn't fix it). */
    const val RESTORE_THRESHOLD_DB = 2

    /** What [recoverFromCrashedRestoreIfNeeded] wiped, if anything. */
    sealed class RestoreRecovery {
        /** No crashed restore was pending — nothing was touched. */
        data object None : RestoreRecovery()
        /** A crashed restore was pending; sync blobs were wiped. */
        data object Sync : RestoreRecovery()
        /** A crashed restore was pending for the >=2nd time; sync blobs + DB were wiped. */
        data object SyncAndDb : RestoreRecovery()
    }

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

    /**
     * Call at the START of the post-unlock wallet restore (the entry of
     * `WalletManager.restoreFromDisk()`), before `recoverWalletFromBytes` or any
     * other risky native deserialization runs. Committed synchronously so a
     * crash immediately after this call still leaves `restore_pending = true`
     * on disk for [recoverFromCrashedRestoreIfNeeded] to see on the next launch.
     */
    fun beginRestore(context: Context) {
        prefs(context).edit().putBoolean(KEY_RESTORE_PENDING, true).commit()
    }

    /**
     * Call AFTER the restore's risky window has genuinely completed — i.e. after
     * `recoverWalletFromBytes` succeeds AND the first `loadSavedBlocks` call in
     * `SyncService.startSyncWithTor` returns (that's where a corrupt
     * `saved_blocks` blob actually crashes the native block deserializer).
     * Clears `restore_pending` and resets `restore_crash_count` so a healthy
     * restore never accumulates toward the escalated DB wipe.
     */
    fun markRestoreHealthy(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_RESTORE_PENDING, false) || p.getInt(KEY_RESTORE_CRASH_COUNT, 0) != 0) {
            android.util.Log.i("BootGuard", "Restore healthy — clearing restore-crash bracket")
            p.edit()
                .putBoolean(KEY_RESTORE_PENDING, false)
                .putInt(KEY_RESTORE_CRASH_COUNT, 0)
                .apply()
        }
    }

    /**
     * Call in `Application.onCreate` immediately after [beginBoot], before the
     * UI/unlock flow can start a restore. If `restore_pending` is STILL true, a
     * restore started last launch and never reached [markRestoreHealthy] — it
     * crashed. Wipes the corrupt sync blobs (escalating to the DB on a repeat
     * restore crash, [RESTORE_THRESHOLD_DB]), clears the flag, and reports what
     * was healed.
     *
     * IDLE-SAFETY: if `restore_pending` is false — [beginRestore] was never
     * called because the user never unlocked — this is a pure no-op that
     * returns [RestoreRecovery.None]. See the class doc's idle-safety invariant.
     */
    fun recoverFromCrashedRestoreIfNeeded(context: Context): RestoreRecovery {
        val p = prefs(context)
        if (!p.getBoolean(KEY_RESTORE_PENDING, false)) {
            return RestoreRecovery.None
        }

        val crashCount = p.getInt(KEY_RESTORE_CRASH_COUNT, 0) + 1
        val recovery: RestoreRecovery
        if (crashCount >= RESTORE_THRESHOLD_DB) {
            android.util.Log.e("BootGuard",
                "RESTORE SAFE MODE: post-unlock restore crashed $crashCount time(s) — " +
                    "wiping sync state + DB (seed preserved)")
            StaleDataWiper.wipeSyncBlobs(context)
            StaleDataWiper.wipeDatabase(context)
            recovery = RestoreRecovery.SyncAndDb
        } else {
            android.util.Log.e("BootGuard",
                "RESTORE SAFE MODE: post-unlock restore crashed — wiping sync state (seed preserved)")
            StaleDataWiper.wipeSyncBlobs(context)
            recovery = RestoreRecovery.Sync
        }

        // Clear the pending flag but persist the escalated crash count — a
        // subsequent healthy restore (markRestoreHealthy) resets it to 0.
        p.edit()
            .putBoolean(KEY_RESTORE_PENDING, false)
            .putInt(KEY_RESTORE_CRASH_COUNT, crashCount)
            .commit()

        return recovery
    }
}
