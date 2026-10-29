package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the sync service never resolves the own-node host name itself. The pin asks
 * [OwnNodeAddress] with the user's Tor setting, which refuses a host name while Tor is on rather
 * than handing it to the device's resolver; the health poll reads the address the pin used. What [OwnNodeAddress] answers is covered by `OwnNodeAddressTest`.
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
}
