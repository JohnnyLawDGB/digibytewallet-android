package io.digibyte.core.sync

import android.content.Context
import io.digibyte.core.networkSuffix
import java.io.File

/**
 * File-backed persistence for the native saved-blocks window (`saved_blocks`).
 *
 * The chain is stored as RAW bytes in a plain file — NOT hex-encoded in
 * SharedPreferences. Structurally this mirrors [FilterHeaderStore] exactly (same
 * epoch/write/load/delete shape, same legacy-hex migration).
 *
 * Rationale (finding I2, 2026-08-09): `SAVE_BLOCK_COUNT` was raised 300 -> 32768
 * for the paced-convoy restore guarantee (`BRPeerManager.h`'s
 * `CF_STATIC_ASSERT(SAVE_BLOCK_COUNT >= CF_CONVOY_WINDOW + MAX_HEADERS_RESULTS)`).
 * Downstream, `saved_blocks` was still hex-encoded (~5.9M chars for a ~2.9MB
 * window) and `putString()`-ed into `dgb_sync_data` on every save (every ~4000
 * blocks during descent, every ~20s at tip). Android's `SharedPreferencesImpl`
 * keeps every value in a process-lifetime in-memory map, so that hex String was
 * pinned and re-grown on each save, plus the whole `dgb_sync_data` XML (which
 * also carries `saved_transactions`) was rewritten on every commit — the exact
 * 512MB-class heap-leak pattern the v3.10.29 [FilterHeaderStore] fix already
 * solved for the filter-header chain (see that file's doc comment). This is the
 * same fix applied to the block window.
 *
 * Persistence is only a restore optimization — the native chain is authoritative
 * in memory — so best-effort I/O and a dropped oversized/corrupt legacy blob
 * (re-synced) are acceptable.
 */
object SavedBlockStore {
    private const val LEGACY_PREFS = "dgb_sync_data"
    private const val LEGACY_KEY = "saved_blocks"
    private const val FILE_BASE = "saved_blocks"

    /**
     * Cap for the one-time legacy-hex → file migration, mirroring
     * [FilterHeaderStore.MAX_LEGACY_MIGRATE_HEX]. A larger legacy blob is dropped
     * (the native chain re-syncs), which also avoids OOM-ing while decoding a huge
     * hex String at startup. ~16 MB hex ≈ 8 MB of blocks.
     */
    internal const val MAX_LEGACY_MIGRATE_HEX = 16 * 1024 * 1024

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin")

    private fun tmpFile(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin.tmp")

    private fun legacyPrefs(ctx: Context) =
        ctx.getSharedPreferences(LEGACY_PREFS + networkSuffix(ctx), Context.MODE_PRIVATE)

    private val ioLock = Any()
    @Volatile private var epoch = 0L

    /** Snapshot the current generation; pass it to [write] so a concurrent [delete]
     *  (rescan / wipe / CF restore-preflight reset) invalidates a stale in-flight
     *  write. */
    fun currentEpoch(): Long = epoch

    /**
     * Persist the block window as raw bytes, atomically (tmp write + rename) —
     * UNLESS a [delete] has run since `snapshotEpoch` was taken, in which case the
     * write is dropped so a stale pre-reset window can't be resurrected after the
     * file was already deleted. Serialized with [delete] on [ioLock]. Best-effort.
     */
    fun write(ctx: Context, bytes: ByteArray, snapshotEpoch: Long) {
        synchronized(ioLock) {
            if (epoch != snapshotEpoch) return // a delete() invalidated this snapshot
            writeLocked(ctx, bytes)
        }
    }

    private fun writeLocked(ctx: Context, bytes: ByteArray) {
        val f = file(ctx)
        val tmp = tmpFile(ctx)
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) tmp.delete() }
        }.onFailure { runCatching { tmp.delete() } }
    }

    /**
     * Load the saved-blocks window for restore: prefer the file; otherwise perform
     * a one-time migration from the legacy `dgb_sync_data`/`saved_blocks` hex
     * (capped, and decoded with [FilterHeaderStore.decodeHexOrNull] rather than the
     * `chunked(2).map{}` amplifier), and ALWAYS drop the legacy key — whether the
     * migration succeeded, was skipped as oversized, or the hex was corrupt — so it
     * can never re-pin the leaky in-memory floor or wedge a future load. Every
     * OTHER key in `dgb_sync_data` (saved_peers, has_synced, last_balance,
     * saved_transactions, …) is left untouched. Returns null if none / dropped.
     */
    fun load(ctx: Context): ByteArray? {
        val f = file(ctx)
        if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
        val legacyHex = runCatching { legacyPrefs(ctx).getString(LEGACY_KEY, null) }.getOrNull()
        // Always drop the leaky legacy key (frees the pinned in-memory hex floor),
        // leaving every sibling dgb_sync_data field untouched.
        runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        if (legacyHex.isNullOrEmpty() || legacyHex.length > MAX_LEGACY_MIGRATE_HEX) return null
        val bytes = FilterHeaderStore.decodeHexOrNull(legacyHex) ?: return null
        synchronized(ioLock) { writeLocked(ctx, bytes) } // migrate to the file store (no concurrent reset at load)
        return bytes
    }

    /** Delete the persisted block window (file, tmp, and legacy prefs key). Used on
     *  rescan / wipe / CF restore-preflight reset. Bumps the epoch so any in-flight
     *  [write] captured before now is dropped, and is serialized with [write] on
     *  [ioLock] so it can't be resurrected. */
    fun delete(ctx: Context) {
        synchronized(ioLock) {
            epoch++
            runCatching { file(ctx).delete() }
            runCatching { tmpFile(ctx).delete() }
            runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        }
    }
}
