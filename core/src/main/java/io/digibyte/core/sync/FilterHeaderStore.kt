package io.digibyte.core.sync

import android.content.Context
import io.digibyte.core.networkSuffix
import java.io.File

/**
 * File-backed persistence for the BIP158 compact-filter-header chain.
 *
 * The chain is stored as RAW bytes in a plain file — NOT hex-encoded in
 * SharedPreferences.
 *
 * Rationale (2026-07-14 heap-leak fix): on a long-history wallet the filter-header
 * chain grows to tens/hundreds of MB. The old code hex-encoded it (2x the bytes)
 * and `putString()`-ed it into `dgb_sync_data` on EVERY cfheaders batch. Android's
 * `SharedPreferencesImpl` keeps every value in a process-lifetime in-memory map, so
 * that hex String was pinned and re-grown on each batch — a JVM heap leak that drove
 * old wallets to the 512MB largeHeap ceiling (GC frees nothing) → OOM-restart loop,
 * so the wallet never finished syncing. A file has no in-memory pin and no hex
 * doubling; the caller coalesces/throttles writes to at most one per interval.
 *
 * Persistence is only a restore optimization — the native chain is authoritative in
 * memory — so best-effort I/O and a dropped oversized legacy blob (re-synced) are
 * acceptable.
 */
object FilterHeaderStore {
    private const val LEGACY_PREFS = "dgb_sync_data"
    private const val LEGACY_KEY = "saved_filter_headers"
    private const val FILE_BASE = "saved_filter_headers"

    /**
     * Cap for the one-time legacy-hex → file migration. A larger legacy blob is
     * dropped (the native chain re-syncs), which also avoids OOM-ing while decoding
     * a huge hex String at startup. ~16 MB hex ≈ 8 MB chain ≈ 250k headers.
     */
    internal const val MAX_LEGACY_MIGRATE_HEX = 16 * 1024 * 1024

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin")

    private fun tmpFile(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin.tmp")

    private fun legacyPrefs(ctx: Context) =
        ctx.getSharedPreferences(LEGACY_PREFS + networkSuffix(ctx), Context.MODE_PRIVATE)

    private val ioLock = Any()
    @Volatile private var epoch = 0L

    /** Snapshot the current generation; pass it to [write] so a concurrent [delete]
     *  (watchdog re-anchor / rescan reset) invalidates a stale in-flight write. */
    fun currentEpoch(): Long = epoch

    /**
     * Persist the chain as raw bytes, atomically (tmp write + rename) — UNLESS a
     * [delete] has run since `snapshotEpoch` was taken, in which case the write is
     * dropped so a stale pre-reset chain can't be resurrected after the file was
     * already deleted. Serialized with [delete] on [ioLock]. Best-effort.
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
     * Load the chain for restore: prefer the file; otherwise perform a one-time
     * migration from the legacy prefs hex (capped) and ALWAYS drop the leaky legacy
     * key to free its pinned in-memory floor. Returns null if none / dropped.
     */
    fun load(ctx: Context): ByteArray? {
        val f = file(ctx)
        if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
        val legacyHex = runCatching { legacyPrefs(ctx).getString(LEGACY_KEY, null) }.getOrNull()
        // Always drop the leaky legacy key (frees the pinned in-memory hex floor).
        runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        if (legacyHex.isNullOrEmpty() || legacyHex.length > MAX_LEGACY_MIGRATE_HEX) return null
        val bytes = decodeHexOrNull(legacyHex) ?: return null
        synchronized(ioLock) { writeLocked(ctx, bytes) } // migrate to the file store (no concurrent reset at load)
        return bytes
    }

    /** Delete the persisted chain (file, tmp, and legacy prefs key). Used on re-anchor /
     *  reset. Bumps the epoch so any in-flight [write] captured before now is dropped,
     *  and is serialized with [write] on [ioLock] so it can't be resurrected. */
    fun delete(ctx: Context) {
        synchronized(ioLock) {
            epoch++
            runCatching { file(ctx).delete() }
            runCatching { tmpFile(ctx).delete() }
            runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        }
    }

    /**
     * Decode a hex string to bytes without the `chunked(2).map { … }` amplifier
     * (which allocates millions of 2-char Strings + boxed Bytes — a multi-GB
     * transient spike / startup OOM on a large chain). Returns null on odd length
     * or a non-hex character.
     */
    fun decodeHexOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }
}
