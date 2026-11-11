package app.aroundtheblock.wallet.core.sync

import android.content.Context
import app.aroundtheblock.wallet.core.WALLET_NETWORK_SUFFIXES
import app.aroundtheblock.wallet.core.networkSuffix
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
 *
 * STORE-DURABILITY: every write goes through [DurableFile] (tmp, fsync, rename, directory
 * fsync), reports whether it landed, and never removes the previous file when it fails.
 * Since the recreate path (`flushLiveStateBeforeRecreate`, [RecreateSequence]) restores the
 * last PERSISTED snapshot, a lost or torn file costs a resume far behind the tip.
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
     * Persist the block window as raw bytes, durably ([DurableFile]: tmp, fsync, rename, directory fsync) —
     * UNLESS a [delete] has run since `snapshotEpoch` was taken, in which case the
     * write is dropped so a stale pre-reset window can't be resurrected after the
     * file was already deleted. Serialized with [delete] on [ioLock]. Returns true only when
     * the file now holds [bytes]; on false the previous file is kept as it was (STORE-DURABILITY).
     */
    fun write(ctx: Context, bytes: ByteArray, snapshotEpoch: Long): Boolean = synchronized(ioLock) {
        if (epoch != snapshotEpoch) return false // a delete() invalidated this snapshot
        writeLocked(ctx, bytes)
    }

    /** STORE-DURABILITY ([DurableFile]): true only when the file now holds [bytes]; a failed write
     *  leaves the previous file as it was. */
    private fun writeLocked(ctx: Context, bytes: ByteArray): Boolean = DurableFile.write(file(ctx), tmpFile(ctx), bytes)

    /**
     * Load the saved-blocks window for restore: prefer the file; then a complete tmp
     * file (a write that synced but was killed before its rename, recovered into
     * place); otherwise a one-time migration from the legacy `dgb_sync_data`/`saved_blocks`
     * hex (capped, and decoded with [FilterHeaderStore.decodeHexOrNull] rather than the
     * `chunked(2).map{}` amplifier). The legacy key is dropped when it is oversized or
     * corrupt, or once the file holding it landed, so it cannot re-pin the leaky
     * in-memory floor; a migration write that fails keeps it, as the last good copy
     * (STORE-DURABILITY), and the next load tries again. Every
     * OTHER key in `dgb_sync_data` (saved_peers, has_synced, last_balance,
     * saved_transactions, …) is left untouched. Returns null if none / dropped.
     */
    fun load(ctx: Context): ByteArray? = synchronized(ioLock) {
        val f = file(ctx)
        if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
        // STORE-DURABILITY: a write that synced its tmp but was killed before the rename.
        DurableFile.recoverTmp(f, tmpFile(ctx), ::isComplete)?.let { recovered ->
            runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() } // older than the file
            return recovered
        }
        val legacyHex = runCatching { legacyPrefs(ctx).getString(LEGACY_KEY, null) }.getOrNull()
        val bytes = legacyHex?.takeIf { it.isNotEmpty() && it.length <= MAX_LEGACY_MIGRATE_HEX }?.let { FilterHeaderStore.decodeHexOrNull(it) }
        // Drop the leaky legacy key (frees the pinned in-memory hex floor) when it is unusable or once
        // the file holding it landed. A migration write that failed keeps it: it is the last good copy.
        if (bytes == null || writeLocked(ctx, bytes)) {
            runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        }
        bytes
    }

    /** Delete the persisted block window (file, tmp, and legacy prefs key). Used on
     *  rescan / wipe / CF restore-preflight reset. Bumps the epoch so any in-flight
     *  [write] captured before now is dropped, and is serialized with [write] on
     *  [ioLock] so it can't be resurrected. */
    fun delete(ctx: Context) {
        synchronized(ioLock) {
            epoch++
            runCatching { DurableFile.delete(file(ctx), tmpFile(ctx)) }
            runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        }
    }

    /**
     * [delete] for EVERY network's block window, not only the selected one — a full wallet reset
     * leaves no network's copy for the next wallet to load. The record format is untouched: the
     * files are removed, never rewritten. Returns true only when, read back, no file is left and
     * the legacy key's removal was written.
     */
    fun deleteAllNetworks(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        WALLET_NETWORK_SUFFIXES.map { net ->
            val bin = File(ctx.filesDir, "$FILE_BASE$net.bin")
            val tmp = File(ctx.filesDir, "$FILE_BASE$net.bin.tmp")
            DurableFile.delete(bin, tmp)
            val legacyGone = runCatching {
                ctx.getSharedPreferences(LEGACY_PREFS + net, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).commit()
            }.getOrDefault(false)
            !bin.exists() && !tmp.exists() && legacyGone
        }.all { it }
    }

    /**
     * Whether [b] is a whole window as bridge_saveBlocks hands it over: a non-zero u32 count, then
     * `count` records of [u32 serLen][u32 height][serLen bytes] that end exactly at its length. Only
     * decides whether a tmp file may be recovered.
     */
    internal fun isComplete(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val count = b.u32(0)
        if (count <= 0L) return false
        val len = b.size.toLong()
        var pos = 4L
        var i = 0L
        while (i < count) {
            if (pos + 8L > len) return false
            val size = b.u32(pos)
            pos += 8L
            if (size > len - pos) return false
            pos += size
            i++
        }
        return pos == len
    }

    private fun ByteArray.u32(at: Long): Long = (this[at.toInt()].toLong() and 0xffL) or
        ((this[at.toInt() + 1].toLong() and 0xffL) shl 8) or
        ((this[at.toInt() + 2].toLong() and 0xffL) shl 16) or
        ((this[at.toInt() + 3].toLong() and 0xffL) shl 24)
}
