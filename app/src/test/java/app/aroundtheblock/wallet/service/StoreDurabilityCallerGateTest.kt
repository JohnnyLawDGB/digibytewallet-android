package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate, STORE-DURABILITY: the sync stores now report a write that did not land and keep the
 * previous file, so every caller in SyncService must act on that answer instead of treating the write
 * as done. The flush rule is [app.aroundtheblock.wallet.core.sync.CfStateFlush], tested in `core`
 * (`CfStateFlushTest`); the store behaviour is `StoreDurabilityTest`. This pins that:
 *  - the coalesced writer and the synchronous flush write the filter headers and the ledger only
 *    through that one rule, and re-mark their state dirty when it says something did not land;
 *  - the saved-blocks tip guard is advanced only after the block window landed (a tip recorded for a
 *    window that is not on disk would refuse every later, lower-but-newer window as a regression);
 *  - `has_synced` is gated on the ledger write of this flush, not only on some ledger file existing.
 */
class StoreDurabilityCallerGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())

    private fun body(signature: String): IntRange {
        val at = sync.code.indexOf(signature)
        assertTrue("$signature not found: the gate is blind, not clean", at >= 0)
        return sync.enclosingBlock(sync.code.indexOf('{', at) + 1)
            ?: throw AssertionError("$signature has no body")
    }

    @Test fun `the header and ledger stores are written only through the flush rule`() {
        val flushes = sync.calls("CfStateFlush.flush")
        assertEquals("one copy of the flush: flushCfState", 1, flushes.size)
        assertTrue("CfStateFlush.flush is called outside flushCfState", flushes.single().range.first in body("private fun flushCfState("))
        for (store in listOf("FilterHeaderStore.write", "CfScanLedgerStore.write")) {
            val writes = sync.calls(store)
            assertEquals("$store: written once, inside the flush", 1, writes.size)
            assertTrue("$store is written outside CfStateFlush.flush", writes.single().range.first in flushes.single().range)
        }
        val gate = flushes.single().valueRange("checkpointGate")
            ?: throw AssertionError("the flush is given no checkpoint gate")
        assertEquals("{ persistWalletTransactionsCheckpoint() }", KotlinSourceGate.squeeze(sync.code.substring(gate)))
    }

    @Test fun `both flush sites re-mark dirty when something did not land`() {
        for (site in listOf("private fun startFilterHeaderWriter()", "private fun flushFilterHeaders(")) {
            val range = body(site)
            assertEquals("$site must flush once", 1, sync.calls("flushCfState", range).size)
            val text = KotlinSourceGate.squeeze(sync.code.substring(range))
            assertTrue("$site does not re-mark dirty on a write that did not land: $text",
                text.contains("val outcome = flushCfState(") && text.contains("if (!outcome.allLanded) filterHeadersDirty = true"))
        }
        assertEquals("flushCfState has a caller besides the two flush sites", 2, sync.calls("flushCfState").size)
    }

    @Test fun `the saved-blocks tip is recorded only after the window landed`() {
        val range = body("private fun persistBlocks(")
        val write = sync.calls("SavedBlockStore.write", range).singleOrNull()
            ?: throw AssertionError("persistBlocks must write the window exactly once")
        val tip = sync.written.indexOf("putLong(\"saved_blocks_tip\"", range.first)
        assertTrue("the tip is not recorded in persistBlocks", tip in range)
        val head = KotlinSourceGate.squeeze(sync.code.substring(range.first, write.range.first))
        assertTrue("persistBlocks ignores what the block window's write answered", head.endsWith("when ("))
        val close = sync.code.indexOf(')', write.range.last + 1)
        val branches = sync.blockAt(sync.code.indexOf('{', close))
            ?: throw AssertionError("the write's answer has no branches")
        val text = KotlinSourceGate.squeeze(sync.code.substring(branches))
        assertTrue("a window that failed must leave persistBlocks as a failure: $text",
            Regex("""StoreWrite\.FAILED -> \{[^}]*return false \}""").containsMatchIn(text))
        assertTrue("a stale (obsolete) window must leave persistBlocks, and is not a failure: $text",
            text.contains("StoreWrite.OBSOLETE, StoreWrite.NONE -> return true"))
        assertTrue("the tip is recorded before the window's write answered", tip > branches.last)
    }

    @Test fun `a ledger a delete made stale is not written and its gate is not run`() {
        val flush = sync.calls("CfStateFlush.flush").single()
        val ledger = flush.valueRange("ledger") ?: throw AssertionError("the flush is given no ledger")
        val text = KotlinSourceGate.squeeze(sync.code.substring(ledger))
        assertTrue("the pending ledger does not say whether a delete made it stale: $text",
            text.contains("obsolete = { CfScanLedgerStore.currentEpoch() != ep }"))
    }

    @Test fun `the teardown flush runs off the main thread, bounded, in order`() {
        val range = body("private fun flushForTeardownBounded()")
        val text = KotlinSourceGate.squeeze(sync.code.substring(range))
        assertTrue("the teardown flush must run on its own thread: $text", text.contains("Thread("))
        assertTrue("onDestroy must wait for it at most the budget: $text", text.contains("worker.join(TEARDOWN_FLUSH_BUDGET_MS)"))
        assertTrue("order: tx checkpoint -> ledger -> block window -> headers: $text",
            text.contains("flushFilterHeaders(beforeHeaders = { flushLastBlockWindow("))
        assertTrue("onDestroy no longer uses the bounded teardown flush",
            sync.calls("flushForTeardownBounded", body("override fun onDestroy()")).size == 1)
    }

    @Test fun `has_synced waits for this flush's ledger write`() {
        val range = body("private fun persistSyncCompletionState()")
        val mark = sync.calls("shouldMarkSynced", range).singleOrNull()
            ?: throw AssertionError("persistSyncCompletionState must ask shouldMarkSynced once")
        val durable = mark.valueRange("ledgerDurable")
            ?: throw AssertionError("shouldMarkSynced is passed no ledgerDurable")
        assertTrue("ledgerDurable must include whether this flush's ledger landed",
            sync.code.substring(durable).contains("ledgerLanded"))
    }
}
