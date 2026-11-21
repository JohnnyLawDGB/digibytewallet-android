package app.aroundtheblock.wallet.core.digistamp

/**
 * Origin rules for the in-app digistamp section.
 *
 * The section shows digistamp's own pages in a WebView, with NO JavaScript bridge — page code
 * cannot call the wallet, because there is nothing to call. Navigation is the only channel, and
 * [isInAppOrigin] decides which navigations stay inside the app next to an unlocked wallet and
 * which are handed to the system browser, where the user gets a URL bar and a sandbox.
 *
 * It is an allowlist of two hosts, each matched exactly: the marketplace ([HOST]) and DigiStamp's
 * Digi-ID sign-in host ([SIGN_IN_HOST], MARKET-SIGNIN-ID-HOST). See DigistampUrisTest.
 *
 * The wallet makes no sign-in request of its own to either host. Until 2026-11 it fetched a Digi-ID
 * challenge from the marketplace host; DigiStamp moved sign-in to [SIGN_IN_HOST], where the
 * challenge is bound to the browser that asked for it, so only the page can ask. The page offers
 * the challenge as a `digiid:` link and the view's client turns that navigation into the wallet's
 * own approval screen (DigistampNavigationPolicy).
 */
object DigistampUris {
    const val HOST = "assets.digistamp.co"

    /**
     * DigiStamp's Digi-ID sign-in host. The marketplace's "Sign in" answers with a redirect to
     * `https://id.digistamp.co/id/signin?...`; the page there shows the Digi-ID request, waits for
     * the wallet's approval and then redirects back to [HOST], which is where the marketplace
     * session cookie is set. Outside the view that last step lands in the browser, never in the app.
     */
    const val SIGN_IN_HOST = "id.digistamp.co"

    const val BASE_URL = "https://$HOST"

    /**
     * True for `https://assets.digistamp.co` (any path), and for the sign-in pages of
     * `https://id.digistamp.co` ([isSignInPage]). Nothing else.
     *
     * Exact host, never a suffix: `assets.digistamp.co.evil.com` ends with the host and belongs
     * to someone else. Other subdomains are excluded too — they are different origins, and nothing
     * needs them. HTTPS only, since a cleartext page would carry the session in the clear.
     */
    fun isInAppOrigin(url: String): Boolean {
        val uri = httpsDefaultPort(url) ?: return false
        return when (uri.host.lowercase()) {
            HOST -> true
            SIGN_IN_HOST -> isSignInPath(uri.rawPath.orEmpty())
            else -> false
        }
    }

    /**
     * True for a page of the sign-in host that the view shows: `https://id.digistamp.co` exactly
     * (default port), and only these paths, read from the live pages on 2026-11-21:
     *
     *  - `/id` and anything under `/id/` — the sign-in page, the step after it and its help page;
     *  - anything under `/auth/` — the host's own redirect into and out of sign-in;
     *  - `/` and `/account` — the Digi-ID account page, where sign-in continues for an account
     *    that existed before the move.
     *
     * Every other path of that host opens in the browser. The paths are a limit on what the app
     * displays, not a second security boundary: the boundary is the origin, which the host, scheme
     * and port decide. A path with a `%`, a backslash or a `.`/`..` segment is refused rather than
     * normalised, so the path that was checked is the path that loads.
     */
    fun isSignInPage(url: String): Boolean {
        val uri = httpsDefaultPort(url) ?: return false
        return uri.host.equals(SIGN_IN_HOST, ignoreCase = true) && isSignInPath(uri.rawPath.orEmpty())
    }

    /** [url] parsed, when it is HTTPS on the default port with a host; null otherwise. */
    private fun httpsDefaultPort(url: String): java.net.URI? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        // URI puts anything before an '@' in userInfo, so "https://good.host@evil.com" has
        // host == evil.com and is refused by the host comparison. Credentials in a URL are no part
        // of either site, so a URL carrying any is refused on the right host too.
        if (uri.host == null || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        return uri
    }

    private fun isSignInPath(rawPath: String): Boolean {
        if (rawPath.isEmpty() || rawPath == "/" || rawPath == "/account") return true
        if (!SIGN_IN_PATH.matches(rawPath)) return false
        return rawPath.split('/').none { it == "." || it == ".." }
    }

    /** `/id`, `/id/...` or `/auth/...`, of unreserved characters only: no `%`, no backslash. */
    private val SIGN_IN_PATH = Regex("""/(?:id(?:/[A-Za-z0-9._~-]*)*|auth(?:/[A-Za-z0-9._~-]*)+)""")

    /**
     * The in-app explorer page for [assetId], or null if that id could escape the URL path.
     *
     * Replaces a hardcoded `https://diginexum.trade/` on the asset screen — a stale host whose
     * certificate is issued for `CN = digiassets.info`, so tapping Marketplace presented a
     * full-page browser security warning. It also carried no asset id and simply opened a
     * homepage.
     *
     * The id lands inside a URL a WebView then loads, so anything that could escape the path is
     * REFUSED rather than percent-encoded. Asset ids are base58, where no slash, dot, colon,
     * query or fragment character occurs legitimately — so rejecting them costs nothing real and
     * removes the question of whether encoding was sufficient.
     */
    fun explorerUrlFor(assetId: String?): String? {
        val id = assetId?.trim().orEmpty()
        if (id.isEmpty()) return null
        if (!id.all { it.isLetterOrDigit() }) return null
        return "$BASE_URL/explorer/$id"
    }
}
