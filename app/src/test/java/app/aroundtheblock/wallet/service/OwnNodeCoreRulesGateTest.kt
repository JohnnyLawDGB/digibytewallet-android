package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the sync service takes its two own-node decisions from the core (BROwnNode.h), as
 * iOS does, instead of deciding them in Kotlin.
 *
 *  - The health poll asks BROwnNodeHealthObserve (NativeBridge.ownNodeHealthObserve), which reads an
 *    own node that has gone 60 s without a connection as dark. The old Kotlin `when` over the raw
 *    status read it as connecting forever.
 *  - Outside injectCustomNode, nothing pins a peer or clears the pin slot unless
 *    BROwnNodeWatchdogMayPin (NativeBridge.ownNodeWatchdogMayPin) allows it, so the watchdogs never
 *    replace an own node's pin. The rule's values are checked by the host KAT (own_node_kat).
 */
class OwnNodeCoreRulesGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())
    private val mayPin = "NativeBridge.ownNodeWatchdogMayPin(ownNodePinned)"

    private fun body(name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(sync.code) ?: error("no fun $name")
        val open = sync.code.indexOf('{', declared.range.last)
        return sync.blockAt(open) ?: error("unbalanced body of $name")
    }

    private fun guarded(at: IntRange): Boolean = sync.branchesOn(mayPin).any { at.first in it && at.last in it }

    @Test fun `the health poll asks the core once per poll`() {
        val poll = body("refreshOwnNodeHealth")
        assertEquals(1, sync.calls("NativeBridge.ownNodeHealthObserve", poll).size)
        assertEquals(1, sync.calls("NativeBridge.compactFilterPeerStatus", poll).size)
    }

    @Test fun `no pin or clear outside the own node's own path skips the core rule`() {
        val own = body("injectCustomNode")
        val clears = sync.calls("NativeBridge.clearPinnedPeer").filter { it.range.first !in own }
        val pins = sync.calls("NativeBridge.setPinnedPeer").filter { it.range.first !in own }
        val picks = sync.calls("nextValidatedFilterPeer")
        assertTrue("the gate found no watchdog pin site; did a rename move them?", pins.isNotEmpty() && picks.isNotEmpty())
        for (c in clears) assertTrue("clearPinnedPeer outside injectCustomNode is not behind $mayPin: ${c.text}", guarded(c.range))
        for (p in picks) assertTrue("a canon peer is picked to pin without $mayPin: ${p.text}", guarded(p.range))
        for (p in pins) assertEquals("a watchdog pins something other than the peer picked behind $mayPin",
            "fp.first", p.arguments.first())
    }

    @Test fun `injectCustomNode records whether the own node holds the pin`() {
        val own = body("injectCustomNode")
        val code = sync.code.substring(own)
        assertEquals("every clear in injectCustomNode must record ownNodePinned = false",
            sync.calls("NativeBridge.clearPinnedPeer", own).size, Regex("""ownNodePinned\s*=\s*false""").findAll(code).count())
        assertEquals(1, Regex("""ownNodePinned\s*=\s*true""").findAll(code).count())
    }
}
