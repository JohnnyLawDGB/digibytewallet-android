package app.aroundtheblock.wallet.core.digiscope

import app.aroundtheblock.wallet.core.wallets.WalletScope

/**
 * Ends one wallet's Hub session on the server, before its stored token is erased: a copy of the
 * token made earlier then stops working too. Best effort — it never throws, never waits on the
 * network, and its outcome decides nothing: a wipe or a removal goes ahead whatever it did, and an
 * unrevoked session expires on its own. Production: [DigiScopeClient.revokeStoredSession].
 */
fun interface HubSessionRevoke {
    fun revoke(scope: WalletScope)

    companion object {
        /** Nothing to revoke (a pure-JVM construction). */
        val NONE = HubSessionRevoke { }
    }
}
