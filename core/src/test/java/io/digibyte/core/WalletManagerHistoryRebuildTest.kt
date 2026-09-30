package io.digibyte.core

import android.content.Context
import io.digibyte.core.sync.HistoryRebuildOnUpgrade
import io.digibyte.core.sync.HistoryRebuildOnUpgrade.OutcomeKind
import io.digibyte.core.sync.fakeContext
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The paths that rebuild the transaction cache from scratch mark the one-time rebuild as not
 * needed: what they write afterwards comes from this build. And the wallet load discards an
 * earlier build's cache before it reads it, with the header anchor lowered to the oldest record.
 */
class WalletManagerHistoryRebuildTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() = HistoryRebuildOnUpgrade.resetProcessStateForTest()
    @After fun resetAfter() = HistoryRebuildOnUpgrade.resetProcessStateForTest()

    private fun walletManager(ctx: Context) = WalletManager(
        context = ctx,
        keyStoreManager = mockk(relaxed = true),
        utxoManager = mockk(relaxed = true),
        dataEraser = mockk(relaxed = true),
        quiesceNative = { },
    )

    private fun ctx(): Context {
        val c = fakeContext()
        every { c.filesDir } returns tmp.newFolder()
        return c
    }

    /** One confirmed record, framing only (count 1; size 2, height, time; 2 bytes). */
    private val oneRecordHex = "01000000" + "02000000" + "404b4c01" + "80e0b862" + "abcd"

    private fun writeCache(c: Context) {
        c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).edit()
            .putString("saved_transactions", oneRecordHex).commit()
    }

    @Test fun clearSyncData_marksTheRebuildNotNeeded() {
        val c = ctx()
        c.getSharedPreferences("dgb_history_rebuild", Context.MODE_PRIVATE).edit()
            .putLong("floor_hint", 7L).putLong("floor_time", 8L).commit()

        walletManager(c).clearSyncData()
        writeCache(c) // the new wallet's own records, written by this build

        assertEquals(OutcomeKind.NOT_NEEDED, HistoryRebuildOnUpgrade.runAtProcessStart(c).kind)
        assertTrue(c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).contains("saved_transactions"))
        assertEquals("a different wallet's floor is forgotten", 0L, HistoryRebuildOnUpgrade.floorHint(c))
        assertEquals(0L, HistoryRebuildOnUpgrade.floorTime(c))
    }

    @Test fun rebuildFromChainRescan_marksTheRebuildNotNeeded_andKeepsTheFloor() {
        val c = ctx()
        c.getSharedPreferences("dgb_history_rebuild", Context.MODE_PRIVATE).edit()
            .putLong("floor_time", 8L).commit()
        writeCache(c)

        walletManager(c).rebuildFromChainRescan()
        assertFalse(c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).contains("saved_transactions"))
        writeCache(c) // re-derived by the manual rebuild's scan

        assertEquals(OutcomeKind.NOT_NEEDED, HistoryRebuildOnUpgrade.runAtProcessStart(c).kind)
        assertTrue(c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).contains("saved_transactions"))
        assertEquals("same wallet: the floor time stays", 8L, HistoryRebuildOnUpgrade.floorTime(c))
    }

    @Test fun rebuildFromChainRescan_stillForgetsRecordedSends() {
        val c = ctx()
        OutgoingTxStore(c).record("ff66", 1L, 1L, "to")
        walletManager(c).rebuildFromChainRescan()
        assertNull(OutgoingTxStore(c).lookup("ff66"))
    }

    // ── the wallet load ───────────────────────────────────────────────────

    @Test fun creationTimeForRestore_lowersToTheFloorTimeOnly() {
        assertEquals(1_700_000_000L, creationTimeForRestore(1_700_000_000L, 0L))
        assertEquals(1_600_000_000L, creationTimeForRestore(1_700_000_000L, 1_600_000_000L))
        assertEquals("never raised", 1_700_000_000L, creationTimeForRestore(1_700_000_000L, 1_800_000_000L))
        assertEquals("no stored time: the existing default", 1774252800L, creationTimeForRestore(0L, 0L))
        assertEquals(1_600_000_000L, creationTimeForRestore(0L, 1_600_000_000L))
    }

    private val source: String by lazy { File("src/main/java/io/digibyte/core/WalletManager.kt").readText() }

    private fun body(fn: String): String {
        val start = source.indexOf("fun $fn(")
        require(start >= 0) { "scanner is blind: no $fn" }
        val next = source.indexOf("\n    fun ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    @Test fun restoreFromDisk_evaluatesTheRebuildBeforeItReadsTheCache() {
        val b = body("restoreFromDisk")
        val eval = b.indexOf("HistoryRebuildOnUpgrade.beforeWalletLoad(")
        val read = b.indexOf("getString(\"saved_transactions\"")
        assertTrue("scanner is blind: no cache read", read >= 0)
        assertTrue("the rebuild is not evaluated before the cache is read", eval in 0 until read)
        assertTrue("a partial rebuild must not load the cache", b.substring(eval, read).contains("isInProgress("))
    }

    @Test fun restoreFromDisk_anchorsTheHeadersAtTheFloorTime() {
        val b = body("restoreFromDisk")
        assertTrue(b.contains("creationTimeForRestore("))
        assertTrue(b.contains("HistoryRebuildOnUpgrade.floorTime("))
    }
}
