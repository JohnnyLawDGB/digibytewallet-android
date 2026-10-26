package app.aroundtheblock.wallet.ui.digistamp

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.aroundtheblock.wallet.core.digistamp.DigistampUris

/**
 * Drives the Market's web view. Every navigation it is asked about goes through
 * [DigistampNavigationPolicy], and every main-frame page that commits is checked again.
 *
 * Its effects are passed in, so the whole client runs in a unit test (DigistampWebViewClientTest).
 * No method here calls the platform's own: those do nothing, and do not exist in a unit test.
 */
internal class DigistampWebViewClient(
    /** Opens the wallet screen a link names. The page supplies a destination, never a decision. */
    private val walletScreen: (Uri) -> Unit,
    /** Hands an approved link to the browser: openExternalUrl, in the app. */
    private val openInBrowser: (String) -> Unit,
    /** The spinner: on when a DigiStamp page starts, off when a page finishes. */
    private val onLoading: (Boolean) -> Unit,
) : WebViewClient() {

    /** The view was sent home; once home commits, the history behind it is dropped. */
    private var returningHome = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        // The page asking is the one the view has committed. getUrl() is not it: while a load the
        // app started is pending (the trip home below), getUrl() reports the URL being loaded.
        val shown = view.copyBackForwardList().currentItem?.url
        return when (DigistampNavigationPolicy.decide(target, request.isForMainFrame, shown)) {
            DigistampNavigation.LOAD_HERE -> false
            DigistampNavigation.WALLET_SCREEN -> {
                walletScreen(request.url)
                true
            }
            DigistampNavigation.OPEN_IN_BROWSER -> {
                openInBrowser(target)
                true
            }
            DigistampNavigation.BLOCK -> true
        }
    }

    // A navigation a page starts is reported here when it commits, a form POST included, which
    // never reaches shouldOverrideUrlLoading.
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (sendHomeIfOutside(view, url)) return
        onLoading(true)
    }

    // Every committed main-frame navigation is reported here, loads the app starts and Back included.
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (sendHomeIfOutside(view, url)) return
        if (returningHome) {
            // Home has committed. Without its history, Back leaves the Market instead of walking
            // into the page that was sent away, which would only send the view home again.
            returningHome = false
            view.clearHistory()
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onLoading(false)
    }

    /** The commit-time backstop: a main-frame page outside the origin is stopped and the view goes home. */
    private fun sendHomeIfOutside(view: WebView, url: String?): Boolean {
        if (!DigistampNavigationPolicy.mustReturnHome(url)) return false
        returningHome = true
        view.stopLoading()
        view.loadUrl(DigistampUris.BASE_URL)
        return true
    }
}
