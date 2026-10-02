package io.digibyte.ui.digistamp

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: while the user's Tor setting is on, the Market screen says it is not routed through
 * Tor BEFORE its WebView loads anything, and loads only after the user continues. The WebView's
 * network stack does not use the app's proxy, so the site sees the device's address.
 *
 * The screen entry point decides; the WebView lives in a separate composable that the entry point
 * reaches only past the notice. What the notice decides is covered by `MarketTorNoticeTest`.
 */
class MarketTorNoticeGateTest {

    private val screen = KotlinSourceGate.of(File("src/main/java/io/digibyte/ui/digistamp/DigistampScreen.kt").readText())

    /** The body of the function named [name]: past its parameter list, whose defaults may hold braces. */
    private fun body(name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(screen.code) ?: error("no fun $name")
        var depth = 0
        var at = declared.range.last
        do {
            when (screen.code[at]) {
                '(' -> depth++
                ')' -> depth--
            }
            at++
        } while (depth > 0)
        val open = screen.code.indexOf('{', at)
        return screen.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `the screen entry point builds no WebView itself`() {
        val entry = body("DigistampScreen")
        assertTrue("DigistampScreen builds the WebView itself", screen.calls("AndroidView", entry).isEmpty())
        assertTrue("DigistampScreen obtains the WebView itself", screen.calls("DigistampWebViewHost.obtain", entry).isEmpty())
    }

    @Test fun `the web content is reached only past the Tor notice`() {
        val entry = body("DigistampScreen")
        val asks = screen.calls("MarketTorNotice.mustAsk", entry)
        assertEquals("DigistampScreen does not ask the Tor notice exactly once", 1, asks.size)
        val content = screen.calls("DigistampWebContent", entry)
        assertEquals("DigistampScreen does not show the web content exactly once", 1, content.size)
        assertTrue("the web content is reached before the notice is asked", asks.single().range.first < content.single().range.first)
        val notice = screen.calls("MarketTorNoticeDialog", entry)
        assertEquals("DigistampScreen does not show the notice", 1, notice.size)
        val noticeBranch = screen.enclosingBlock(notice.single().range.first) ?: error("the notice is not in a branch")
        assertTrue("the web content is shown in the same branch as the notice", content.single().range.first !in noticeBranch)
    }

    @Test fun `the WebView is built only in the web content`() {
        val content = body("DigistampWebContent")
        assertEquals(1, screen.calls("AndroidView", content).size)
        assertEquals("the WebView is built outside the web content", 1, screen.calls("AndroidView").size)
    }
}
