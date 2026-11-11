package app.aroundtheblock.wallet.core.sync

import android.content.Context
import io.mockk.every
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TX-CKPT-CAP: the transaction checkpoint lives in `filesDir/saved_transactions<net>.bin` as raw bytes,
 * not as hex in `dgb_sync_data`. The legacy key is migrated once and removed only after the file
 * landed; a failed write keeps it. Writes are atomic and drop a write snapshotted before a reset; the
 * wipe removes the file for every network.
 */
class SavedTransactionsStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File

    private fun ctx(): Context = fakeContext().also { c ->
        dir = tmp.newFolder()
        every { c.filesDir } returns dir
    }

    private fun sync(c: Context) = c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE)

    private val blob = byteArrayOf(2, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 0x7f, -1)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // (d) migration
    @Test fun `a legacy key is migrated to the file and removed once the file landed`() {
        val c = ctx()
        sync(c).edit().putString("saved_transactions", hex(blob)).putBoolean("has_synced", true).commit()
        assertArrayEquals(blob, SavedTransactionsStore.load(c))
        assertArrayEquals("the file holds the raw bytes", blob, SavedTransactionsStore.file(c).readBytes())
        assertFalse("the legacy key is gone", sync(c).contains("saved_transactions"))
        assertTrue("its siblings are untouched", sync(c).getBoolean("has_synced", false))
        assertArrayEquals("the next load reads the file", blob, SavedTransactionsStore.load(c))
        assertTrue(SavedTransactionsStore.exists(c))
    }

    @Test fun `a failed migration write keeps the legacy key and still restores from it`() {
        val c = ctx()
        sync(c).edit().putString("saved_transactions", hex(blob)).commit()
        // A directory where the tmp file goes: the write cannot open it.
        File(dir, "saved_transactions.bin.tmp").mkdirs()
        assertArrayEquals("the restore still gets the bytes", blob, SavedTransactionsStore.load(c))
        assertFalse(SavedTransactionsStore.file(c).exists())
        assertEquals("the key is kept: nothing is lost", hex(blob), sync(c).getString("saved_transactions", null))
        // Once the store can write again, the next load migrates it.
        File(dir, "saved_transactions.bin.tmp").delete()
        assertArrayEquals(blob, SavedTransactionsStore.load(c))
        assertTrue(SavedTransactionsStore.file(c).exists())
        assertFalse(sync(c).contains("saved_transactions"))
    }

    @Test fun `an unreadable legacy key loads as empty and is kept`() {
        val c = ctx()
        sync(c).edit().putString("saved_transactions", "zz-not-hex").commit()
        val got = SavedTransactionsStore.load(c)
        assertEquals(0, got?.size)
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, SavedTransactionsCheckpoint.declaredCount(got))
        assertTrue(sync(c).contains("saved_transactions"))
    }

    @Test fun `no checkpoint loads as null`() {
        val c = ctx()
        assertNull(SavedTransactionsStore.load(c))
        assertFalse(SavedTransactionsStore.exists(c))
    }

    @Test fun `a write lands atomically, replaces the file and removes a legacy copy`() {
        val c = ctx()
        sync(c).edit().putString("saved_transactions", "01000000").commit()
        assertTrue(SavedTransactionsStore.write(c, blob, SavedTransactionsStore.currentEpoch()))
        assertArrayEquals(blob, SavedTransactionsStore.file(c).readBytes())
        assertFalse(File(dir, "saved_transactions.bin.tmp").exists())
        assertFalse(sync(c).contains("saved_transactions"))
        val second = byteArrayOf(1, 0, 0, 0, 9)
        assertTrue(SavedTransactionsStore.write(c, second, SavedTransactionsStore.currentEpoch()))
        assertArrayEquals(second, SavedTransactionsStore.load(c))
    }

    @Test fun `a write snapshotted before a delete is dropped`() {
        val c = ctx()
        val epoch = SavedTransactionsStore.currentEpoch()
        assertTrue(SavedTransactionsStore.delete(c))
        assertFalse(SavedTransactionsStore.write(c, blob, epoch))
        assertFalse(SavedTransactionsStore.file(c).exists())
    }

    @Test fun `a failed write says so and leaves the previous checkpoint`() {
        val c = ctx()
        assertTrue(SavedTransactionsStore.write(c, blob, SavedTransactionsStore.currentEpoch()))
        File(dir, "saved_transactions.bin.tmp").mkdirs()
        assertFalse(SavedTransactionsStore.write(c, byteArrayOf(9, 9, 9, 9), SavedTransactionsStore.currentEpoch()))
        assertArrayEquals(blob, SavedTransactionsStore.file(c).readBytes())
    }

    // (e) the wipe
    @Test fun `delete removes the file, its tmp and the legacy key`() {
        val c = ctx()
        SavedTransactionsStore.write(c, blob, SavedTransactionsStore.currentEpoch())
        File(dir, "saved_transactions.bin.tmp").writeBytes(blob)
        sync(c).edit().putString("saved_transactions", hex(blob)).commit()
        assertTrue(SavedTransactionsStore.delete(c))
        assertFalse(SavedTransactionsStore.file(c).exists())
        assertFalse(File(dir, "saved_transactions.bin.tmp").exists())
        assertFalse(sync(c).contains("saved_transactions"))
        assertFalse(SavedTransactionsStore.exists(c))
    }

    @Test fun `the full wipe removes every network's checkpoint`() {
        val c = ctx()
        val names = listOf("saved_transactions.bin", "saved_transactions.bin.tmp",
            "saved_transactions_testnet.bin", "saved_transactions_testnet.bin.tmp")
        names.forEach { File(dir, it).writeBytes(blob) }
        sync(c).edit().putString("saved_transactions", "00").commit()
        c.getSharedPreferences("dgb_sync_data_testnet", Context.MODE_PRIVATE).edit().putString("saved_transactions", "00").commit()
        assertTrue(SavedTransactionsStore.deleteAllNetworks(c))
        names.forEach { assertFalse("$it survived the wipe", File(dir, it).exists()) }
        assertFalse(sync(c).contains("saved_transactions"))
        assertFalse(c.getSharedPreferences("dgb_sync_data_testnet", Context.MODE_PRIVATE).contains("saved_transactions"))
    }

    @Test fun `every wipe path deletes the checkpoint file`() {
        val repo = File("..").canonicalFile
        fun src(path: String) = File(repo, path).readText()
        val eraser = src("core/src/main/java/app/aroundtheblock/wallet/core/WalletDataEraser.kt")
        assertTrue("the wallet wipe no longer deletes every network's checkpoint",
            eraser.contains("SavedTransactionsStore.deleteAllNetworks(context)") && eraser.contains("savedTransactions && abandonedBand"))
        assertTrue("the crash-loop breaker no longer deletes the checkpoint",
            src("app/src/main/java/app/aroundtheblock/wallet/StaleDataWiper.kt").contains("SavedTransactionsStore.delete(context)"))
        val wm = src("core/src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt")
        assertTrue("a new wallet (clearSyncData) no longer drops the previous checkpoint",
            wm.contains("SavedTransactionsStore.delete(context)"))
        val rebuild = src("core/src/main/java/app/aroundtheblock/wallet/core/sync/HistoryRebuildOnUpgrade.kt")
        assertTrue("the history rebuild no longer drops the checkpoint",
            rebuild.contains("SavedTransactionsStore.delete(context)") && rebuild.contains("SavedTransactionsStore.file(context)"))
    }

    @Test fun `no source writes the legacy prefs key any more`() {
        val repo = File("..").canonicalFile
        val kotlin = listOf(File(repo, "core/src/main/java"), File(repo, "app/src/main/java"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(kotlin.size > 50)
        for (f in kotlin) {
            val text = f.readText()
            assertFalse("${f.name} writes the legacy saved_transactions key",
                Regex("""putString\(\s*"saved_transactions"""").containsMatchIn(text))
            if (f.name != "WalletTxPersister.kt" && f.name != "NativeBridge.kt") {
                assertFalse("${f.name} serializes the wallet's set itself: go through WalletTxPersister",
                    Regex("""NativeBridge\.getSerializedTransactions\(""").containsMatchIn(text))
            }
        }
    }
}
