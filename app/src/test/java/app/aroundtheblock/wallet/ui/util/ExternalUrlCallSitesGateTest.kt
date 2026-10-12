package app.aroundtheblock.wallet.ui.util

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import app.aroundtheblock.wallet.ui.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate: every URL the app hands to another app goes through [openExternalUrl], the one
 * place that refuses any scheme but http(s), an over-long URL, or a control character before
 * anything is launched. No other file builds a view intent, opens a Custom Tab, parses an intent
 * from a URI, or asks Compose to open a URI (Compose's URI handler builds a view intent itself).
 */
class ExternalUrlCallSitesGateTest {

    private val helperPath = "app/src/main/java/app/aroundtheblock/wallet/ui/util/ExternalUrl.kt"

    /** Spellings in code (comments and the text of literals blanked) that build or open a view intent. */
    private val inCode = listOf(
        Regex("""(?<![\w$])ACTION_VIEW(?![\w$])""") to "a view intent",
        Regex("""(?<![\w$])CustomTabsIntent(?![\w$])""") to "a Custom Tab",
        Regex("""(?<![\w$])launchUrl\s*\(""") to "a Custom Tab",
        Regex("""(?<![\w$])(Local)?UriHandler(?![\w$])""") to "Compose's URI handler",
        Regex("""(?<![\w$])openUri\s*\(""") to "Compose's URI handler",
        Regex("""(?<![\w$])LinkAnnotation\s*\.\s*Url(?![\w$])""") to "a Compose text link",
        Regex("""(?<![\w$])parseUri\s*\(""") to "an intent read from a URI",
    )

    /** The same action written as a string. */
    private val inLiterals = Regex(""""android\.intent\.action\.VIEW"""")

    /** Each place in [text] that builds or opens a view intent, as `line: what`. */
    private fun viewIntents(text: String): List<String> {
        val gate = KotlinSourceGate.of(text)
        val hits = inCode.flatMap { (pattern, what) -> pattern.findAll(gate.code).map { it.range.first to what }.toList() } +
            inLiterals.findAll(gate.written).map { it.range.first to "a view intent" }.toList()
        return hits.sortedBy { it.first }.map { (at, what) -> "${ProductionSources.lineOf(text, at)}: $what" }.distinct()
    }

    @Test fun `no file but the safe-URL helper builds a view intent`() {
        val outside = ProductionSources.files
            .filter { (path, _) -> path != helperPath }
            .flatMap { (path, text) -> viewIntents(text).map { "$path:$it" } }
        assertEquals("view intents built outside openExternalUrl", emptyList<String>(), outside)
    }

    /** The bug report, the update download and the Market's browser hand-off each open through the helper. */
    @Test fun `the external links open through the helper`() {
        fun calls(path: String) = KotlinSourceGate.of(ProductionSources.files.single { it.first == path }.second)
            .calls("openExternalUrl")
            .map { it.arguments }

        val settings = calls("app/src/main/java/app/aroundtheblock/wallet/ui/settings/SettingsScreen.kt")
        assertTrue("the bug report does not open through the helper: $settings", settings.any { it.getOrNull(1)?.startsWith("buildBugReportUrl(") == true })

        val update = calls("app/src/sideload/java/app/aroundtheblock/wallet/ui/components/UpdateDialog.kt")
        assertTrue("the update download does not open through the helper: $update", update.any { it.getOrNull(1) == "update.downloadUrl" })

        val market = calls("app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampScreen.kt")
        assertEquals("the Market does not hand the browser its links through the helper", 1, market.size)
    }

    // ---- the gate's own reading ------------------------------------------------------------

    @Test fun `the reader finds each spelling, in code only`() {
        val sample = listOf(
            "// Intent.ACTION_VIEW in a comment is not a call site",
            "val note = \"ACTION_VIEW in a string is not one either\"",
            "context.startActivity(Intent(Intent.ACTION_VIEW, uri))",
            "android.content.Intent(",
            "    android.content.Intent.ACTION_VIEW,",
            "    android.net.Uri.parse(url))",
            "Intent(\"android.intent.action.VIEW\", uri)",
            "CustomTabsIntent.Builder().build().launchUrl(context, uri)",
            "LocalUriHandler.current.openUri(url)",
            "Intent.parseUri(raw, 0)",
            "val link = LinkAnnotation.Url(url)",
            "/* launchUrl(context, uri) */ val fine = openExternalUrl(context, url)",
        ).joinToString("\n")
        assertEquals(
            listOf(
                "3: a view intent", "5: a view intent", "7: a view intent", "8: a Custom Tab",
                "9: Compose's URI handler", "10: an intent read from a URI", "11: a Compose text link",
            ),
            viewIntents(sample),
        )
    }

    /** A gate that read nothing would pass. It reads every module and the flavour source sets. */
    @Test fun `the gate reads every production source set`() {
        val paths = ProductionSources.files.map { it.first }
        listOf(
            helperPath,
            "app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampScreen.kt",
            "app/src/sideload/java/app/aroundtheblock/wallet/ui/components/UpdateDialog.kt",
            "app/src/play/java/app/aroundtheblock/wallet/update/SelfUpdatePrompt.kt",
            "core/src/main/java/app/aroundtheblock/wallet/core/digistamp/DigistampUris.kt",
        ).forEach { assertTrue("not read: $it", it in paths) }
        assertTrue("no test source is read", paths.none { "/src/test/" in it || "/src/androidTest/" in it })
        assertTrue("the helper itself is found by the reader", viewIntents(ProductionSources.files.single { it.first == helperPath }.second).isNotEmpty())
    }
}
