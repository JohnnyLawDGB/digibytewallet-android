package app.aroundtheblock.wallet.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncNotificationTextTest {

    private fun fmt(app: String, wallet: String) = "$app · $wallet"

    private val en = object : NotificationStrings {
        override fun synced(peers: Int) = "Synced — $peers peers connected"
        override fun connecting() = "Connecting to peers…"
        override fun syncing(pct: Int, peers: Int) = "Syncing $pct% — $peers peers"
        override fun viaTor() = " · via Tor"
    }

    @Test fun `one wallet shows the app name only`() {
        assertEquals("HoldFast", notificationTitle("HoldFast", "Wallet 1", 1, ::fmt))
    }

    @Test fun `several wallets name the active one`() {
        assertEquals("HoldFast · Savings", notificationTitle("HoldFast", "Savings", 2, ::fmt))
    }

    @Test fun `the text follows the sync state`() {
        assertEquals(en.synced(3), notificationText(100, 3, false, en))
        assertEquals(en.connecting(), notificationText(0, 0, false, en))
        assertEquals(en.syncing(42, 2) + en.viaTor(), notificationText(42, 2, true, en))
    }

    @Test fun `the Tor suffix keeps its leading space in every locale`() {
        val files = File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings_wallet.xml") }.filter { it.isFile }
        assertEquals("13 locales expected", 13, files.size)
        val re = Regex("""<string name="sync_notif_via_tor">(.*?)</string>""")
        for (f in files) {
            val raw = re.find(f.readText())?.groupValues?.get(1)
            assertTrue("${f.parentFile!!.name}: sync_notif_via_tor missing", raw != null)
            assertTrue("${f.parentFile!!.name}: raw value must start with a quote and a space: $raw", raw!!.startsWith("\" "))
        }
    }

    @Test fun `no notification literal is left`() {
        val src = File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText()
        assertTrue("scan is blind", src.contains("notificationText"))
        for (lit in listOf("\"Synced — ", "\"Connecting to peers…", "\" · via Tor\"")) {
            assertFalse("literal left in SyncService.kt: $lit", src.contains(lit))
        }
    }
}
