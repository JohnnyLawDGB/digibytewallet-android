package io.digibyte.ui.digistamp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView

/**
 * Keeps ONE digistamp WebView alive for the whole app session.
 *
 * WHY THIS EXISTS. Digi-ID sign-in is a two-sided handshake: the page polls
 * `/api/auth/digiid/poll` while the wallet signs and POSTs to the callback. The server marks the
 * nonce authenticated, and **the polling request is what receives the session cookie**.
 *
 * When the Digi-ID confirmation was a navigation, Compose disposed the Market screen and took the
 * WebView with it — killing the poll mid-handshake. Measured on an emulator 2026-08-24: the
 * wallet logged `Digi-ID response: 200`, a real success, and coming back showed the page freshly
 * reloaded at the home URL still offering "Sign in". The server had a session; the browser half
 * had been destroyed before it could claim it.
 *
 * Retaining the view keeps the poll in flight across that round trip. It is also why signing in
 * lands you back where you were rather than at the top of the site.
 *
 * Built with the APPLICATION context deliberately — a retained View holding an Activity would
 * leak it on every rotation.
 */
object DigistampWebViewHost {

    private var webView: WebView? = null

    /** The current handler for intercepted wallet links. Held here rather than captured by the
     *  WebViewClient, because the client outlives each composition and a captured lambda would
     *  keep navigating with a NavController that has since been discarded. */
    @Volatile
    var onWalletAction: (android.net.Uri) -> Unit = {}

    /** Whether a view already exists — i.e. whether this visit will re-attach rather than load. */
    fun exists(): Boolean = webView != null

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context, configure: (WebView) -> Unit): WebView {
        // Re-attaching a View that still has a parent throws, and AndroidView will try.
        webView?.let { detach(); return it }

        val created = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true          // Next.js will not render without it
            settings.domStorageEnabled = true

            // Deliberately closed. Each would hand page script a capability it has no business
            // having next to a wallet.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setGeolocationEnabled(false)
            // NOTE: addJavascriptInterface is NEVER called. See DigistampScreen.
        }
        configure(created)
        webView = created
        return created
    }

    /** Detach from whatever parent held it, so it can be re-attached on the next visit. */
    fun detach() {
        (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
    }

    /** Signing out must not leave a live session behind in a retained view. */
    fun destroy() {
        detach()
        webView?.destroy()
        webView = null
    }
}
