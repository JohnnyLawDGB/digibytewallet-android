package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the stranded-send sweep decides what to re-publish through [StrandedSendSelector]
 * and settles, in the store, every recorded send the wallet holds at a confirmed height — so a
 * confirmed send is never re-published and leaves the sweep's working set. The sweep used to read
 * the wallet's recent-transaction window on its own and re-published every recorded send the
 * window did not list; a confirmed send that had aged out of the window was re-published every
 * 90 s for as long as the wallet ran (Note 8, 2026-09-26). The decision itself is covered by
 * [StrandedSendSelectorTest]; this pins that the sweep consults it, acts on its settlements, and
 * counts its attempts.
 */
class StrandedSweepSettlesConfirmedGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())

    private val sweep: IntRange
        get() {
            val at = sync.code.indexOf("private suspend fun rebroadcastStrandedSends()")
            assertTrue("rebroadcastStrandedSends not found", at >= 0)
            return at until sync.code.indexOf("\n    }\n", at)
        }

    @Test fun `the sweep takes its selection from the selector, over the pending records`() {
        val selects = sync.calls("StrandedSendSelector.select", sweep)
        assertEquals("the sweep must ask the selector exactly once", 1, selects.size)
        assertEquals(listOf("recorded", "details", "store::sweepAttempts", "now"), selects.single().arguments)
        assertEquals("the sweep reads the pending records once", 1, sync.calls("store.pendingTxids", sweep).size)
        assertEquals("the wallet's transaction list is read once, for the selector", 1, sync.calls("NativeBridge.getTransactionDetails", sweep).size)
        assertTrue("the sweep still parses the transaction list on its own", sync.calls("split", sweep).isEmpty())
    }

    @Test fun `the sweep settles what the selector settles and counts every attempt`() {
        val settles = sync.calls("store.markSettled", sweep)
        assertEquals("the sweep must settle a decided send exactly once", 1, settles.size)
        assertEquals(listOf("txid"), settles.single().arguments)
        val notes = sync.calls("store.noteSweepAttempt", sweep)
        assertEquals("the sweep must count an attempt exactly once", 1, notes.size)
        assertEquals(listOf("txid", "now"), notes.single().arguments)
        val publishes = sync.calls("NativeBridge.publishTransaction", sweep)
        assertEquals(1, publishes.size)
        assertTrue("the attempt is counted before the publish", notes.single().range.first < publishes.single().range.first)
        assertTrue("a send is settled before anything is published", settles.single().range.first < publishes.single().range.first)
    }

    @Test fun `a transaction list the wallet could not produce ends the sweep before any decision`() {
        // The read and its blank check both stand inside runCatching, so a thrown read and a null
        // string alike become null here — and null leaves the function before the selector, the
        // store or the bridge is asked anything. The startup caller has no handler of its own.
        val read = sync.calls("NativeBridge.getTransactionDetails", sweep).single()
        // `runCatching { … }` takes its lambda without brackets, so it is found by its brace.
        val block = Regex("""runCatching\s*\{""").findAll(sync.code)
            .filter { it.range.first in sweep }
            .mapNotNull { sync.blockAt(it.range.last) }
            .single { read.range.first in it }
        assertEquals("the blank check is inside the runCatching", 1, sync.calls("isNotBlank", block).size)
        val select = sync.calls("StrandedSendSelector.select", sweep).single()
        val between = sync.code.substring(block.last, select.range.first)
        assertTrue("no early return between the read and the selector", Regex("""if\s*\(\s*details\s*==\s*null\s*\)[\s\S]*?\breturn\b""").containsMatchIn(between))
        for (asked in listOf("store.markSettled", "store.noteSweepAttempt", "NativeBridge.publishTransaction", "store.sweepAttempts")) {
            assertTrue("$asked is asked before the list is read", sync.calls(asked, sweep).all { it.range.first > select.range.first })
        }
    }

    @Test fun `the sweep publishes a send once per sweep, under the store's count`() {
        // The retry is the next sweep, bounded by the persisted count — not a loop of publishes
        // fifteen seconds apart inside one sweep (Note 8, 2026-09-26: a pending send was
        // submitted at :23, :38 and :53 while its earlier publish still awaited a verdict).
        val body = sync.code.substring(sweep)
        assertTrue("the sweep still loops over publishes within one sweep", !body.contains("for (attempt"))
        assertEquals("the sweep publishes once", 1, sync.calls("NativeBridge.publishTransaction", sweep).size)
        assertEquals("the attempt the sweep logs is the store's", 1, sync.calls("store.sweepAttempts", sweep).size)
    }

    @Test fun `nothing in the service sweeps over settled records`() {
        assertTrue("SyncService still reads every recorded send, settled or not", sync.calls("allTxids").isEmpty())
        assertEquals("the timer gate and the sweep read the pending records", 2, sync.calls("pendingTxids").size)
    }
}
