package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate: the stranded-send sweep acts on the core's "refused by the network" answer only once
 * that answer has held -- seen at two observations at least ten minutes apart in this session, and at
 * every observation in between. Read at a single sweep, the answer can be one given before every
 * connected peer had answered (right after start, say), and acting on it removes a plain DGB send and
 * gives its coins back.
 *
 * The rule is [app.aroundtheblock.wallet.core.sync.RefusalHold], tested in `core` (`RefusalHoldTest`).
 * This pins that the sweep's removal input is the hold's verdict on the core's answer, that the core's
 * answer reaches the sweep by no other way, and that the hold is the service's own, for this session,
 * on a clock that only moves forward.
 */
class StrandedSweepRefusalHoldGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())

    private val sweep: IntRange
        get() {
            val at = sync.code.indexOf("private suspend fun rebroadcastStrandedSends()")
            assertTrue("rebroadcastStrandedSends not found", at >= 0)
            return at until sync.code.indexOf("\n    }\n", at)
        }

    @Test fun `the sweep removes on the hold's verdict, not on one sighting of the core's answer`() {
        val asks = sync.calls("outcome.actionFor", sweep)
        assertEquals("the sweep must ask the outcome what to do, exactly once", 1, asks.size)
        val refused = asks.single().valueRange("refusedByPeers")
            ?: throw AssertionError("the sweep passes no refusedByPeers: the gate is blind, not clean")
        val held = sync.calls("refusalHold.observe", refused)
        assertEquals("the sweep removes a send the moment the core first calls it refused", 1, held.size)
        assertEquals(listOf("txid", "isRefusedByPeers(txid)"), held.single().arguments)
        assertEquals(
            "refusedByPeers is more than the hold's verdict",
            KotlinSourceGate.squeeze(held.single().text), KotlinSourceGate.squeeze(sync.code.substring(refused)),
        )
    }

    @Test fun `the core's answer reaches the sweep only through the hold`() {
        val reads = sync.calls("NativeBridge.isRefusedByNetwork")
        assertEquals("the core's refusal answer is read somewhere besides isRefusedByPeers", 1, reads.size)
        val helper = sync.range("private fun isRefusedByPeers(", "\n    }\n")
            ?: throw AssertionError("isRefusedByPeers not found: the gate is blind, not clean")
        assertTrue("the core's refusal answer is read outside isRefusedByPeers", reads.single().range.first in helper)
        val uses = sync.calls("isRefusedByPeers")
        assertEquals("isRefusedByPeers is used somewhere besides the hold's observation", 1, uses.size)
        val observed = sync.calls("refusalHold.observe").singleOrNull()
            ?: throw AssertionError("the hold observes the core's answer in more than one place, or nowhere")
        assertTrue("isRefusedByPeers is used outside the hold's observation", uses.single().range.first in observed.range)
    }

    @Test fun `the hold is the service's own, for this session, on a clock that only moves forward`() {
        val made = sync.calls("app.aroundtheblock.wallet.core.sync.RefusalHold")
        assertEquals("the service must make exactly one hold", 1, made.size)
        assertEquals(
            "the hold must read awake time (a wall clock set forward would pass it at once, and time asleep heard no peer)",
            listOf("clock = android.os.SystemClock::uptimeMillis"), made.single().arguments,
        )
        assertTrue(
            "the hold is not an instance field of the service",
            Regex("""\n    private val refusalHold = app\.aroundtheblock\.wallet\.core\.sync\.RefusalHold\(""").containsMatchIn(sync.code),
        )
    }
}
