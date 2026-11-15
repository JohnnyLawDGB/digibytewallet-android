package app.aroundtheblock.wallet

import app.aroundtheblock.wallet.core.model.DigiIdRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSG-LEN: MainActivity takes a `digiid://` deep link in only when it is no longer than
 * [DigiIdRequest.MAX_URI_LENGTH]. A deep link can come from any app or web page, and nothing else bounds
 * its length before it is held as the pending request.
 */
class DigiIdDeepLinkTest {

    private val head = "digiid://site.example/callback?x="

    @Test
    fun `MSG-LEN a deep link at the cap is taken in`() {
        val atCap = head + "n".repeat(DigiIdRequest.MAX_URI_LENGTH - head.length)
        assertEquals(atCap, DigiIdDeepLink.accepted(atCap))
    }

    @Test
    fun `MSG-LEN a deep link over the cap is not taken in`() {
        val over = head + "n".repeat(DigiIdRequest.MAX_URI_LENGTH - head.length + 1)
        assertNull(DigiIdDeepLink.accepted(over))
        assertNull(DigiIdDeepLink.accepted(head + "n".repeat(0x10000)))
    }

    @Test
    fun `a link that is not Digi-ID is not taken in`() {
        assertNull(DigiIdDeepLink.accepted("https://site.example/callback?x=n"))
        assertNull(DigiIdDeepLink.accepted(null))
    }

    @Test
    fun `MainActivity takes deep links in through the cap`() {
        val src = listOf("src/main/java/app/aroundtheblock/wallet/MainActivity.kt",
            "app/src/main/java/app/aroundtheblock/wallet/MainActivity.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val body = src.substringAfter("private fun handleDigiIdIntent(").substringBefore("\n    }\n")
        assertTrue("handleDigiIdIntent does not take the link in through DigiIdDeepLink.accepted",
            body.contains("DigiIdDeepLink.accepted(intent?.data?.toString())"))
        assertTrue("handleDigiIdIntent checks the scheme itself, around the cap",
            !body.contains("startsWith(\"digiid://\")"))
    }
}
