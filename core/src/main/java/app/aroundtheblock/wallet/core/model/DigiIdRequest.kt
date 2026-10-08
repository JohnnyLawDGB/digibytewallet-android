package app.aroundtheblock.wallet.core.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class DigiIdRequest(
    val rawUri: String,       // original digiid://... URI (signed + echoed in POST)
    val callbackUrl: String,  // https/http callback, verbatim; the per-site key's derivation input
    val nonce: String,
    val isUnsecure: Boolean,  // http vs https
    val domain: String,       // shown to the user; always callbackHttpUrl.host
    val callbackHttpUrl: HttpUrl  // the one parse of callbackUrl: the check and the POST use it
) {
    companion object {
        fun parse(uri: String): DigiIdRequest? {
            if (!uri.startsWith("digiid://")) return null
            val withoutScheme = uri.removePrefix("digiid://")
            val parts = withoutScheme.split("?", limit = 2)
            val callbackPath = parts[0]
            val params = if (parts.size > 1) {
                parts[1].split("&").associate {
                    val kv = it.split("=", limit = 2)
                    kv[0] to (kv.getOrNull(1) ?: "")
                }
            } else emptyMap()

            val nonce = params["x"] ?: return null
            val unsecure = params["u"] == "1"
            val scheme = if (unsecure) "http" else "https"
            // Kept verbatim: the per-site identity key is derived from this exact string, so a
            // respelling of an ordinary callback would silently move its users to a new identity.
            val callbackUrl = "$scheme://$callbackPath"

            // CRITICAL-4: the domain shown, the host checked and the host the POST connects to are
            // one value, taken from this single parse. It is OkHttp's parse because OkHttp makes
            // the request, and the authority is accepted only when its text already IS that host.
            val url = callbackUrl.toHttpUrlOrNull() ?: return null
            if (!isPlainAuthority(callbackPath.substringBefore('/'), url)) return null

            return DigiIdRequest(uri.trim(), callbackUrl, nonce, unsecure, url.host, url)
        }

        /**
         * True when [authority] (the text between `digiid://` and the first `/`) is exactly
         * `host` or `host:port` as OkHttp parsed it, up to letter case. That refuses:
         *  - user info (`name@host`, `name:port@host`) — OkHttp connects to the part after `@`;
         *  - anything outside printable ASCII — OkHttp maps such a host through IDNA, so the host
         *    it contacts is not the text that was shown;
         *  - any other spelling OkHttp rewrites (percent escapes, a backslash, a fragment, a port
         *    with leading zeros, a bracketed IPv6 literal).
         */
        private fun isPlainAuthority(authority: String, url: HttpUrl): Boolean {
            if (authority.isEmpty() || authority.any { it.code !in 0x21..0x7e }) return false
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
            val expected = if (':' in authority) "${url.host}:${url.port}" else url.host
            return authority.lowercase() == expected
        }
    }
}

sealed class DigiIdResult {
    data class Success(val domain: String) : DigiIdResult()
    data class Error(val code: Int, val message: String) : DigiIdResult()
}
