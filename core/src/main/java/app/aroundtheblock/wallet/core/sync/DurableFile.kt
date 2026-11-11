package app.aroundtheblock.wallet.core.sync

import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * STORE-DURABILITY: the one copy of how a persisted sync store writes its file.
 *
 * Invariant: every persisted sync store writes durably and never destroys its last good copy.
 *  - A write is: write tmp -> fsync(tmp fd) -> rename tmp over live -> fsync(directory). Without the
 *    first fsync the rename can reach the disk before the data, and a power loss leaves an empty or
 *    partial live file; without the second the rename itself can be lost (on ext4/f2fs it is a
 *    change to the directory).
 *  - A failed rename never deletes the live file: [write] returns false and the caller keeps its gate
 *    shut. (The stores once fell back to delete-then-rename, so a second failed rename lost both.)
 *  - At load, a missing live file next to a complete tmp is recovered ([recoverTmp]) when the format
 *    lets completeness be checked; otherwise the tmp is ignored.
 *
 * Used by [FilterHeaderStore], [SavedBlockStore], [CfScanLedgerStore] and [SavedTransactionsStore].
 * Callers hold their own store lock around these calls.
 */
object DurableFile {
    /** Test seam: flush the tmp file's data to the device before it is renamed. */
    @Volatile internal var fsync: (FileOutputStream) -> Unit = { it.fd.sync() }

    /** Test seam: the rename that publishes the tmp file. */
    @Volatile internal var rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    /** Test seam: remove one file (the delete order is what a kill between two removals leaves). */
    @Volatile internal var remove: (File) -> Boolean = { it.delete() }

    /** Test seam: make a rename into [dir] durable. */
    @Volatile internal var syncDir: (File?) -> Unit = { syncDirectory(it) }

    /**
     * Write [bytes] to [live] through [tmp]. True only when [live] now holds exactly these bytes. On
     * any failure [live] is left as it was and [tmp] is removed.
     */
    fun write(live: File, tmp: File, bytes: ByteArray): Boolean = runCatching {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            fsync(out)
        }
        if (!rename(tmp, live)) error("rename failed") // never delete-then-rename: the live copy stays
        syncDir(live.parentFile)
        live.length() == bytes.size.toLong()
    }.getOrElse {
        runCatching { tmp.delete() }
        false
    }

    /**
     * A write that synced its tmp file but did not get to its rename: when [live] is missing and
     * [tmp] holds a blob [isComplete] accepts, publish it and return its bytes (still returned if the
     * rename fails). Null when [live] exists, or there is no tmp, or it is partial (left where it is,
     * ignored; the next write replaces it).
     */
    fun recoverTmp(live: File, tmp: File, isComplete: (ByteArray) -> Boolean): ByteArray? {
        if (runCatching { live.exists() }.getOrDefault(true)) return null
        val pending = runCatching { if (tmp.isFile) tmp.readBytes() else null }.getOrNull() ?: return null
        if (!runCatching { isComplete(pending) }.getOrDefault(false)) return null
        if (rename(tmp, live)) syncDir(live.parentFile)
        return pending
    }

    /**
     * Remove a store's files, tmp FIRST: a process killed between the two removals must not leave a
     * tmp beside a missing live file, which the next load would recover as the deleted data.
     */
    fun delete(live: File, tmp: File) {
        runCatching { remove(tmp) }
        runCatching { remove(live) }
    }

    /** fsync a directory. Best-effort: a platform that cannot open a directory for sync is left as before. */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    /**
     * The one rule for a store's legacy SharedPreferences hex copy (the pre-file format, pinned in
     * SharedPreferencesImpl's process-lifetime map, so it must not outlive its migration):
     *  - a readable key is the NEWER copy, even beside a file: only an older build writes it, after a
     *    downgrade. [load] rewrites the file from it and removes it once that write landed; a write
     *    that failed keeps it, as the last good copy;
     *  - an unusable key (empty, over [maxHex], not hex) is dropped, unless the store says otherwise;
     *  - every write of the store that lands removes a key still present ([afterLanded]), so a key a
     *    failed migration kept cannot stay pinned once the file is written some other way.
     */
    class LegacyKey(private val prefs: () -> SharedPreferences, private val key: String, private val maxHex: Int?) {
        sealed class Read {
            object Absent : Read()
            object Unusable : Read()
            class Usable(val bytes: ByteArray) : Read()
        }

        fun read(): Read {
            val hex = runCatching { prefs().getString(key, null) }.getOrNull() ?: return Read.Absent
            if (hex.isEmpty() || (maxHex != null && hex.length > maxHex)) return Read.Unusable
            return FilterHeaderStore.decodeHexOrNull(hex)?.let { Read.Usable(it) } ?: Read.Unusable
        }

        fun remove() {
            runCatching { prefs().edit().remove(key).commit() } // commit: a key outliving a newer file would win the next load
        }

        /** After a write of the store landed: the file is the newest copy now. */
        fun afterLanded() {
            if (runCatching { prefs().contains(key) }.getOrDefault(false)) remove()
        }
    }
}

/** What a store write did. Only [FAILED] means the latest state is not on disk and must be retried. */
enum class StoreWrite {
    /** The file now holds exactly these bytes. */
    LANDED,
    /** Not written: the write or its rename failed. The previous file is kept as it was. */
    FAILED,
    /** Not written, on purpose: a delete (reset, wipe) ran since the snapshot, so these bytes are stale. */
    OBSOLETE,
    /** Nothing was pending. */
    NONE;

    val failed: Boolean get() = this == FAILED

}
