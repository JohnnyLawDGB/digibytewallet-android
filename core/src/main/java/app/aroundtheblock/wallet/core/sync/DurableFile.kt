package app.aroundtheblock.wallet.core.sync

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
        runCatching { tmp.delete() }
        runCatching { live.delete() }
    }

    /** fsync a directory. Best-effort: a platform that cannot open a directory for sync is left as before. */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
