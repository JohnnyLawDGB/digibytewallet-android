package app.aroundtheblock.wallet.core.sync

import android.content.Context
import app.aroundtheblock.wallet.core.WALLET_NETWORK_SUFFIXES
import app.aroundtheblock.wallet.core.networkSuffix
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
 *
 * STORE-DURABILITY: every write goes through [DurableFile] (tmp, fsync, rename, directory
 * fsync), reports whether it landed, and never removes the previous file when it fails.
 * Since the recreate path (`flushLiveStateBeforeRecreate`, [RecreateSequence]) restores the
 * last PERSISTED snapshot, a lost or torn file costs a resume far behind the tip.
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
     * Persist the ledger as raw bytes, durably ([DurableFile]: tmp, fsync, rename, directory fsync) — UNLESS a
     * [delete] has run since `snapshotEpoch` was taken, in which case the write is
     * dropped so a stale pre-reset ledger can't be resurrected after the file was
     * already deleted. Serialized with [delete] on [ioLock]. Returns [StoreWrite.LANDED]
     * when the file now holds [bytes], [StoreWrite.OBSOLETE] for a dropped stale snapshot (not a
     * failure), and [StoreWrite.FAILED] with the previous file kept as it was (STORE-DURABILITY).
     */
    fun write(ctx: Context, bytes: ByteArray, snapshotEpoch: Long): StoreWrite = synchronized(ioLock) {
        if (epoch != snapshotEpoch) return StoreWrite.OBSOLETE // a delete() invalidated this snapshot
        if (DurableFile.write(file(ctx), tmpFile(ctx), bytes)) StoreWrite.LANDED else StoreWrite.FAILED
    }

    /**
     * Load the ledger for restore: the file's bytes; with no file, a complete tmp file (a write that
     * synced but was killed before its rename) is recovered. Null if none is persisted / the read failed.
     */
    fun load(ctx: Context): ByteArray? = synchronized(ioLock) {
        val f = file(ctx)
        if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
        DurableFile.recoverTmp(f, tmpFile(ctx), ::isComplete)
    }

    /** Delete the persisted ledger (file and tmp). Used on re-anchor / reset. Bumps
     *  the epoch so any in-flight [write] captured before now is dropped, and is
     *  serialized with [write] on [ioLock] so it can't be resurrected. */
    fun delete(ctx: Context) {
        synchronized(ioLock) {
            epoch++
            runCatching { DurableFile.delete(file(ctx), tmpFile(ctx)) }
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
            DurableFile.delete(bin, tmp)
            !bin.exists() && !tmp.exists()
        }.all { it }
    }

    /**
     * Whether [b] is a whole ledger as BRCFScanLedgerSerialize writes it (the versions
     * BRCFScanLedgerParse reads, 1 to 3): magic "CFL1", version, a 24-byte (v1) or 28-byte header
     * ending in the outstanding count, its entries (5 bytes, 6 from v3), the parked count and its
     * entries (4 bytes, 6 from v3), ending exactly at its length. Only decides whether a tmp file may
     * be recovered; the native parser still validates what it is handed.
     */
    internal fun isComplete(b: ByteArray): Boolean {
        val len = b.size.toLong()
        if (len < 24L || b.u32(0) != LEDGER_MAGIC) return false
        val version = b.u32(4)
        if (version !in 1L..LEDGER_VERSION_MAX) return false
        val header = if (version >= 2L) 28L else 24L
        if (len < header) return false
        val outEntry = if (version >= 3L) 6L else 5L
        val parkedEntry = if (version >= 3L) 6L else 4L
        val parkedAt = header + b.u32(header - 4L) * outEntry
        if (parkedAt + 4L > len) return false
        return parkedAt + 4L + b.u32(parkedAt) * parkedEntry == len
    }

    internal const val LEDGER_MAGIC = 0x43464C31L // "CFL1", BRCFScanLedger.c

    /** The newest ledger version BRCFScanLedgerParse reads (BR_CF_RESTORE_LEDGER_VERSION_MAX); pinned to the C
     *  header by StoreBlobCompletenessTest, so a version bump fails a test instead of silently ending tmp recovery. */
    internal const val LEDGER_VERSION_MAX = 3L

    private fun ByteArray.u32(at: Long): Long = (this[at.toInt()].toLong() and 0xffL) or
        ((this[at.toInt() + 1].toLong() and 0xffL) shl 8) or
        ((this[at.toInt() + 2].toLong() and 0xffL) shl 16) or
        ((this[at.toInt() + 3].toLong() and 0xffL) shl 24)
}
