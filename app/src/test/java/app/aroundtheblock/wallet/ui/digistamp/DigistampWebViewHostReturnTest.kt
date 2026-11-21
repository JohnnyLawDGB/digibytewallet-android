package app.aroundtheblock.wallet.ui.digistamp

import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MARKET-SIGNIN-ID-HOST: what the Market does when the user comes back from a wallet screen
 * ([DigistampWebViewHost.onReturnFromWalletScreen]), against a view and a history that stand in
 * for the platform's.
 *
 * [DigistampNavigationPolicyTest] holds the rule as a pure function. This holds the step that
 * APPLIES it: a screen that reloaded whatever page it showed would pass the policy's tests and
 * still discard the sign-in page's pending Digi-ID request, leaving the approval unclaimed.
 */
class DigistampWebViewHostReturnTest {

    private val signInPage = "https://id.digistamp.co/id/signin?site=assets&state=abc&next=%2F"
    private val marketPage = "https://assets.digistamp.co/marketplace"

    @After fun tearDown() {
        DigistampWebViewHost.reloadOnReturn = false
    }

    /** A view whose committed page is [committed]; its getUrl() reports [reported]. */
    private fun view(committed: String?, reported: String? = committed): WebView {
        val item = committed?.let { address -> mockk<WebHistoryItem> { every { url } returns address } }
        val history = mockk<WebBackForwardList> { every { currentItem } returns item }
        return mockk(relaxed = true) {
            every { copyBackForwardList() } returns history
            every { url } returns reported
        }
    }

    @Test fun `returning to a sign-in page does not reload it`() {
        val shown = view(signInPage)
        DigistampWebViewHost.reloadOnReturn = true

        DigistampWebViewHost.onReturnFromWalletScreen(shown)

        verify(exactly = 0) { shown.reload() }
        verify(exactly = 0) { shown.loadUrl(any()) }
        assertFalse("the mark is cleared", DigistampWebViewHost.reloadOnReturn)
    }

    @Test fun `returning to a marketplace page reloads it once`() {
        val shown = view(marketPage)
        DigistampWebViewHost.reloadOnReturn = true

        DigistampWebViewHost.onReturnFromWalletScreen(shown)

        verify(exactly = 1) { shown.reload() }
        assertFalse("the mark is cleared", DigistampWebViewHost.reloadOnReturn)
    }

    /** The committed page decides, not the URL the view reports while another load is pending. */
    @Test fun `the committed page decides, not the reported one`() {
        val onSignIn = view(committed = signInPage, reported = marketPage)
        DigistampWebViewHost.reloadOnReturn = true
        DigistampWebViewHost.onReturnFromWalletScreen(onSignIn)
        verify(exactly = 0) { onSignIn.reload() }

        val onMarket = view(committed = marketPage, reported = signInPage)
        DigistampWebViewHost.reloadOnReturn = true
        DigistampWebViewHost.onReturnFromWalletScreen(onMarket)
        verify(exactly = 1) { onMarket.reload() }
    }

    @Test fun `a view with nothing committed is reloaded`() {
        val empty = view(committed = null)
        DigistampWebViewHost.reloadOnReturn = true

        DigistampWebViewHost.onReturnFromWalletScreen(empty)

        verify(exactly = 1) { empty.reload() }
        assertFalse(DigistampWebViewHost.reloadOnReturn)
    }

    @Test fun `a visit that is not a return from a wallet screen reloads nothing`() {
        val shown = view(marketPage)
        DigistampWebViewHost.reloadOnReturn = false

        DigistampWebViewHost.onReturnFromWalletScreen(shown)

        verify(exactly = 0) { shown.reload() }
        verify(exactly = 0) { shown.copyBackForwardList() }
    }

    @Test fun `with no view yet the mark is still cleared`() {
        DigistampWebViewHost.reloadOnReturn = true
        DigistampWebViewHost.onReturnFromWalletScreen(null)
        assertFalse(DigistampWebViewHost.reloadOnReturn)
    }

    /** The screen delegates the whole step: it neither reloads nor clears the mark itself. */
    @Test fun `the screen leaves the return step to the host`() {
        val screen = java.io.File("src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampScreen.kt").readText()
        assertTrue(screen.contains("DigistampWebViewHost.onReturnFromWalletScreen(webView)"))
        assertFalse("the screen reloads the view itself", Regex("""\.reload\(\)""").containsMatchIn(screen))
        assertFalse("the screen clears the mark itself", screen.contains("reloadOnReturn = false"))
    }
}
