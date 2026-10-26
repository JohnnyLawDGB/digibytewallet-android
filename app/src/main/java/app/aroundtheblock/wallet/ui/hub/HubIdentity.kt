package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.core.bridge.NativeBridge
import javax.inject.Inject

/**
 * The identity the Hub knows the wallet by: the shared identity key, m/0'/0/0, which the Hub
 * account is bound to. The Hub screens reach it only through here, so they can be driven
 * without the native library.
 */
class HubIdentity @Inject constructor() {

    /** The identity's address, or "" while no wallet is loaded. */
    fun address(): String = NativeBridge.getReceiveAddress(0, 0) ?: ""

    /** "address|signature" over [text], or null when the wallet cannot sign. */
    fun sign(text: String): String? = NativeBridge.signMessage(text, 1)
}
