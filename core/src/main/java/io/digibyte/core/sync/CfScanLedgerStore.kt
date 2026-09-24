package io.digibyte.core.sync

import android.content.Context
import io.digibyte.core.WALLET_NETWORK_SUFFIXES
import io.digibyte.core.networkSuffix
import java.io.File

/**
 * File-backed persistence for the CF scan ledger (native-serialized, opaque bytes).
 *
 * Mirrors [FilterHeaderStore] structurally (see
 * docs/superpowers/specs/2026-07-25-cf-scan-ledger-design.md §5): the ledger is
 * stored as RAW bytes in a plain file — NOT hex-encoded in SharedPreferences — for
 * the same reasons FilterHeaderStore moved off `dgb_sync_data`: a hex-encoded String
 * pinned in Android's process-lifetime `SharedPreferencesImpl` in-memory map would
 * leak and re-grow on every persisted snapshot.
 *
 * Persistence is only a restore optimization — the native ledger is authoritative in
 * memory — so best-effort I/O is acceptable. Unlike FilterHeaderStore, there is no
 * legacy SharedPreferences blob to migrate; this store is new.
 */
object CfScanLedgerStore {
    private const val FILE_BASE = "saved_cf_ledger"

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin")

    private fun tmpFile(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin.tmp")

    private val ioLock = Any()
    @Volatile private var epoch = 0L

    /** Snapshot the current generation; pass it to [write] so a concurrent [delete]
     *  (watchdog re-anchor / rescan reset) invalidates a stale in-flight write. */
    fun currentEpoch(): Long = epoch

    /**
     * Persist the ledger as raw bytes, atomically (tmp write + rename) — UNLESS a
     * [delete] has run since `snapshotEpoch` was taken, in which case the write is
     * dropped so a stale pre-reset ledger can't be resurrected after the file was
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
     * Load the ledger for restore: returns the file bytes, or null if none is
     * persisted yet / the read failed.
     */
    fun load(ctx: Context): ByteArray? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    /** Delete the persisted ledger (file and tmp). Used on re-anchor / reset. Bumps
     *  the epoch so any in-flight [write] captured before now is dropped, and is
     *  serialized with [write] on [ioLock] so it can't be resurrected. */
    fun delete(ctx: Context) {
        synchronized(ioLock) {
            epoch++
            runCatching { file(ctx).delete() }
            runCatching { tmpFile(ctx).delete() }
        }
    }

    /**
     * [delete] for EVERY network's ledger, not only the selected one. For a full wallet reset
     * only: a ledger records which heights were already evaluated for ONE wallet, so none may
     * be left for the next wallet on either network. Returns true only when, read back, no file
     * is left.
     */
    fun deleteAllNetworks(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        WALLET_NETWORK_SUFFIXES.map { net ->
            val bin = File(ctx.filesDir, "$FILE_BASE$net.bin")
            val tmp = File(ctx.filesDir, "$FILE_BASE$net.bin.tmp")
            runCatching { bin.delete() }
            runCatching { tmp.delete() }
            !bin.exists() && !tmp.exists()
        }.all { it }
    }
}
