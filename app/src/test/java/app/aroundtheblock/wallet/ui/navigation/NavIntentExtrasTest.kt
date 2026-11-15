package app.aroundtheblock.wallet.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NAV-DEEPLINK: no launching intent chooses a screen. The filter removes every Navigation extra
 * and nothing else. MainActivity applies it before anything reads the intent and before the
 * NavHost exists, and to every later intent before any listener sees it.
 */
class NavIntentExtrasTest {

    @Test
    fun `every Navigation extra is stripped and nothing else`() {
        val keys = setOf(
            "android-support-nav:controller:deepLinkIds",
            "android-support-nav:controller:deepLinkArgs",
            "android-support-nav:controller:deepLinkExtras",
            "android-support-nav:controller:deepLinkHandled",
            "android-support-nav:controller:deepLinkIntent",
            "android-support-nav:anything-a-later-version-adds",
            "app.aroundtheblock.wallet.STARTED_AFTER_WIPE",
            "digiid",
        )
        assertEquals(keys.filter { it.startsWith("android-support-nav:") }.toSet(), NavIntentExtras.keysToStrip(keys))
        assertTrue(NavIntentExtras.keysToStrip(setOf("x", "android-support-navx")).isEmpty())
    }

    /** MainActivity with comments removed, so a comment naming the call cannot stand in for it. */
    private val activity = File("src/main/java/app/aroundtheblock/wallet/MainActivity.kt").readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun body(signature: String): String {
        val start = activity.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return activity.substring(start, activity.indexOf("\n    }\n", start))
    }

    @Test
    fun `onCreate strips before anything reads the intent and before setContent`() {
        val onCreate = body("override fun onCreate(")
        // At method level (8 spaces): not inside a branch that may not run.
        val strip = onCreate.indexOf("\n        app.aroundtheblock.wallet.ui.navigation.NavIntentExtras.strip(intent)\n")
        assertTrue("onCreate must strip the launch intent, unconditionally", strip >= 0)
        for (reader in listOf("launch = intent", "handleDigiIdIntent(intent)", "setContent {")) {
            val at = onCreate.indexOf(reader)
            assertTrue("$reader not found in onCreate", at >= 0)
            assertTrue("strip must come before $reader", strip < at)
        }
    }

    @Test
    fun `onNewIntent strips before super`() {
        val onNewIntent = body("override fun onNewIntent(")
        val strip = onNewIntent.indexOf("\n        app.aroundtheblock.wallet.ui.navigation.NavIntentExtras.strip(intent)\n")
        val sup = onNewIntent.indexOf("super.onNewIntent(intent)")
        assertTrue("onNewIntent must strip", strip >= 0)
        assertTrue("strip must come before super.onNewIntent", sup > strip)
    }

    @Test
    fun `no destination declares a navigation deep link`() {
        // Stripping the extras leaves intent.data, which Navigation matches only against declared deep
        // links. None is declared, so no URI chooses a screen. A new one must come with its own lock review.
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { "navDeepLink" in it || Regex("deepLinks\\s*=").containsMatchIn(it) } }
            .map { it.path }.toList()
        assertTrue("navigation deep links declared in $offenders", offenders.isEmpty())
    }
}
