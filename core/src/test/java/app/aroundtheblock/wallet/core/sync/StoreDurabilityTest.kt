package app.aroundtheblock.wallet.core.sync

import android.content.Context
import io.mockk.every
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * STORE-DURABILITY: every persisted sync store writes durably and never destroys its last good copy.
 * For each file store: the tmp file is fsynced before it is renamed and the directory after; a rename
 * that fails keeps the live file and reports failure; a missing live file beside a COMPLETE tmp is
 * recovered and a partial one ignored; a legacy key is removed only once the file holding it landed.
 */
class StoreDurabilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private val savedFsync = DurableFile.fsync
    private val savedRename = DurableFile.rename
    private val savedSyncDir = DurableFile.syncDir

    @After fun restoreSeams() {
        DurableFile.fsync = savedFsync
        DurableFile.rename = savedRename
        DurableFile.syncDir = savedSyncDir
    }

    private lateinit var dir: File

    private fun ctx(): Context = fakeContext().also { c ->
        dir = tmp.newFolder()
        every { c.filesDir } returns dir
    }

    private fun sync(c: Context) = c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** One store under test, by its public surface. */
    private class Store(
        val base: String,
        val write: (Context, ByteArray) -> Any?,
        val load: (Context) -> ByteArray?,
        val delete: (Context) -> Unit,
        val legacyKey: String?,
        val blob: (Int) -> ByteArray,
    )

    private val stores = listOf(
        Store("saved_filter_headers", { c, b -> FilterHeaderStore.write(c, b, FilterHeaderStore.currentEpoch()) == StoreWrite.LANDED },
            FilterHeaderStore::load, FilterHeaderStore::delete, "saved_filter_headers", ::filterChain),
        Store("saved_blocks", { c, b -> SavedBlockStore.write(c, b, SavedBlockStore.currentEpoch()) == StoreWrite.LANDED },
            SavedBlockStore::load, SavedBlockStore::delete, "saved_blocks", ::savedBlocks),
        Store("saved_cf_ledger", { c, b -> CfScanLedgerStore.write(c, b, CfScanLedgerStore.currentEpoch()) == StoreWrite.LANDED },
            CfScanLedgerStore::load, CfScanLedgerStore::delete, null, ::ledger),
        Store("saved_transactions", { c, b -> SavedTransactionsStore.write(c, b, SavedTransactionsStore.currentEpoch()) },
            SavedTransactionsStore::load, { c -> SavedTransactionsStore.delete(c) }, null, ::transactions),
    )

    @Test fun `a write fsyncs the tmp file, then renames it, then syncs the directory`() {
        for (s in stores) {
            val c = ctx()
            val events = mutableListOf<String>()
            DurableFile.fsync = { out -> events += "fsync"; savedFsync(out) }
            DurableFile.rename = { from, to -> events += "rename ${from.name} -> ${to.name}"; savedRename(from, to) }
            DurableFile.syncDir = { d -> events += "syncDir ${d?.name}"; savedSyncDir(d) }
            assertEquals(s.base, true, s.write(c, s.blob(3)))
            assertEquals(s.base, listOf("fsync", "rename ${s.base}.bin.tmp -> ${s.base}.bin", "syncDir ${dir.name}"), events)
            assertArrayEquals(s.base, s.blob(3), File(dir, "${s.base}.bin").readBytes())
        }
    }

    // The PIN store (core/security/FilePinStore.kt) writes through the same procedure (review item 3).

    @Test fun `the PIN store write fsyncs the tmp file, renames it, then syncs the directory`() {
        val d = tmp.newFolder()
        val target = File(d, "dgb_pin_store.bin")
        val events = mutableListOf<String>()
        DurableFile.fsync = { out -> events += "fsync"; savedFsync(out) }
        DurableFile.rename = { from, to -> events += "rename ${from.name} -> ${to.name}"; savedRename(from, to) }
        app.aroundtheblock.wallet.core.security.writeWhole(target, byteArrayOf(1, 2, 3)) { events += "syncDir ${it.name}" }
        assertEquals(listOf("fsync", "rename dgb_pin_store.bin.tmp -> dgb_pin_store.bin", "syncDir ${d.name}"), events)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test fun `the PIN store write whose rename fails keeps the live file and throws`() {
        val d = tmp.newFolder()
        val target = File(d, "dgb_pin_store.bin").apply { writeBytes(byteArrayOf(9)) }
        DurableFile.rename = { _, _ -> false }
        val r = runCatching { app.aroundtheblock.wallet.core.security.writeWhole(target, byteArrayOf(1, 2, 3)) { } }
        assertTrue("a write that did not land must throw", r.isFailure)
        assertArrayEquals("the live copy stays", byteArrayOf(9), target.readBytes())
        assertFalse(File(d, "dgb_pin_store.bin.tmp").exists())
    }

    @Test fun `a rename that fails keeps the live file and reports failure`() {
        for (s in stores) {
            val c = ctx()
            val old = s.blob(2)
            assertEquals(s.base, true, s.write(c, old))
            DurableFile.rename = { _, _ -> false }
            assertEquals(s.base, false, s.write(c, s.blob(5)))
            DurableFile.rename = savedRename
            assertArrayEquals("${s.base}: the live copy is untouched", old, File(dir, "${s.base}.bin").readBytes())
            assertFalse("${s.base}: the tmp file is removed", File(dir, "${s.base}.bin.tmp").exists())
            assertArrayEquals(s.base, old, s.load(c))
        }
    }

    @Test fun `a failed fsync does not publish the tmp file`() {
        for (s in stores) {
            val c = ctx()
            val old = s.blob(2)
            assertEquals(s.base, true, s.write(c, old))
            DurableFile.fsync = { throw java.io.SyncFailedException("injected") }
            assertEquals(s.base, false, s.write(c, s.blob(5)))
            DurableFile.fsync = savedFsync
            assertArrayEquals(s.base, old, File(dir, "${s.base}.bin").readBytes())
        }
    }

    @Test fun `a missing live file beside a complete tmp is recovered`() {
        for (s in stores) {
            val c = ctx()
            val whole = s.blob(4)
            File(dir, "${s.base}.bin.tmp").writeBytes(whole)
            assertArrayEquals(s.base, whole, s.load(c))
            assertArrayEquals("${s.base}: renamed into place", whole, File(dir, "${s.base}.bin").readBytes())
            assertFalse(s.base, File(dir, "${s.base}.bin.tmp").exists())
        }
    }

    @Test fun `a partial or overlong tmp is ignored`() {
        for (s in stores) {
            val c = ctx()
            val whole = s.blob(4)
            for (bad in listOf(whole.copyOf(whole.size - 1), whole + byteArrayOf(0), whole.copyOf(3))) {
                File(dir, "${s.base}.bin.tmp").writeBytes(bad)
                assertNull(s.base, s.load(c))
                assertFalse(s.base, File(dir, "${s.base}.bin").exists())
            }
        }
    }

    @Test fun `a tmp beside a live file is not used`() {
        for (s in stores) {
            val c = ctx()
            assertEquals(s.base, true, s.write(c, s.blob(2)))
            File(dir, "${s.base}.bin.tmp").writeBytes(s.blob(6))
            assertArrayEquals(s.base, s.blob(2), s.load(c))
        }
    }

    @Test fun `delete removes the tmp as well, so nothing is recovered after it`() {
        for (s in stores) {
            val c = ctx()
            assertEquals(s.base, true, s.write(c, s.blob(2)))
            File(dir, "${s.base}.bin.tmp").writeBytes(s.blob(3))
            s.delete(c)
            assertNull(s.base, s.load(c))
        }
    }

    @Test fun `a legacy key whose migration did not land is kept and still restores`() {
        for (s in stores.filter { it.legacyKey != null }) {
            val c = ctx()
            val bytes = s.blob(3)
            sync(c).edit().putString(s.legacyKey, hex(bytes)).commit()
            DurableFile.rename = { _, _ -> false }
            assertArrayEquals(s.base, bytes, s.load(c))
            DurableFile.rename = savedRename
            assertFalse(s.base, File(dir, "${s.base}.bin").exists())
            assertEquals("${s.base}: the key is the last good copy", hex(bytes), sync(c).getString(s.legacyKey, null))
            assertArrayEquals(s.base, bytes, s.load(c))
            assertArrayEquals("${s.base}: migrated once it can be", bytes, File(dir, "${s.base}.bin").readBytes())
            assertFalse("${s.base}: then the key goes", sync(c).contains(s.legacyKey))
        }
    }

    @Test fun `a legacy key a failed migration kept goes once a later write lands`() {
        for (s in stores.filter { it.legacyKey != null }) {
            val c = ctx()
            val bytes = s.blob(3)
            sync(c).edit().putString(s.legacyKey, hex(bytes)).commit()
            DurableFile.rename = { _, _ -> false }
            assertArrayEquals(s.base, bytes, s.load(c))           // the migration write fails: key kept
            DurableFile.rename = savedRename
            assertTrue(s.base, sync(c).contains(s.legacyKey))
            assertEquals(s.base, true, s.write(c, s.blob(4)))    // the coalesced writer / persistBlocks lands
            assertFalse("${s.base}: a landed write leaves the legacy key pinned", sync(c).contains(s.legacyKey))
            assertArrayEquals("${s.base}: the load reads the file", s.blob(4), s.load(c))
        }
    }

    @Test fun `a readable legacy key beside a file is the newer copy`() {
        for (s in stores.filter { it.legacyKey != null }) {
            val c = ctx()
            assertEquals(s.base, true, s.write(c, s.blob(2)))
            sync(c).edit().putString(s.legacyKey, hex(s.blob(5))).commit()   // a downgraded build wrote it
            assertArrayEquals(s.base, s.blob(5), s.load(c))
            assertArrayEquals("${s.base}: the file is rewritten from it", s.blob(5), File(dir, "${s.base}.bin").readBytes())
            assertFalse("${s.base}: then it goes", sync(c).contains(s.legacyKey))
        }
    }

    @Test fun `a kill between the two removals of a delete leaves no tmp to recover`() {
        val savedRemove = DurableFile.remove
        try {
            for (s in stores) {
                val c = ctx()
                assertEquals(s.base, true, s.write(c, s.blob(2)))
                File(dir, "${s.base}.bin.tmp").writeBytes(s.blob(3))    // a complete tmp: recoverable
                var removals = 0
                DurableFile.remove = { f -> if (removals++ == 0) f.delete() else false } // killed after the first
                s.delete(c)
                DurableFile.remove = savedRemove
                assertFalse("${s.base}: the tmp outlived a delete, and the next load would recover it",
                    File(dir, "${s.base}.bin.tmp").exists())
                assertFalse("${s.base}: the deleted data came back", s.load(c).contentEquals(s.blob(3)))
            }
        } finally {
            DurableFile.remove = savedRemove
        }
    }

    companion object {
        private fun ByteArrayOutputStream.u32(v: Long) { for (i in 0 until 4) write(((v shr (8 * i)) and 0xff).toInt()) }

        /** BRCompactFilterChainSerialize: magic "BCFC", version 1, type, start, anchorPrev, count, 32*count. */
        fun filterChain(n: Int): ByteArray = ByteArrayOutputStream().apply {
            u32(0x46434243L); write(1); write(0); u32(1_000L); write(ByteArray(32) { 7 }); u32(n.toLong())
            repeat(n) { i -> write(ByteArray(32) { i.toByte() }) }
        }.toByteArray()

        /** bridge_saveBlocks: [u32 count][u32 serLen, u32 height, serLen bytes]... */
        fun savedBlocks(n: Int): ByteArray = ByteArrayOutputStream().apply {
            u32(n.toLong()); repeat(n) { i -> u32(10L); u32(5_000L - i); write(ByteArray(10) { i.toByte() }) }
        }.toByteArray()

        /** BRCFScanLedgerSerialize: "CFL1", version, start, scanned, requested, [abandoned v2+], out, gaveUp. */
        fun ledger(n: Int, version: Int = 3): ByteArray = ByteArrayOutputStream().apply {
            u32(0x43464C31L); u32(version.toLong()); u32(100L); u32(200L); u32(300L)
            if (version >= 2) u32(0L)
            u32(n.toLong()); repeat(n) { i -> u32(150L + i); write(0); if (version >= 3) write(1) }
            u32(n.toLong()); repeat(n) { i -> u32(120L + i); if (version >= 3) { write(2); write(1) } }
        }.toByteArray()

        /** BRWalletSerializeTransactions framing, as SavedTransactionsStore checks it. */
        fun transactions(n: Int): ByteArray = ByteArrayOutputStream().apply {
            u32(n.toLong()); repeat(n) { i -> u32(6L); u32(1_000L + i); u32(2_000L + i); write(ByteArray(6) { i.toByte() }) }
        }.toByteArray()
    }
}
