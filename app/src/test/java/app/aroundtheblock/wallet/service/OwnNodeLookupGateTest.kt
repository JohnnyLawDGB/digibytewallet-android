package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the sync service never resolves the own-node host name itself. The pin asks
 * [OwnNodeAddress] with the user's Tor setting, which refuses a host name while Tor is on rather
 * than handing it to the device's resolver; the health poll reads the address the pin used, and
 * with no pin it still asks the core, with status 0, so the node reads dark. What
 * [OwnNodeAddress] answers is covered by `OwnNodeAddressTest`.
 */
class OwnNodeLookupGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())

    private fun body(name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(sync.code) ?: error("no fun $name")
        val open = sync.code.indexOf('{', declared.range.last)
        return sync.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `the sync service makes no name lookup of its own`() {
        for (callee in listOf("InetAddress.getAllByName", "InetAddress.getByName", "getAllByName", "getByName")) {
            assertTrue("SyncService calls $callee", sync.calls(callee).isEmpty())
        }
    }

    @Test fun `the pin asks for the address with the Tor setting`() {
        val asks = sync.calls("OwnNodeAddress.resolve", body("injectCustomNode"))
        assertEquals("injectCustomNode does not ask OwnNodeAddress exactly once", 1, asks.size)
        assertEquals("injectCustomNode does not pass the user's Tor setting",
            "torManager.isEnabled", asks.single().named["torEnabled"])
    }

    // The health poll reads the address the pin used, as iOS does: a second lookup could land on
    // another address of the same host name and reset the core's health record each poll.
    @Test fun `the health poll makes no lookup and reads the pinned address`() {
        val poll = body("refreshOwnNodeHealth")
        assertTrue("refreshOwnNodeHealth looks the node up itself", sync.calls("OwnNodeAddress.resolve", poll).isEmpty())
        assertTrue("refreshOwnNodeHealth does not read ownNodePinnedAddr",
            Regex("""(?<!\w)ownNodePinnedAddr(?!\w)""").containsMatchIn(sync.code.substring(poll)))
    }

    // No pin (the address did not parse, did not resolve, or is a host name while Tor is on) is
    // status 0, which the core reads as dark, as iOS's OwnNodeHealthPoll.reading. An early return
    // on no pin would leave the last reading standing instead, a divergence.
    @Test fun `with no pin the health poll feeds status 0 and reads dark`() {
        val poll = body("refreshOwnNodeHealth")
        val asked = sync.calls("NativeBridge.compactFilterPeerStatus", poll)
        assertEquals("refreshOwnNodeHealth does not ask compactFilterPeerStatus exactly once", 1, asked.size)
        val onPin = sync.branchesOn("pinned != null", poll).filter { asked.single().range.first in it }
        assertEquals("compactFilterPeerStatus is not asked only under `if (pinned != null)`", 1, onPin.size)
        val rest = sync.code.substring(onPin.single().last + 1).substringBefore('\n')
        assertTrue("no pin is not status 0: `$rest`", rest.trim().matches(Regex("""else\s+0""")))
        val head = sync.code.substring(poll.first, onPin.single().first)
        assertTrue("the status is not `val status = if (pinned != null) …`",
            Regex("""(?<!\w)val\s+status\s*=\s*if\s*\(\s*pinned\s*!=\s*null\s*\)\s*$""").containsMatchIn(head))

        val observed = sync.calls("NativeBridge.ownNodeHealthObserve", poll)
        assertEquals("refreshOwnNodeHealth does not call ownNodeHealthObserve exactly once", 1, observed.size)
        assertEquals("ownNodeHealthObserve is not fed the status", "status", observed.single().arguments[1])

        // The only way out before the core is asked is the own node being off (UNPAIRED).
        val off = sync.branchesOn("!CustomNodePrefs.isEnabled(this@SyncService)", poll)
        assertEquals("refreshOwnNodeHealth has no single `if (!CustomNodePrefs.isEnabled(…))`", 1, off.size)
        val returns = Regex("""(?<!\w)return(?!\w)""").findAll(sync.code.substring(0, poll.last + 1))
            .filter { it.range.first in poll }.toList()
        assertTrue("refreshOwnNodeHealth has no return (the gate is reading the wrong body)", returns.isNotEmpty())
        for (r in returns) {
            assertTrue("refreshOwnNodeHealth returns early outside the off branch (no pin must read dark)",
                r.range.first in off.single())
        }
        assertTrue("refreshOwnNodeHealth returns early on no pin",
            Regex("""pinned\s*\?:\s*return""").find(sync.code.substring(poll)) == null)
    }
}
