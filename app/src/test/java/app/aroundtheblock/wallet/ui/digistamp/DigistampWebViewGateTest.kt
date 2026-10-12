package app.aroundtheblock.wallet.ui.digistamp

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import app.aroundtheblock.wallet.ui.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate: the Market's web view is the one the tests drive. Its navigations go through
 * [DigistampWebViewClient] (the only WebViewClient in the app), and its settings are made in one
 * place, [DigistampWebViewHost.applyLeastSettings], which every WebView the app builds receives.
 */
class DigistampWebViewGateTest {

    private val screenPath = "app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampScreen.kt"
    private val hostPath = "app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampWebViewHost.kt"
    private val clientPath = "app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampWebViewClient.kt"

    private val sources = ProductionSources.files.map { (path, text) -> path to KotlinSourceGate.of(text) }
    private fun source(path: String) = sources.single { it.first == path }.second

    /** Every place in production code that matches [pattern], as `path:line`. */
    private fun found(pattern: Regex): List<String> = sources.flatMap { (path, gate) ->
        pattern.findAll(gate.code).map { "$path:${ProductionSources.lineOf(gate.code, it.range.first)}" }.toList()
    }

    /** The body of `fun [name]` in [gate]: past its parameter list, whose defaults may hold brackets. */
    private fun body(gate: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(gate.code) ?: error("no fun $name")
        var depth = 0
        var at = declared.range.last
        do {
            when (gate.code[at]) {
                '(' -> depth++
                ')' -> depth--
            }
            at++
        } while (depth > 0)
        return gate.blockAt(gate.code.indexOf('{', at)) ?: error("unbalanced body of $name")
    }

    @Test fun `the Market's view is driven by the navigation client`() {
        val screen = source(screenPath)
        val assigned = Regex("""\.\s*webViewClient\s*=\s*DigistampWebViewClient\s*\(""").findAll(screen.code).count()
        assertEquals("DigistampScreen does not hand its view to DigistampWebViewClient exactly once", 1, assigned)
    }

    @Test fun `no other WebViewClient exists in the app`() {
        val clients = found(Regex("""(?<!\w)WebViewClient\s*\(""")).map { it.substringBefore(':') }.distinct()
        assertEquals("a WebViewClient outside DigistampWebViewClient", listOf(clientPath), clients)
    }

    /** GUARD (no red exists: the view has always had a single place for its settings). */
    @Test fun `GUARD every WebView the app builds receives the least settings, and nothing else sets one`() {
        val built = found(Regex("""(?<!\w)WebView\s*\("""))
        assertEquals("a WebView built anywhere but DigistampWebViewHost.obtain", 1, built.size)
        assertEquals(hostPath, built.single().substringBefore(':'))

        val host = source(hostPath)
        val obtain = body(host, "obtain")
        val construction = Regex("""(?<!\w)WebView\s*\(""").find(host.code)!!.range.first
        assertTrue("the WebView is built outside obtain", construction in obtain)
        val applied = host.calls("applyLeastSettings", obtain)
        assertEquals("obtain does not apply the least settings exactly once", 1, applied.size)
        val handedOut = host.calls("configure", obtain).single().range.first
        assertTrue("the view is handed out before its settings are made", applied.single().range.first in construction..handedOut)

        val setters = Regex(
            """(?<!\w)(setJavaScriptEnabled|javaScriptEnabled|setDomStorageEnabled|domStorageEnabled|""" +
                """setAllowFileAccess|allowFileAccess|setAllowContentAccess|allowContentAccess|""" +
                """allowFileAccessFromFileURLs|setAllowFileAccessFromFileURLs|""" +
                """allowUniversalAccessFromFileURLs|setAllowUniversalAccessFromFileURLs|""" +
                """javaScriptCanOpenWindowsAutomatically|setJavaScriptCanOpenWindowsAutomatically|""" +
                """setGeolocationEnabled|mixedContentMode|setMixedContentMode|setSupportMultipleWindows|""" +
                """databaseEnabled|setDatabaseEnabled|safeBrowsingEnabled|setSafeBrowsingEnabled|""" +
                """setWebContentsDebuggingEnabled)(?!\w)"""
        )
        val settingsBody = body(host, "applyLeastSettings")
        val outside = sources.flatMap { (path, gate) ->
            setters.findAll(gate.code)
                .filterNot { path == hostPath && it.range.first in settingsBody }
                .map { "$path:${ProductionSources.lineOf(gate.code, it.range.first)} ${it.value}" }.toList()
        }
        assertEquals("a WebView setting made outside applyLeastSettings", emptyList<String>(), outside)
    }

    /** GUARD (none has ever existed): a WebChromeClient is where a page would be granted a camera, files or a location. */
    @Test fun `GUARD nothing gives the page a WebChromeClient`() {
        assertEquals(emptyList<String>(), found(Regex("""(?<!\w)WebChromeClient(?!\w)""")))
    }
}
