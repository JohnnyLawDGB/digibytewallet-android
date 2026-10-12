package app.aroundtheblock.wallet.ui.digistamp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
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

    /** Opens a link the navigation policy approved for the browser. Held here for the same reason:
     *  the browser is started from the current screen's context, not the first one's. */
    @Volatile
    var openInBrowser: (String) -> Unit = {}

    /** Whether a view already exists — i.e. whether this visit will re-attach rather than load. */
    fun exists(): Boolean = webView != null

    fun obtain(context: Context, configure: (WebView) -> Unit): WebView {
        // Re-attaching a View that still has a parent throws, and AndroidView will try.
        webView?.let { detach(); return it }

        val created = WebView(context.applicationContext)
        applyLeastSettings(created.settings)
        configure(created)
        webView = created
        return created
    }

    /**
     * Every setting the Market's view has, each the least the page needs (DigistampWebSettingsTest
     * holds the list; a setting added here is added there, with what the page needs it for).
     *
     * What the page needs was read from the site: it is a Next.js app, and the only thing it keeps
     * in DOM storage is a theme choice, read inside a try/catch that falls back to the system theme.
     */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun applyLeastSettings(settings: WebSettings) {
        // Kept: the site renders nothing without script. Script cannot reach the wallet: there is
        // no JavaScript bridge (see DigistampScreen), and its only way out of the page is a
        // navigation, which the view's client decides.
        settings.javaScriptEnabled = true

        // Off: nothing on the site needs them, and each would hand page script a capability it
        // has no business having next to a wallet.
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setGeolocationEnabled(false)
    }

    /**
     * Set when a wallet action was dispatched, so the next visit knows to re-check with the site.
     *
     * Digi-ID completes out-of-band: the wallet POSTs the signature and the SERVER marks the
     * nonce authenticated. The page only learns of it through its own poll, which is unreliable
     * across a detach — a detached WebView has its timers throttled. Reloading on return asks the
     * site directly instead of hoping the poll survived.
     */
    @Volatile
    var reloadOnReturn: Boolean = false

    /**
     * A URL the next visit should load.
     *
     * The view is RETAINED, so `startUrl` only applies the first time it is built — passing a
     * different one on a later visit would silently do nothing. Anything wanting to send the
     * section somewhere specific (the asset screen's Marketplace button) sets this instead.
     */
    @Volatile
    var pendingUrl: String? = null

    /** What the site currently thinks of us, for diagnosing a sign-in that reports success and
     *  leaves the page logged out. Names only — never the values, which ARE the session. */
    fun logSessionCookieNames() {
        val cookie = android.webkit.CookieManager.getInstance()
            .getCookie(app.aroundtheblock.wallet.core.digistamp.DigistampUris.BASE_URL)
        val names = cookie?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }
        android.util.Log.i("Digistamp", "session cookies present: ${names ?: "none"}")
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
