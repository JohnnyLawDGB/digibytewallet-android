package app.aroundtheblock.wallet.ui.digistamp

import app.aroundtheblock.wallet.core.digistamp.DigistampUris
import app.aroundtheblock.wallet.ui.digistamp.DigistampNavigation.BLOCK
import app.aroundtheblock.wallet.ui.digistamp.DigistampNavigation.LOAD_HERE
import app.aroundtheblock.wallet.ui.digistamp.DigistampNavigation.OPEN_IN_BROWSER
import app.aroundtheblock.wallet.ui.digistamp.DigistampNavigation.WALLET_SCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Market's web view stays inside the DigiStamp origin, and one pure policy decides every
 * navigation it is asked about:
 *
 *  - a main-frame page of the origin loads in the view;
 *  - a wallet link opens the wallet screen it names, and an approved HTTPS link opens in the
 *    browser — both only when the page asking is a DigiStamp page;
 *  - everything else is blocked: every sub-frame navigation, and any other main-frame target.
 *
 * And at commit time, a main-frame page outside the origin sends the view home. That is the only
 * check that sees a form POST: the platform asks the client about GET navigations only.
 */
class DigistampNavigationPolicyTest {

    private val page = "https://assets.digistamp.co/marketplace"
    private val signIn = "digiid://assets.digistamp.co/api/auth/digiid/callback?x=0b654336c3fcc835a5f325a8f3d4dc77"
    private val pay = "digibyte:DBXu2kgc3xtvCUWFcxFE3r9hEYgmuaaCyD?amount=1"

    private fun mainFrame(target: String, shown: String? = page) =
        DigistampNavigationPolicy.decide(target, isMainFrame = true, shownUrl = shown)

    private fun subFrame(target: String, shown: String? = page) =
        DigistampNavigationPolicy.decide(target, isMainFrame = false, shownUrl = shown)

    // ---- main frame -------------------------------------------------------------------------

    @Test fun `a DigiStamp page loads in the view`() {
        listOf(
            "https://assets.digistamp.co/",
            "https://assets.digistamp.co/explorer/La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC",
            "https://ASSETS.digistamp.co/help",
            "https://assets.digistamp.co:443/collections",
        ).forEach { assertEquals(it, LOAD_HERE, mainFrame(it)) }
    }

    @Test fun `a wallet link from a DigiStamp page opens the wallet screen`() {
        assertEquals(WALLET_SCREEN, mainFrame(signIn))
        assertEquals(WALLET_SCREEN, mainFrame(pay))
    }

    @Test fun `an https link to another site opens in the browser`() {
        listOf(
            "https://digibyte.org/",
            "https://t.me/DigiByteCoin",
            "https://github.com/JohnnyLawDGB/digibytewallet-android/releases",
            "https://assets.digistamp.co.other.example/",
            "https://cdn.assets.digistamp.co/",
            "https://assets.digistamp.co:8443/",
        ).forEach { assertEquals(it, OPEN_IN_BROWSER, mainFrame(it)) }
    }

    /** Neither in the origin nor an HTTPS link the safe-URL helper accepts: nothing happens. */
    @Test fun `any other main-frame target is blocked`() {
        listOf(
            "http://assets.digistamp.co/marketplace",
            "http://digibyte.org/",
            "javascript:void(0)",
            "data:text/html,<p>x</p>",
            "file:///sdcard/Download/x.html",
            "content://media/external/file/1",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "market://details?id=app.aroundtheblock.wallet",
            "about:blank",
            "blob:https://assets.digistamp.co/5b2f7c4e",
            "mailto:someone@example.com",
            "tel:+15555550100",
            "digiscope://hub/thread/1",
            "https://other.example/" + "a".repeat(2100),
            "https://other.example/a\u0000b",
            "",
            "not a url",
        ).forEach { assertEquals(it.take(64), BLOCK, mainFrame(it)) }
    }

    // ---- sub-frames -------------------------------------------------------------------------

    /** A frame inside the page is never a reason to open a wallet screen, another app, or a browser. */
    @Test fun `every sub-frame navigation is blocked`() {
        listOf(
            "https://assets.digistamp.co/embedded",
            "https://other.example/",
            signIn,
            pay,
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=app.aroundtheblock.wallet",
            "javascript:void(0)",
        ).forEach { assertEquals(it, BLOCK, subFrame(it)) }
    }

    // ---- the page shown ---------------------------------------------------------------------

    /**
     * Only a DigiStamp page may ask for a wallet screen or the browser. A page from another
     * origin that committed (until the backstop below takes it down) is not one, and neither is
     * an empty view. Either may still load a DigiStamp page.
     */
    @Test fun `a page outside the origin can ask for nothing but a DigiStamp page`() {
        listOf("https://other.example/after-post", "http://assets.digistamp.co/", "about:blank", null).forEach { shown ->
            assertEquals("$shown", BLOCK, mainFrame(signIn, shown))
            assertEquals("$shown", BLOCK, mainFrame(pay, shown))
            assertEquals("$shown", BLOCK, mainFrame("https://other.example/next", shown))
            assertEquals("$shown", LOAD_HERE, mainFrame("https://assets.digistamp.co/", shown))
        }
    }

    // ---- the commit-time backstop -----------------------------------------------------------

    @Test fun `a committed page outside the origin sends the view home`() {
        listOf(
            "https://other.example/after-post",
            "http://assets.digistamp.co/",
            "https://assets.digistamp.co.other.example/",
            "about:blank",
            "data:text/html,x",
            "",
            null,
        ).forEach { assertTrue("$it", DigistampNavigationPolicy.mustReturnHome(it)) }
    }

    @Test fun `a committed DigiStamp page stays`() {
        listOf(
            "https://assets.digistamp.co/",
            "https://assets.digistamp.co/marketplace?category=art",
            "https://assets.digistamp.co/explorer/La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC#top",
        ).forEach { assertFalse(it, DigistampNavigationPolicy.mustReturnHome(it)) }
    }

    /** Where the backstop sends the view must itself stay, or the view would never settle. */
    @Test fun `home is a page the backstop keeps`() {
        assertFalse(DigistampNavigationPolicy.mustReturnHome(DigistampUris.BASE_URL))
        assertEquals(LOAD_HERE, mainFrame(DigistampUris.BASE_URL, shown = null))
    }
}
