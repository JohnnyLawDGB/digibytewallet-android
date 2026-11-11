package app.aroundtheblock.wallet

import app.aroundtheblock.wallet.core.model.DigiIdRequest

/** Which deep links MainActivity takes in as a pending Digi-ID request. */
internal object DigiIdDeepLink {

    /**
     * [uri] when it is a `digiid://` link no longer than [DigiIdRequest.MAX_URI_LENGTH]; null for anything
     * else. MSG-LEN: a deep link can come from any app or web page and its length is not bounded, so the
     * cap applies at intake, before the link is held, shown or signed.
     */
    fun accepted(uri: String?): String? =
        uri?.takeIf { it.startsWith("digiid://") && DigiIdRequest.withinLengthCap(it) }
}
