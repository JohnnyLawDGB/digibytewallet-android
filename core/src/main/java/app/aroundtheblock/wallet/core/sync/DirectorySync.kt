package app.aroundtheblock.wallet.core.sync

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * fsync a directory, so a rename into it survives a power loss: on ext4/f2fs the rename is a change
 * to the directory, and is not durable until the directory itself is synced. Best-effort: a
 * platform that cannot open a directory for sync (it throws) is left as before.
 */
internal object DirectorySync {
    fun sync(dir: File?) {
        if (dir == null) return
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
