package app.aroundtheblock.wallet.ui.digistamp

import android.net.Uri
import android.webkit.WebView
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
import app.aroundtheblock.wallet.core.digistamp.DigistampUris
import app.aroundtheblock.wallet.ui.util.openExternalUrl

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
 * Only `https://assets.digistamp.co` renders here ([DigistampUris.isInAppOrigin]), and
 * [DigistampNavigationPolicy] decides every navigation: a frame inside the page decides nothing; an
 * HTTPS link elsewhere opens in the browser, through openExternalUrl, where the user gets a URL bar
 * and the browser's sandbox; anything else is dropped. A main-frame page outside the origin that
 * commits anyway — a form POST, which the platform never asks the client about — is stopped and
 * the view goes home ([DigistampWebViewClient]).
 *
 * JavaScript is enabled because the site is a Next.js app and will not render without it; every
 * other capability is off (DigistampWebViewHost.applyLeastSettings). That is safe precisely
 * because of the two rules above: script can run, but it cannot reach out.
 */
@Composable
fun DigistampScreen(
    onWalletAction: (Uri) -> Unit = {},
    startUrl: String = DigistampUris.BASE_URL,
    onCancel: () -> Unit = {},
) {
    // The WebView's network stack does not use the app's proxy, so with the user's Tor setting
    // on the site would see the device's own address. Say so BEFORE anything loads, and load only
    // if the user continues; the answer is remembered for the session (MarketTorNotice).
    val context = LocalContext.current
    val torEnabled = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            DigistampEntryPoint::class.java,
        ).torManager().isEnabled
    }
    var asking by remember { mutableStateOf(MarketTorNotice.mustAsk(torEnabled)) }
    if (asking) {
        MarketTorNoticeDialog(
            onContinue = {
                MarketTorNotice.accept()
                asking = false
            },
            onCancel = {
                // A destination asked for by another screen is not carried into a later visit.
                DigistampWebViewHost.pendingUrl = null
                onCancel()
            },
        )
    } else {
        DigistampWebContent(onWalletAction = onWalletAction, startUrl = startUrl)
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface DigistampEntryPoint {
    fun torManager(): app.aroundtheblock.wallet.core.tor.TorManager
}

/** The notice shown before the Market loads while the user's Tor setting is on. */
@Composable
private fun MarketTorNoticeDialog(onContinue: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.aroundtheblock.wallet.R.string.market_tor_notice_title)) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.aroundtheblock.wallet.R.string.market_tor_notice_body)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onContinue) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.aroundtheblock.wallet.R.string.market_tor_notice_continue))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.aroundtheblock.wallet.R.string.common_cancel))
            }
        },
    )
}

/** The Market itself: the retained WebView. Reached only past the Tor notice ([DigistampScreen]). */
@Composable
private fun DigistampWebContent(
    onWalletAction: (Uri) -> Unit,
    startUrl: String,
) {
    val context = LocalContext.current
    // A revisit re-attaches an already-loaded view, so onPageFinished will not fire again and a
    // spinner initialised to true would sit forever over a perfectly good page.
    var loading by remember { mutableStateOf(!DigistampWebViewHost.exists()) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Refreshed every composition; the retained client reads them rather than capturing them.
    DigistampWebViewHost.onWalletAction = { uri ->
        DigistampWebViewHost.reloadOnReturn = true
        onWalletAction(uri)
    }
    DigistampWebViewHost.openInBrowser = { url -> openExternalUrl(context, url) }

    // Returning from a wallet action: ask the site what it thinks now rather than trusting a poll
    // to have survived the detach. Digi-ID is completed by the SERVER, so a reload is how the
    // page finds out.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        DigistampWebViewHost.logSessionCookieNames()

        // A specific destination was requested (e.g. an asset's Marketplace button). Checked
        // before the reload, because reloading and THEN navigating would show the previous page
        // first and cost a round trip. Re-validated here rather than trusted: whatever set this
        // is in-process, but the origin lock is the one rule this screen must never be talked
        // out of.
        val requested = DigistampWebViewHost.pendingUrl
        if (requested != null) {
            DigistampWebViewHost.pendingUrl = null
            if (DigistampUris.isInAppOrigin(requested)) {
                loading = true
                webView?.loadUrl(requested)
                return@LaunchedEffect
            }
            android.util.Log.w("Digistamp", "refusing off-origin pending url")
        }

        if (DigistampWebViewHost.reloadOnReturn) {
            DigistampWebViewHost.reloadOnReturn = false
            webView?.reload()
        }
    }

    // In-site navigation should feel like the app's own, so Back walks the page history first
    // and only leaves the section when there is nowhere left to go back to.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // Retained, NOT created per visit. Digi-ID sign-in is a two-sided handshake and
                // the page's poll is what collects the session cookie — disposing this view
                // mid-navigation killed it. See DigistampWebViewHost.
                DigistampWebViewHost.obtain(ctx) { view ->
                    view.webViewClient = DigistampWebViewClient(
                        // A wallet action: the page asked for a native screen. It supplies a
                        // destination, never an instruction — whatever opens must build its own
                        // confirmation from data the wallet fetched and verified itself.
                        walletScreen = { uri -> DigistampWebViewHost.onWalletAction(uri) },
                        openInBrowser = { url -> DigistampWebViewHost.openInBrowser(url) },
                        onLoading = { loading = it },
                    )

                    view.loadUrl(startUrl)
                }.also { webView = it }
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
