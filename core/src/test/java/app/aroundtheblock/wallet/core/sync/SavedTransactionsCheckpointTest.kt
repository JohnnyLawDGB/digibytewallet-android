package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TX-CKPT-CAP, the restore side: a restore that loads fewer checkpointed transactions than the
 * checkpoint declares is recorded, so the sync start rebuilds from the birth floor (the SyncService
 * side is CfRestorePreflightTest / TransactionCheckpointPreflightGateTest); a complete restore is
 * not, so it resumes near the tip. The native writer/reader limit is held by the host KAT
 * tx_ckpt_cap_kat; the Kotlin mirror of it is held to the header here.
 */
class SavedTransactionsCheckpointTest {

    /** The checkpoint hex of a blob declaring [count] records (framing only). */
    private fun hexDeclaring(count: Long, tail: String = "") =
        "%02x%02x%02x%02x".format(count and 0xff, (count shr 8) and 0xff, (count shr 16) and 0xff, (count shr 24) and 0xff) + tail

    @Test fun `declared count is the leading little-endian u32`() {
        assertEquals(10_001L, SavedTransactionsCheckpoint.declaredCount(hexDeclaring(10_001, "deadbeef")))
        assertEquals(1L, SavedTransactionsCheckpoint.declaredCount("01000000"))
        assertEquals(0xFFFFFFFFL, SavedTransactionsCheckpoint.declaredCount("ffffffff"))
        assertEquals(0xFFFFFFFFL, SavedTransactionsCheckpoint.declaredCount("FFFFFFFF"))
    }

    @Test fun `no checkpoint declares nothing, an unreadable one is unreadable`() {
        assertNull(SavedTransactionsCheckpoint.declaredCount(null))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, SavedTransactionsCheckpoint.declaredCount(""))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, SavedTransactionsCheckpoint.declaredCount("0100"))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, SavedTransactionsCheckpoint.declaredCount("01zz0000"))
    }

    @Test fun `short is fewer loaded than declared, or an unreadable checkpoint`() {
        assertTrue(SavedTransactionsCheckpoint.isShort(10_001, 0))          // the reader refused the whole blob
        assertTrue(SavedTransactionsCheckpoint.isShort(10_001, 10_000))     // one record lost
        assertTrue(SavedTransactionsCheckpoint.isShort(SavedTransactionsCheckpoint.UNREADABLE, 0))
        assertFalse(SavedTransactionsCheckpoint.isShort(10_001, 10_001))
        assertFalse(SavedTransactionsCheckpoint.isShort(null, 0))            // no checkpoint: not this rule's
        assertFalse(SavedTransactionsCheckpoint.isShort(0, 0))
    }

    @Test fun `a short restore is recorded, a complete one is not`() {
        val prefs = FakeSharedPreferences()
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, hexDeclaring(10_001), 10_001))
        assertFalse(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, null, 0))
        assertFalse(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))

        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, hexDeclaring(10_001), 0))
        assertEquals(10_001L, prefs.getLong(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE, 0L))
    }

    @Test fun `the record is sticky until the rebuild clears it`() {
        // A checkpoint rewritten from the short wallet (a send's persist) declares the short count, so
        // the next restore is "complete"; the record taken earlier must still stand.
        val prefs = FakeSharedPreferences()
        assertTrue(SavedTransactionsCheckpoint.recordRestore(prefs, "zz", 0))
        assertEquals(SavedTransactionsCheckpoint.UNREADABLE, prefs.getLong(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE, 0L))
        assertFalse(SavedTransactionsCheckpoint.recordRestore(prefs, hexDeclaring(3), 3))
        assertTrue(prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE))
    }

    @Test fun `the Kotlin limit is the core's shipped limit`() {
        val header = File(File("..").canonicalFile, "native/src/main/jni/digibytewallet-core/BRSavedTransactions.h")
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
        val record = wm.indexOf("SavedTransactionsCheckpoint.recordRestore(syncPrefs, savedTxHex, loaded.toLong())")
        val load = wm.indexOf("loaded = NativeBridge.loadSerializedTransactions(txBytes)")
        val build = wm.indexOf("NativeBridge.restoreWalletFromBytes(seedBytes, restoreTime, storedPass)")
        assertTrue("the restore no longer records a short load", record > 0)
        assertTrue("the record must follow the load and precede the build", load in 1 until record && record < build)
    }
}
