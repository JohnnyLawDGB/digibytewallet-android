package app.aroundtheblock.wallet.core.sync

import android.content.Context
import app.aroundtheblock.wallet.core.WALLET_NETWORK_SUFFIXES
import app.aroundtheblock.wallet.core.networkSuffix
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed persistence for the wallet's transaction checkpoint: the raw bytes
 * BRWalletSerializeTransactions writes, in `filesDir/saved_transactions<net>.bin`.
 *
 * TX-CKPT-CAP: until 2026-11 the checkpoint was hex in `dgb_sync_data`/`saved_transactions`. With the
 * shared record limit at 100,000 that string reaches ~50 MB, pinned for the process lifetime in
 * SharedPreferencesImpl's in-memory map, plus ~100 MB of UTF-16 while it is decoded, in the BootGuard
 * restore window, and the whole `dgb_sync_data` XML was rewritten with it on every commit: the
 * pattern [FilterHeaderStore] (v3.10.29) and [SavedBlockStore] (I2) already removed for their blobs.
 * Here the bytes go to native as they are.
 *
 * Unlike those two stores, this one is not a pure restore optimization: the scan frontier assumes the
 * history it restores. So:
 *  - [write] is atomic (tmp file, fsync, rename) and says whether it landed: the caller's checkpoint
 *    gate (the scan ledger and `has_synced` are only written after a checkpoint lands) needs to know;
 *  - the legacy key is migrated once, and removed only after the file holding it has landed: a failed
 *    write keeps the key, so the checkpoint is never lost on the way;
 *  - a write that lands also removes a legacy key still present: the file is newer.
 */
object SavedTransactionsStore {
    private const val LEGACY_PREFS = "dgb_sync_data"
    internal const val LEGACY_KEY = "saved_transactions"
    private const val FILE_BASE = "saved_transactions"

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin")

    private fun tmpFile(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin.tmp")

    private fun legacyPrefs(ctx: Context) =
        ctx.getSharedPreferences(LEGACY_PREFS + networkSuffix(ctx), Context.MODE_PRIVATE)

    private val ioLock = Any()
    @Volatile private var epoch = 0L

    /** Snapshot the current generation BEFORE reading the native set; pass it to [write] so a [delete]
     *  in between (rebuild, wipe) drops the stale write instead of resurrecting the cleared history. */
    fun currentEpoch(): Long = epoch

    /**
     * Persist [bytes] atomically, unless a [delete] has run since [snapshotEpoch]. Returns true only
     * when the file now holds exactly these bytes.
     */
    fun write(ctx: Context, bytes: ByteArray, snapshotEpoch: Long): Boolean = synchronized(ioLock) {
        if (epoch != snapshotEpoch) return false
        writeLocked(ctx, bytes)
    }

    private fun writeLocked(ctx: Context, bytes: ByteArray): Boolean {
        val f = file(ctx)
        val tmp = tmpFile(ctx)
        val landed = runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) error("rename failed")
            }
            f.length() == bytes.size.toLong()
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
        // The file is the checkpoint now: a legacy copy left behind is older, and would be pinned again.
        if (landed) runCatching {
            val p = legacyPrefs(ctx)
            if (p.contains(LEGACY_KEY)) p.edit().remove(LEGACY_KEY).commit()
        }
        return landed
    }

    /** Whether a checkpoint is stored (the file, or a legacy key not yet migrated). */
    fun exists(ctx: Context): Boolean =
        runCatching { file(ctx).exists() }.getOrDefault(false) ||
            runCatching { legacyPrefs(ctx).contains(LEGACY_KEY) }.getOrDefault(false)

    /**
     * The stored checkpoint, or null when there is none. A legacy key whose hex is not readable gives
     * an EMPTY array (a checkpoint is present and declares nothing readable: [SavedTransactionsCheckpoint]
     * treats that as a short restore) and is left in place. A readable legacy key is migrated: written
     * to the file, and removed only once that write landed; if it did not, the key stays and the bytes
     * are still returned, so the restore uses them and the next launch migrates again.
     */
    fun load(ctx: Context): ByteArray? = synchronized(ioLock) {
        val f = file(ctx)
        if (runCatching { f.exists() }.getOrDefault(false)) {
            return runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
        }
        val legacyHex = runCatching { legacyPrefs(ctx).getString(LEGACY_KEY, null) }.getOrNull() ?: return null
        val bytes = FilterHeaderStore.decodeHexOrNull(legacyHex) ?: return ByteArray(0)
        writeLocked(ctx, bytes) // removes the key only if the file landed
        bytes
    }

    /** Delete this network's checkpoint: file, tmp and legacy key. Bumps the epoch so an in-flight
     *  [write] snapshotted before now is dropped. Returns whether, read back, none is left. */
    fun delete(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        runCatching { file(ctx).delete() }
        runCatching { tmpFile(ctx).delete() }
        val keyGone = runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).commit() }.getOrDefault(false)
        keyGone && !runCatching { file(ctx).exists() || tmpFile(ctx).exists() }.getOrDefault(true)
    }

    /** [delete] for EVERY network, for a full wallet reset. Returns true only when, read back, no
     *  file is left and every legacy key's removal was written. */
    fun deleteAllNetworks(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        WALLET_NETWORK_SUFFIXES.map { net ->
            val bin = File(ctx.filesDir, "$FILE_BASE$net.bin")
            val tmp = File(ctx.filesDir, "$FILE_BASE$net.bin.tmp")
            runCatching { bin.delete() }
            runCatching { tmp.delete() }
            val legacyGone = runCatching {
                ctx.getSharedPreferences(LEGACY_PREFS + net, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).commit()
            }.getOrDefault(false)
            !bin.exists() && !tmp.exists() && legacyGone
        }.all { it }
    }
}
