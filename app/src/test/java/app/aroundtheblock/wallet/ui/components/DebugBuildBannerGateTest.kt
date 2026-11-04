package app.aroundtheblock.wallet.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A debug build says so on every screen and on the launcher; a release build is unchanged.
 *
 * On the test phone a debug wallet's balance was read as the release wallet's (2026-11-03): the two
 * apps had the same name, icon and screens.
 */
class DebugBuildBannerGateTest {

    private val root = File("..").canonicalFile
    private fun src(path: String) = File(root, path).readText()

    @Test fun `the banner draws nothing unless the build is a debug build`() {
        val banner = src("app/src/main/java/app/aroundtheblock/wallet/ui/components/DebugBuildBanner.kt")
        assertTrue(banner.contains("if (!BuildConfig.DEBUG) return"))
        // No click handling: the pill must not take touches meant for the screen beneath.
        assertTrue(!banner.contains("clickable") && !banner.contains("pointerInput"))
    }

    @Test fun `MainActivity draws the banner above the whole navigation graph`() {
        val activity = src("app/src/main/java/app/aroundtheblock/wallet/MainActivity.kt")
        val nav = activity.indexOf("AppNavigation(")
        val banner = activity.indexOf("DebugBuildBanner(")
        assertTrue("banner missing", banner > 0)
        assertTrue("the banner is drawn after (above) AppNavigation", banner > nav && nav > 0)
    }

    @Test fun `a debug build never offers the release APK as its update`() {
        val prompt = src("app/src/sideload/java/app/aroundtheblock/wallet/update/SelfUpdatePrompt.kt")
        val guard = prompt.indexOf("if (BuildConfig.DEBUG) return")
        assertTrue("debug guard missing", guard > 0)
        assertTrue("the guard comes before the update check", guard < prompt.indexOf("UpdateChecker("))
    }

    @Test fun `only the debug source set renames the launcher label`() {
        assertTrue(src("app/src/debug/res/values/strings.xml").contains(">HoldFast DEBUG<"))
        assertTrue(src("app/src/main/res/values/strings.xml").contains("name=\"app_name\" translatable=\"false\">HoldFast<"))
    }
}
