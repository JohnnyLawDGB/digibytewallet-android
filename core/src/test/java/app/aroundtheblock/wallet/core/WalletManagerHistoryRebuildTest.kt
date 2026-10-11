package app.aroundtheblock.wallet.core

import android.content.Context
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.OutcomeKind
import app.aroundtheblock.wallet.core.sync.fakeContext
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

    @Test fun rebuildFromChainRescan_armsTheRebuild_soTheNextStartClearsWhatTheLastWindowWroteBack() {
        val c = ctx()
        c.getSharedPreferences("dgb_history_rebuild", Context.MODE_PRIVATE).edit()
            .putBoolean("done", true).putLong("floor_time", 8L).commit()
        writeCache(c)

        walletManager(c).rebuildFromChainRescan()
        assertFalse(c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).contains("saved_transactions"))
        val armed = HistoryRebuildOnUpgrade.readState(c)
        assertFalse("done must be left unset", armed.done)
        assertTrue("the runner is armed", armed.inProgress)
        assertEquals("same wallet: the floor time stays", 8L, armed.floorTime)

        // A coalesced writer lands between the clear and the process exit.
        writeCache(c)

        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, 1L) { 0L }.kind)
        assertFalse(c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).contains("saved_transactions"))
        assertTrue(HistoryRebuildOnUpgrade.readState(c).done)
        assertTrue(HistoryRebuildOnUpgrade.floorTime(c) in 1L..8L)
    }

    @Test fun clearSyncData_whoseClearDoesNotLand_doesNotMarkTheRebuildNotNeeded() {
        val stores = HashMap<String, android.content.SharedPreferences>()
        val c = mockk<Context>(relaxed = true)
        every { c.getSharedPreferences(any(), any()) } answers {
            val name = firstArg<String>()
            stores.getOrPut(name) {
                if (name == "dgb_sync_data") app.aroundtheblock.wallet.core.sync.FailingCommitPrefs(app.aroundtheblock.wallet.core.sync.FakeSharedPreferences())
                else app.aroundtheblock.wallet.core.sync.FakeSharedPreferences()
            }
        }
        every { c.filesDir } returns tmp.newFolder()

        walletManager(c).clearSyncData()

        assertFalse(HistoryRebuildOnUpgrade.readState(c).done)
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

    private val source: String by lazy { File("src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt").readText() }

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
        val lowered = Regex("""val (\w+) = creationTimeForRestore\(\s*creationTime\s*,\s*HistoryRebuildOnUpgrade\.floorTime\(context\)\s*\)""")
            .find(b) ?: error("the restore time is not creationTimeForRestore(creationTime, floorTime)")
        val name = lowered.groupValues[1]
        val calls = Regex("""NativeBridge\.recoverWalletFromBytes\(([^)]*)\)""").findAll(b).map { it.groupValues[1] }.toList()
        assertTrue("scanner is blind: no native restore call", calls.isNotEmpty())
        for (args in calls) {
            val time = args.split(",").map { it.trim() }.getOrNull(1)
            assertEquals("every native restore must receive the lowered time", name, time)
        }
    }

    @Test fun restoreFromDisk_logsTheRebuildUnderTheSharedTag() {
        val b = body("restoreFromDisk")
        val lines = b.lines().filter { it.contains("Log.") && it.contains("rebuild", ignoreCase = true) }
        assertTrue("scanner is blind", lines.isNotEmpty())
        for (l in lines) assertTrue("rebuild log line not under the shared tag: $l", l.contains("HistoryRebuildOnUpgrade.TAG"))
    }
}
