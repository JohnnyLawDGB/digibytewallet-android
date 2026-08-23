package io.digibyte.core.digistamp

import org.json.JSONObject

/** A Digi-ID challenge issued by digistamp, already checked to be about digistamp. */
data class DigistampChallenge(
    val digiIdUri: String,
    val nonce: String,
    val callbackUrl: String,
)

/**
 * Origin rules for the in-app digistamp section.
 *
 * The section shows digistamp's own pages in a WebView, with NO JavaScript bridge — page code
 * cannot call the wallet, because there is nothing to call. Navigation is the only channel, and
 * [isInAppOrigin] decides which navigations stay inside the app next to an unlocked wallet and
 * which are handed to the system browser, where the user gets a URL bar and a sandbox.
 *
 * It is an allowlist, matched exactly. See DigistampUrisTest.
 */
object DigistampUris {
    const val HOST = "assets.digistamp.co"

    const val BASE_URL = "https://$HOST"
    const val CHALLENGE_URL = "$BASE_URL/api/auth/digiid/challenge"
    const val SESSION_URL = "$BASE_URL/api/auth/session"
    const val LOGOUT_URL = "$BASE_URL/api/auth/logout"

    /**
     * True only for `https://assets.digistamp.co` exactly.
     *
     * Exact host, never a suffix: `assets.digistamp.co.evil.com` ends with the host and belongs
     * to someone else. Subdomains are excluded too — they are a different origin, and nothing
     * needs them. HTTPS only, since a cleartext page would carry the session in the clear.
     */
    fun isInAppOrigin(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        // URI puts anything before an '@' in userInfo, so "https://good.host@evil.com" has
        // host == evil.com and is correctly refused here.
        val host = uri.host ?: return false
        if (!host.equals(HOST, ignoreCase = true)) return false
        return uri.port == -1 || uri.port == 443
    }

    /**
     * Reads a challenge and refuses one that is not about digistamp.
     *
     * The wallet signs the URI this returns, so a challenge naming another domain's callback
     * would have it sign an authentication for a site the user never visited. DigiIdManager
     * checks the callback host too; this is the earlier of two independent checks, not a
     * duplicate of one.
     */
    fun parseChallenge(json: JSONObject): DigistampChallenge? {
        if (json.has("error")) return null

        val uri = json.optString("uri").takeIf { it.isNotEmpty() } ?: return null
        if (!uri.startsWith("digiid://")) return null
        if (digiIdHost(uri) != HOST) return null

        val nonce = json.optString("nonce").takeIf { it.isNotEmpty() } ?: return null

        val callback = json.optString("callbackUrl").takeIf { it.isNotEmpty() } ?: return null
        if (!isInAppOrigin(callback)) return null

        return DigistampChallenge(digiIdUri = uri, nonce = nonce, callbackUrl = callback)
    }

    /** Host of a `digiid://host/path?x=nonce` URI, matching DigiIdRequest's own reading. */
    private fun digiIdHost(uri: String): String =
        uri.removePrefix("digiid://").substringBefore('?').substringBefore('/').substringBefore(':')
}
