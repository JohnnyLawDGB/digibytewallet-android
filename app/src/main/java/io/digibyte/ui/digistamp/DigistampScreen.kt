package io.digibyte.ui.digistamp

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.digibyte.core.digistamp.DigistampUris

/**
 * The digistamp marketplace, housed in the wallet.
 *
 * ## The rule this screen exists to enforce
 *
 * **There is no JavaScript bridge, and there must never be one.** `addJavascriptInterface` is
 * not called here or anywhere else in the app. Page code therefore has no way to ask the wallet
 * for anything — not a signature, not an address, not a balance. The only channel from a page to
 * the wallet is *navigation*: the page links to a wallet URL and [onWalletAction] turns that into
 * a native screen the user can read before acting.
 *
 * That asymmetry is the whole security argument for putting a third-party site next to an
 * unlocked wallet. With a bridge, safety would rest on validating every argument page JS could
 * pass. With navigation only, a fully compromised page can ask to *open a screen* and nothing
 * more.
 *
 * ## Origin lock
 *
 * Only `https://assets.digistamp.co` renders here ([DigistampUris.isInAppOrigin]). Everything
 * else — other hosts, plaintext HTTP, `javascript:`, `data:`, `file:` — is handed to the system
 * browser, where the user gets a URL bar and the browser's sandbox. A link that leaves the site
 * leaves the app.
 *
 * JavaScript is enabled because the site is a Next.js app and will not render without it. That
 * is safe precisely because of the two rules above: script can run, but it cannot reach out.
 */
@Composable
fun DigistampScreen(
    onWalletAction: (Uri) -> Unit = {},
    startUrl: String = DigistampUris.BASE_URL,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // In-site navigation should feel like the app's own, so Back walks the page history first
    // and only leaves the section when there is nowhere left to go back to.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                @SuppressLint("SetJavaScriptEnabled")
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true          // Next.js will not render without it
                    settings.domStorageEnabled = true

                    // Deliberately closed. Each of these would hand page script a capability it
                    // has no business having next to a wallet.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setGeolocationEnabled(false)
                    // NOTE: addJavascriptInterface is NEVER called. See the class comment.

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url.toString()

                            // A wallet action: the page asked for a native screen. It supplies a
                            // destination, never an instruction — whatever opens must build its
                            // own confirmation from data the wallet fetched and verified itself.
                            if (request.url.scheme == "digibyte" || request.url.scheme == "digiid") {
                                onWalletAction(request.url)
                                return true
                            }

                            if (DigistampUris.isInAppOrigin(url)) return false  // let it load here

                            // Anything else belongs to the browser, with its URL bar and sandbox.
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                android.util.Log.w("Digistamp", "no handler for $url", it)
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                        }
                    }

                    loadUrl(startUrl)
                    webView = this
                }
            },
        )

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
