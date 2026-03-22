package io.digibyte.core.model

data class DigiIdRequest(
    val callbackUrl: String,
    val nonce: String,
    val isUnsecure: Boolean,  // http vs https
    val domain: String
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
            val callbackUrl = "$scheme://$callbackPath"
            val domain = callbackPath.split("/")[0].split(":")[0]

            return DigiIdRequest(callbackUrl, nonce, unsecure, domain)
        }
    }
}

sealed class DigiIdResult {
    data class Success(val domain: String) : DigiIdResult()
    data class Error(val code: Int, val message: String) : DigiIdResult()
}
