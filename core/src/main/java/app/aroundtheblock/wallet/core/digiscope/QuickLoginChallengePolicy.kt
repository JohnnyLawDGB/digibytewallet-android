package app.aroundtheblock.wallet.core.digiscope

import app.aroundtheblock.wallet.core.model.DigiIdRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Which login challenges Hub quick login signs (ID-001). Pure: no network, no native code.
 *
 * Quick login signs the challenge the DigiScope server hands it with the shared identity key,
 * m/0'/0/0 — on purpose, since the Hub account is bound to that key's address. So what keeps the
 * signature DigiScope's is this check, made before anything is signed: the strict Digi-ID parser
 * ([DigiIdRequest.parse]) must accept the challenge, its callback must be HTTPS, and it must name
 * the very origin — host and port — that quick login then submits the signed login to
 * ([LOGIN_CALLBACK]). Every other challenge is refused unsigned.
 */
object QuickLoginChallengePolicy {

    /** Where quick login submits the signed challenge. */
    val LOGIN_CALLBACK: HttpUrl = "${DigiScopeClient.BASE_URL}/auth/digiid/callback".toHttpUrl()

    /**
     * [uri] itself when quick login may sign it — the text checked is the text signed and
     * submitted, so text with anything around it is refused — or null when it is not a login
     * challenge for DigiScope.
     */
    fun accept(uri: String?): String? {
        if (uri.isNullOrEmpty() || uri != uri.trim()) return null
        val challenge = DigiIdRequest.parse(uri) ?: return null
        val callback = challenge.callbackHttpUrl
        val sameOrigin = callback.scheme == LOGIN_CALLBACK.scheme &&
            callback.host == LOGIN_CALLBACK.host &&
            callback.port == LOGIN_CALLBACK.port
        val forDigiScope = !challenge.isUnsecure && sameOrigin && challenge.domain == LOGIN_CALLBACK.host
        return if (forDigiScope && challenge.nonce.isNotEmpty()) uri else null
    }
}
