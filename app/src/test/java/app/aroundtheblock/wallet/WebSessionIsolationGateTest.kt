package app.aroundtheblock.wallet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The launch isolation runs in DigiByteApp.onCreate before anything that could touch a WebView. */
class WebSessionIsolationGateTest {
    @Test fun `isolation precedes every web use in onCreate`() {
        val src = File("src/main/java/app/aroundtheblock/wallet/DigiByteApp.kt").readText()
        val body = src.substring(src.indexOf("override fun onCreate"))
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }
        val at = body.indexOf("WebSessionIsolation.isolateAtLaunch")
        assertTrue("isolation missing from onCreate", at > 0)
        listOf("CookieManager", "WebView", "DigistampWebViewHost", "WebStorage").forEach {
            val i = body.indexOf(it)
            assertTrue("$it used before the isolation", i < 0 || i > at)
        }
        assertTrue("housekeeping must come first so the active wallet resolves",
            body.indexOf("WalletHousekeeping") in 0 until at)
    }
}
