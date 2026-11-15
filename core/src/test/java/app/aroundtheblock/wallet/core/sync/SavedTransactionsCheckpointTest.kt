package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TX-CKPT-CAP, the restore side: a restore whose reader loads fewer checkpointed transactions than
 * the checkpoint declares is recorded, so the sync start rebuilds from the birth floor (the
 * SyncService side is the app's TransactionCheckpointPreflightTest); a complete restore is not, so
 * it resumes near the tip. The native writer/reader limit is held by the host KAT tx_ckpt_cap_kat;
 * the Kotlin mirror of it is held to the header here.
 */
class SavedTransactionsCheckpointTest {

    /** A blob declaring [count] records (framing only). */
    private fun declaring(count: Long, tail: Int = 0) = ByteArray(4 + tail).also {
        for (i in 0 until 4) it[i] = ((count shr (8 * i)) and 0xff).toByte()
    }

    private val repo = File("..").canonicalFile

    @Test fun `declared count is the leading little-endian u32`() {
        assertEquals(10_001L, SavedTransactionsCheckpoint.declaredCount(declaring(10_001, tail = 8)))
        assertEquals(1L, SavedTransactionsCheckpoint.declaredCount(declaring(1)))
        assertEquals(0xFFFFFFFFL, SavedTransactionsCheckpoint.declaredCount(declaring(0xFFFFFFFFL)))
    }

    @Test fun `no checkpoint declares nothing, a too-short one is unreadable`() {
        assertNull(SavedTransactionsCheckpoint.declaredCount(null))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, SavedTransactionsCheckpoint.declaredCount(ByteArray(0)))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, SavedTransactionsCheckpoint.declaredCount(ByteArray(3)))
    }

    @Test fun `short is fewer loaded by the reader than declared, or an unreadable checkpoint`() {
        assertTrue(SavedTransactionsCheckpoint.isShort(10_001, 0))          // the reader refused the whole blob
        assertTrue(SavedTransactionsCheckpoint.isShort(10_001, 10_000))     // one record lost
        assertTrue(SavedTransactionsCheckpoint.isShort(SavedTransactionsCheckpoint.UNREADABLE, 0))
        assertFalse(SavedTransactionsCheckpoint.isShort(10_001, 10_001))
        assertFalse(SavedTransactionsCheckpoint.isShort(null, 0))            // no checkpoint: not this rule's
        assertFalse(SavedTransactionsCheckpoint.isShort(0, 0))
    }

    @Test fun `a short restore is recorded, a complete one is not`() {
        val prefs = FakeSharedPreferences()
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, declaring(10_001), 10_001))
        assertFalse(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, null, 0))
        assertFalse(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))

        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, declaring(10_001), 0))
        assertEquals(10_001L, prefs.getLong(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE, 0L))
    }

    @Test fun `the record is sticky until the rebuild clears it`() {
        // A checkpoint rewritten from the short wallet (a send's persist) declares the short count, so
        // the next restore is "complete"; the record taken earlier must still stand.
        val prefs = FakeSharedPreferences()
        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, ByteArray(0), 0))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, prefs.getLong(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE, 0L))
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, declaring(3), 3))
        assertTrue(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))
    }

    // The flag outlived its file: transactions_checkpointed with a recorded count, and no file at all.
    @Test fun `a checkpointed count with no file is a short restore`() {
        val prefs = FakeSharedPreferences()
        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, null, 0, checkpointedCount = 22))
        assertEquals(22L, prefs.getLong(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE, 0L))
        val empty = FakeSharedPreferences()
        assertFalse("an empty wallet's checkpoint (count 0) with no file is not short",
            SavedTransactionsCheckpoint.recordRestore(empty, null, 0, checkpointedCount = 0))
        assertFalse("a file that is there is judged by what it declares, not by the recorded count",
            SavedTransactionsCheckpoint.recordRestore(empty, declaring(3), 3, checkpointedCount = 22))
    }

    @Test fun `the restore checks the recorded count when no file is there`() {
        val wm = File("src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt").readText()
        val count = wm.indexOf("syncPrefs.getLong(SavedTransactionsStore.KEY_COUNT, 0L)")
        val flag = wm.lastIndexOf("syncPrefs.getBoolean(\"transactions_checkpointed\", false)", count)
        val record = wm.indexOf("SavedTransactionsCheckpoint.recordRestore(syncPrefs, null, 0L, checkpointedCount)")
        val build = wm.indexOf("NativeBridge.restoreWalletFromBytes(seedBytes, restoreTime, storedPass)")
        assertTrue("the restore no longer reads the count beside the flag", count > 0 && flag in 0 until count)
        assertTrue("the restore no longer records a lost file as short, before the build", record in (count + 1) until build)
        val sync = File(repo, "app/src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText()
        val commit = sync.substring(sync.indexOf(".putBoolean(\"transactions_checkpointed\", true)")).substringBefore(".commit()")
        assertTrue("the flag's commit no longer carries the count", commit.contains(".putLong(SavedTransactionsStore.KEY_COUNT,"))
    }

    // (a) The false-positive guard. real_writer_3.hex is BRWalletSerializeTransactions' own blob for a
    // fixed 3-record wallet; tx_ckpt_cap_kat holds it current and shows the C readers load all 3.
    // Fed to the restore's check with that reader count, it must not be short: a normal restore
    // resumes near the tip.
    @Test fun `the real writer's blob with the real reader's count is not short`() {
        val vector = File(repo, "native/src/test/host/tx_ckpt_cap_kat/real_writer_3.hex")
        assertTrue("$vector not found", vector.isFile)
        val blob = FilterHeaderStore.decodeHexOrNull(vector.readText().trim())
        assertTrue("the vector is not a 3-record blob", blob != null && blob.size > 4 + 3 * 12)
        assertEquals(3L, SavedTransactionsCheckpoint.declaredCount(blob))
        val prefs = FakeSharedPreferences()
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, blob, readerLoadedCount = 3))
        assertFalse(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))
        // ...and the same blob with a reader that dropped one record is.
        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, blob, readerLoadedCount = 2))
    }

    // (c) The over-limit upgrade path: a checkpoint an earlier build wrote with more records than the
    // limit (that writer had none) is refused whole by the reader (tx_ckpt_cap_kat
    // old_blob_over_limit_refused), so the restore loads 0 and must record a short restore.
    @Test fun `an earlier build's checkpoint above the limit is recorded short`() {
        val prefs = FakeSharedPreferences()
        val old = declaring(SavedTransactionsCheckpoint.MAX_RECORDS + 1, tail = 64)
        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, old, readerLoadedCount = 0))
        assertEquals(SavedTransactionsCheckpoint.MAX_RECORDS + 1, prefs.getLong(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE, 0L))
    }

    @Test fun `the writer refusal breadcrumb is written once per state change`() {
        val diag = FakeSharedPreferences()
        assertTrue(SavedTransactionsCheckpoint.noteWriterRefused(diag, 100_001, nowMs = 1_000))
        assertFalse(SavedTransactionsCheckpoint.noteWriterRefused(diag, 100_005, nowMs = 2_000))   // same state
        assertEquals(100_001L, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_COUNT, 0))
        assertEquals(1_000L, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_AT_MS, 0))
        SavedTransactionsCheckpoint.noteWriterAccepted(diag)
        assertFalse(diag.getBoolean(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED, true))
        assertEquals("the last refusal stays on record", 100_001L, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_COUNT, 0))
        assertTrue(SavedTransactionsCheckpoint.noteWriterRefused(diag, 100_009, nowMs = 3_000))   // a new refusal
        assertEquals(3_000L, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_AT_MS, 0))
    }

    @Test fun `the Kotlin limit is the core's shipped limit`() {
        val header = File(repo, "native/src/main/jni/digibytewallet-core/BRSavedTransactions.h")
        assertTrue("$header not found: is the core submodule checked out?", header.isFile)
        val src = header.readText()
        // The shipped value is the #else arm of the TX_CKPT_CAP_UNFIXED seam.
        val shipped = Regex(
            """#ifdef TX_CKPT_CAP_UNFIXED.*?#else\s*#define BR_SAVED_TRANSACTIONS_MAX_COUNT\s+(\d+)u?\s*#endif""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(src)?.groupValues?.get(1)?.toLong()
        assertEquals("BRSavedTransactions.h's shipped limit", SavedTransactionsCheckpoint.MAX_RECORDS, shipped)
        assertEquals(SavedTransactionsCheckpoint.MAX_RECORDS, HistoryRebuildOnUpgrade.MAX_RECORDS)
    }

    @Test fun `the restore records its load before the wallet is built from it`() {
        val wm = File("src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt").readText()
        val read = wm.indexOf("SavedTransactionsStore.load(context)")
        val load = wm.indexOf("loaded = NativeBridge.loadSerializedTransactions(savedTx)")
        val record = wm.indexOf("SavedTransactionsCheckpoint.recordRestore(syncPrefs, savedTx, loaded.toLong())")
        val build = wm.indexOf("NativeBridge.restoreWalletFromBytes(seedBytes, restoreTime, storedPass)")
        assertTrue("the restore no longer reads the file store", read > 0)
        assertTrue("the restore no longer records a short load", record > 0)
        assertTrue("read, load, record, build: in that order", read < load && load < record && record < build)
    }
}
