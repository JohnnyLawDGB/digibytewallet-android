package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MainActivity logs the host of a Digi-ID deep link when it captures one. A deep link can come from
 * any app or web page, and `Uri.getHost()` decodes percent escapes, so `digiid://a%0Ab/…` has the
 * host "a\nb". What reaches the log line holds printable ASCII only — the range `DigiIdRequest.parse`
 * accepts in an authority — so it cannot end the line or start another the app never wrote.
 */
class DigiIdDeepLinkLogTest {

    @Test
    fun `a line break in the host does not reach the log line`() {
        val logged = DigiIdDeepLinkLog.host("site.example\nI/MainActivity: Digi-ID approved")!!
        assertFalse("a line break reached the log line", logged.contains('\n'))
        assertEquals("site.example?I/MainActivity:?Digi-ID?approved", logged)
    }

    @Test
    fun `every character outside printable ASCII is written as a question mark`() {
        val outside = (0x00..0x20) + (0x7f..0x9f) + listOf(0x2028, 0x2029, 0x200b, 0xfeff)
        for (code in outside) {
            val logged = DigiIdDeepLinkLog.host("a${code.toChar()}b")!!
            val codePoints = logged.map { "U+%04X".format(it.code) }
            assertEquals("U+%04X was logged as %s".format(code, codePoints), "a?b", logged)
        }
    }

    @Test
    fun `an ordinary host is logged exactly as it is`() {
        for (host in listOf("digiscope.me", "login.example.org", "xn--bcher-kva.example", "192.0.2.7")) {
            assertEquals(host, DigiIdDeepLinkLog.host(host))
        }
    }

    @Test
    fun `no host is no host`() {
        assertNull(DigiIdDeepLinkLog.host(null))
    }
}
