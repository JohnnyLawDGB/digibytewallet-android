package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the sync service never resolves the own-node host name itself. Both places that
 * need the node's address (pinning it, and the health poll) ask [OwnNodeAddress] with the user's
 * Tor setting, which refuses a host name while Tor is on rather than handing it to the device's
 * resolver. What [OwnNodeAddress] answers is covered by `OwnNodeAddressTest`.
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

    @Test fun `both own-node sites ask for the address with the Tor setting`() {
        for (site in listOf("injectCustomNode", "refreshOwnNodeHealth")) {
            val asks = sync.calls("OwnNodeAddress.resolve", body(site))
            assertEquals("$site does not ask OwnNodeAddress exactly once", 1, asks.size)
            assertEquals("$site does not pass the user's Tor setting",
                "torManager.isEnabled", asks.single().named["torEnabled"])
        }
    }
}
