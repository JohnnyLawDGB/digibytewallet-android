package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEER-COUNT-HANDSHAKED: a peer count the wallet SHOWS counts only peers that have completed the
 * version/verack handshake, never peers that are still connecting.
 *
 * The core has two counts. `NativeBridge.getPeerCount()` includes a peer that is still dialling:
 * right for a recovery decision (a manager with a dial in flight is not dead), wrong on a screen,
 * and it is the one the home screen showed as "Connected · N peers" (found by the pre-publish suite,
 * 2026-11-21). `NativeBridge.getHandshakedPeerCount()` is the one to show. That the core counts
 * correctly is proven by the host KAT `peer_count_handshaked_kat`; NativeBridge cannot load in a
 * JVM test, so what this gate holds is the wiring: each place a count becomes something the user
 * reads takes it from the handshaken count.
 *
 * It reads source text, so it sees the three places named below and any new write to a
 * `_peerCount` flow. A new screen that reads `getPeerCount()` and shows it some other way is
 * beyond it.
 */
class ShownPeerCountSourceGateTest {

    private val app = "app/src/main/java/app/aroundtheblock/wallet"

    private fun source(path: String): String =
        ProductionSources.files.firstOrNull { it.first == path }?.second
            ?: error("$path is not a production source; the gate would be reading nothing")

    /** Every statement that writes the flow the screens collect, in [text]. */
    private fun peerCountWrites(text: String): List<String> =
        text.lines().map { it.trim() }.filter { Regex("""\b_peerCount\.value\s*=[^=]""").containsMatchIn(it) }

    /** The value a write publishes: the write itself, or the `val` it names. */
    private fun publishedFrom(text: String, write: String): String {
        val name = Regex("""_peerCount\.value\s*=\s*(\w+)\s*$""").find(write)?.groupValues?.get(1) ?: return write
        return text.lines().map { it.trim() }.firstOrNull { it.startsWith("val $name =") }
            ?: error("cannot find where `$name` comes from")
    }

    private fun assertShownCountIsHandshaken(path: String) {
        val text = source(path)
        val writes = peerCountWrites(text)
        assertTrue("$path no longer writes _peerCount; point this gate at what the screens collect", writes.isNotEmpty())
        for (write in writes) {
            val from = publishedFrom(text, write)
            assertTrue(
                "$path publishes a shown peer count that is not the handshaken count: `$from`",
                from.contains("NativeBridge.getHandshakedPeerCount()"),
            )
        }
    }

    @Test
    fun `the home screen, Send and Receive show the handshaken count`() =
        assertShownCountIsHandshaken("$app/ui/wallet/WalletViewModel.kt")

    @Test
    fun `Network Info shows the handshaken count`() =
        assertShownCountIsHandshaken("$app/ui/settings/SettingsViewModel.kt")

    @Test
    fun `the sync notification reads the handshaken count itself`() {
        val text = source("$app/service/SyncService.kt")
        // No caller hands it a count: the callers are recovery paths holding the other one.
        val declarations = Regex("""fun updateNotification\(([^)]*)\)""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals("updateNotification takes the progress only", listOf("progress: Float"), declarations)
        val body = text.substringAfter("fun updateNotification(progress: Float) {").substringBefore("\n    }")
        assertTrue(
            "updateNotification must build from the handshaken count",
            body.contains("NativeBridge.getHandshakedPeerCount()") && body.contains("buildNotification(progress, peerCount)"),
        )
        // The only other build is the first one, before any peer exists.
        val builds = Regex("""buildNotification\(([^)]*)\)""").findAll(text).map { it.groupValues[1] }
            .filterNot { it.startsWith("progress: Float") }.toList().sorted()
        assertEquals(listOf("progress = 0f, peerCount = 0", "progress, peerCount"), builds)
    }

    @Test
    fun `the gate's reader sees a connecting-inclusive count as a failure`() {
        val unfixed = """
            val peers = NativeBridge.getPeerCount()
            if (peers != _peerCount.value) _peerCount.value = peers
            _peerCount.value = runCatching { NativeBridge.getPeerCount() }.getOrDefault(0)
        """.trimIndent()
        val writes = peerCountWrites(unfixed)
        assertEquals(2, writes.size)
        assertTrue(writes.none { publishedFrom(unfixed, it).contains("getHandshakedPeerCount") })
    }
}
