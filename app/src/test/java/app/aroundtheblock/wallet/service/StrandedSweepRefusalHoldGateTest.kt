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
 * gives its coins back. Both observations must come from one peer manager: the core keeps relay evidence
 * per manager and a rebuilt one starts with none.
 *
 * The rule is [app.aroundtheblock.wallet.core.sync.RefusalHold], tested in `core` (`RefusalHoldTest`);
 * that every manager the bridge builds is a new generation is `PeerManagerGenerationGateTest`. This
 * pins that the sweep's removal input is the hold's verdict on the core's answer, that the core's
 * answer reaches the sweep by no other way, and that the hold is the service's own, for this session,
 * on a clock that only moves forward, naming the manager each answer came from by its generation.
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
        assertEquals(
            "refusedByPeers must be the hold's verdict on the core's answer, read inside the hold",
            "refusalHold.observe(txid) { isRefusedByPeers(txid) }", KotlinSourceGate.squeeze(sync.code.substring(refused)),
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
        val answer = sync.trailingBlock(observed)
            ?: throw AssertionError("the hold is handed an answer read before it, not the read itself")
        assertTrue("isRefusedByPeers is used outside the hold's observation", uses.single().range.first in answer)
    }

    @Test fun `the hold is the service's own, for this session, on a clock that only moves forward`() {
        val made = sync.calls("app.aroundtheblock.wallet.core.sync.RefusalHold")
        assertEquals("the service must make exactly one hold", 1, made.size)
        val args = made.single().arguments
        assertEquals("the hold takes a clock and the peer manager's generation, nothing else", 2, args.size)
        assertEquals(
            "the hold must read awake time (a wall clock set forward would pass it at once, and time asleep heard no peer)",
            "clock = android.os.SystemClock::uptimeMillis", args[0],
        )
        assertEquals(
            "the hold must name each answer's peer manager by the bridge's generation, an unreadable one as unknown",
            "manager = { runCatching { NativeBridge.peerManagerGeneration() }.getOrNull() }", args[1],
        )
        assertTrue(
            "the hold is not an instance field of the service",
            Regex("""\n    private val refusalHold = app\.aroundtheblock\.wallet\.core\.sync\.RefusalHold\(""").containsMatchIn(sync.code),
        )
    }
}
