package app.aroundtheblock.wallet.core.sync

import android.content.Context
import app.aroundtheblock.wallet.core.WALLET_NETWORK_SUFFIXES
import app.aroundtheblock.wallet.core.networkSuffix
import java.io.File

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
 *  - a write that lands also removes a legacy key still present, and records the count the file
 *    declares under [KEY_COUNT] in the same commit; the sync service's `transactions_checkpointed`
 *    commit carries it too. A restore that finds that count above 0 and no file is short (the file
 *    was lost), never a near-tip resume with no history;
 *  - a rename that fails leaves the live file as it was and reports failure; a live file that is
 *    missing beside a COMPLETE tmp file (its records end exactly at its length) is recovered from it;
 *  - a legacy key found beside the file was written by an older build after a downgrade, so it is the
 *    newer copy: it replaces the file, then goes.
 */
object SavedTransactionsStore {
    private const val LEGACY_PREFS = "dgb_sync_data"
    internal const val LEGACY_KEY = "saved_transactions"
    private const val FILE_BASE = "saved_transactions"

    /** In the network's `dgb_sync_data`: the record count of the last checkpoint that landed. */
    const val KEY_COUNT = "saved_transactions_count"

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
        // STORE-DURABILITY: tmp, fsync, rename, directory fsync; a failed rename keeps the live checkpoint.
        val landed = DurableFile.write(file(ctx), tmpFile(ctx), bytes)
        // The file is the checkpoint now: a legacy copy left behind is older, and would be pinned again.
        if (landed) runCatching {
            legacyPrefs(ctx).edit()
                .remove(LEGACY_KEY)
                .putLong(KEY_COUNT, SavedTransactionsCheckpoint.declaredCount(bytes)?.coerceAtLeast(0L) ?: 0L)
                .commit()
        }
        return landed
    }

    /** Whether [b] is a whole checkpoint: a non-zero count whose records end exactly at its length. */
    internal fun isComplete(b: ByteArray): Boolean {
        val count = SavedTransactionsCheckpoint.declaredCount(b) ?: return false
        if (count <= 0L) return false
        var pos = 4L
        val len = b.size.toLong()
        var i = 0L
        while (i < count) {
            if (pos + 12L > len) return false
            val size = (b[pos.toInt()].toLong() and 0xffL) or ((b[pos.toInt() + 1].toLong() and 0xffL) shl 8) or
                ((b[pos.toInt() + 2].toLong() and 0xffL) shl 16) or ((b[pos.toInt() + 3].toLong() and 0xffL) shl 24)
            pos += 12L
            if (size > len - pos) return false
            pos += size
            i++
        }
        return pos == len
    }

    /** Whether a checkpoint is stored (the file, or a legacy key not yet migrated). */
    fun exists(ctx: Context): Boolean =
        runCatching { file(ctx).exists() }.getOrDefault(false) ||
            runCatching { legacyPrefs(ctx).contains(LEGACY_KEY) }.getOrDefault(false)

    /**
     * The stored checkpoint, or null when there is none. In order:
     *  - a legacy key (a migration, or an older build's write after a downgrade, which is newer than
     *    the file): written to the file, and removed only once that write landed; if it did not, the
     *    key stays and its bytes are still returned. A legacy key that is not readable hex gives the
     *    file if there is one, else an EMPTY array (present, declares nothing readable: a short
     *    restore), and is left in place;
     *  - the file;
     *  - no file but a complete tmp file (a write that synced but did not get to its rename): renamed
     *    into place and returned. A partial tmp file is ignored.
     */
    fun load(ctx: Context): ByteArray? = synchronized(ioLock) {
        val f = file(ctx)
        val fileThere = runCatching { f.exists() }.getOrDefault(false)
        val legacyHex = runCatching { legacyPrefs(ctx).getString(LEGACY_KEY, null) }.getOrNull()
        if (legacyHex != null) {
            val bytes = FilterHeaderStore.decodeHexOrNull(legacyHex)
            if (bytes != null) {
                writeLocked(ctx, bytes) // removes the key only if the file landed
                return bytes
            }
            if (!fileThere) return ByteArray(0)
        }
        if (fileThere) return runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
        DurableFile.recoverTmp(f, tmpFile(ctx), ::isComplete)
    }

    /** Delete this network's checkpoint: file, tmp and legacy key. Bumps the epoch so an in-flight
     *  [write] snapshotted before now is dropped. Returns whether, read back, none is left. */
    fun delete(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        runCatching { DurableFile.delete(file(ctx), tmpFile(ctx)) }
        val keyGone = runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).remove(KEY_COUNT).commit() }.getOrDefault(false)
        keyGone && !runCatching { file(ctx).exists() || tmpFile(ctx).exists() }.getOrDefault(true)
    }

    /** [delete] for EVERY network, for a full wallet reset. Returns true only when, read back, no
     *  file is left and every legacy key's removal was written. */
    fun deleteAllNetworks(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        WALLET_NETWORK_SUFFIXES.map { net ->
            val bin = File(ctx.filesDir, "$FILE_BASE$net.bin")
            val tmp = File(ctx.filesDir, "$FILE_BASE$net.bin.tmp")
            DurableFile.delete(bin, tmp)
            val legacyGone = runCatching {
                ctx.getSharedPreferences(LEGACY_PREFS + net, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).remove(KEY_COUNT).commit()
            }.getOrDefault(false)
            !bin.exists() && !tmp.exists() && legacyGone
        }.all { it }
    }
}
