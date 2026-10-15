package app.aroundtheblock.wallet.ui.digistamp

import android.net.Uri
import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import app.aroundtheblock.wallet.core.digistamp.DigistampUris
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client that drives the Market's web view, against a view, requests and a history that
 * stand in for the platform's.
 *
 * Asked about a navigation, it does what [DigistampNavigationPolicy] decides — judged against the
 * page the view has COMMITTED, read from its history. `WebView.getUrl()` would not do: while a
 * load the app started is pending it reports the URL being loaded, not the page still on screen.
 *
 * When a main-frame page commits outside the origin it stops the view and loads the home page.
 * The platform never asks the client about a POST, so this is the check that keeps a form POST
 * from leaving a page of another origin in the view. It listens on both commit callbacks: the
 * page-started callback comes at commit for navigations a page starts, the history callback for
 * every committed navigation. Once the home page has committed, the history is cleared, so Back
 * leaves the Market instead of walking into the page that was sent away (and bouncing home again).
 */
class DigistampWebViewClientTest {

    private val home = DigistampUris.BASE_URL
    private val page = "https://assets.digistamp.co/marketplace"
    private val outside = "https://other.example/after-post"
    private val signIn = "digiid://assets.digistamp.co/api/auth/digiid/callback?x=0b654336c3fcc835a5f325a8f3d4dc77"

    private val walletScreens = mutableListOf<String>()
    private val browser = mutableListOf<String>()
    private val loading = mutableListOf<Boolean>()

    private val client = DigistampWebViewClient(
        walletScreen = { walletScreens += it.toString() },
        openInBrowser = { browser += it },
        onLoading = { loading += it },
    )

    /** A view whose committed page is [committed]; [reported] is what its getUrl() says. */
    private fun view(committed: String?, reported: String? = committed): WebView {
        val item = committed?.let { address -> mockk<WebHistoryItem> { every { url } returns address } }
        val history = mockk<WebBackForwardList> { every { currentItem } returns item }
        return mockk(relaxed = true) {
            every { copyBackForwardList() } returns history
            every { url } returns reported
        }
    }

    private fun request(target: String, mainFrame: Boolean): WebResourceRequest {
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns target
            every { scheme } returns target.substringBefore(':', "").ifEmpty { null }
        }
        return mockk {
            every { url } returns uri
            every { isForMainFrame } returns mainFrame
            every { method } returns "GET"
            every { isRedirect } returns false
            every { hasGesture() } returns true
            every { requestHeaders } returns emptyMap()
        }
    }

    private fun ask(target: String, mainFrame: Boolean, view: WebView = view(page)) =
        client.shouldOverrideUrlLoading(view, request(target, mainFrame))

    // ---- what the client does with a navigation ---------------------------------------------

    @Test fun `a DigiStamp page loads in place`() {
        assertEquals(false, ask("https://assets.digistamp.co/explorer", mainFrame = true))
        assertTrue(walletScreens.isEmpty() && browser.isEmpty())
    }

    @Test fun `a main-frame wallet link from a DigiStamp page opens its screen`() {
        assertEquals(true, ask(signIn, mainFrame = true))
        assertEquals(listOf(signIn), walletScreens)
    }

    @Test fun `an https link to another site goes to the browser`() {
        assertEquals(true, ask("https://digibyte.org/", mainFrame = true))
        assertEquals(listOf("https://digibyte.org/"), browser)
    }

    @Test fun `a sub-frame wallet link opens no wallet screen`() {
        assertEquals(true, ask(signIn, mainFrame = false))
        assertEquals(emptyList<String>(), walletScreens)
    }

    @Test fun `a sub-frame link to another app is not handed out`() {
        assertEquals(true, ask("intent://scan/#Intent;scheme=zxing;end", mainFrame = false))
        assertEquals(true, ask("market://details?id=app.aroundtheblock.wallet", mainFrame = false))
        assertEquals(emptyList<String>(), browser)
    }

    @Test fun `a main-frame link that is not approved https is neither loaded nor handed out`() {
        listOf("intent://scan/#Intent;scheme=zxing;end", "http://digibyte.org/", "javascript:void(0)", "file:///sdcard/x.html")
            .forEach { assertEquals(it, true, ask(it, mainFrame = true)) }
        assertEquals(emptyList<String>(), browser)
        assertEquals(emptyList<String>(), walletScreens)
    }

    /** The page still on screen is the one asking, even while the view reports the home page it is loading. */
    @Test fun `a page outside the origin cannot ask for a wallet screen while the view goes home`() {
        val leaving = view(committed = outside, reported = home)
        assertEquals(true, ask(signIn, mainFrame = true, view = leaving))
        assertEquals(true, ask("https://other.example/next", mainFrame = true, view = leaving))
        assertEquals(emptyList<String>(), walletScreens)
        assertEquals(emptyList<String>(), browser)
    }

    // ---- the commit-time backstop -----------------------------------------------------------

    @Test fun `a page outside the origin that commits is stopped and the view goes home`() {
        val v = view(page)
        client.onPageStarted(v, outside, null)
        verify(exactly = 1) { v.stopLoading() }
        verify(exactly = 1) { v.loadUrl(home) }
    }

    /** A navigation the app starts, Back and Forward included, is reported at commit to the history callback. */
    @Test fun `a commit reported only to the history callback is sent home too`() {
        val v = view(page)
        client.doUpdateVisitedHistory(v, outside, false)
        verify(exactly = 1) { v.stopLoading() }
        verify(exactly = 1) { v.loadUrl(home) }
    }

    @Test fun `a DigiStamp page that commits is left alone`() {
        val v = view(page)
        client.onPageStarted(v, page, null)
        client.doUpdateVisitedHistory(v, page, false)
        client.onPageFinished(v, page)
        verify(exactly = 0) { v.stopLoading() }
        verify(exactly = 0) { v.loadUrl(any()) }
        verify(exactly = 0) { v.clearHistory() }
        assertEquals(listOf(true, false), loading)
    }

    @Test fun `once home has committed after a return, Back cannot walk into the page that was sent away`() {
        val v = view(page)
        client.onPageStarted(v, outside, null)
        client.doUpdateVisitedHistory(v, outside, false)
        verify(exactly = 0) { v.clearHistory() }   // not while that page is still the current entry

        client.onPageStarted(v, home, null)
        client.doUpdateVisitedHistory(v, home, false)
        verify(exactly = 1) { v.clearHistory() }

        client.doUpdateVisitedHistory(v, page, false)   // later visits keep their history
        verify(exactly = 1) { v.clearHistory() }
    }
}
