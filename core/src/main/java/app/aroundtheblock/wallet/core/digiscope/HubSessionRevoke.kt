package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.wallets.WalletScope
import okhttp3.OkHttpClient

/**
 * Ends one wallet's Hub session on the server, before its stored token is erased: a copy of the
 * token made earlier then stops working too. Best effort — it never throws, never waits on the
 * network, and its outcome decides nothing: a wipe or a removal goes ahead whatever it did, and an
 * unrevoked session expires on its own. Production: [StoredHubSessionRevoke].
 */
fun interface HubSessionRevoke {
    fun revoke(scope: WalletScope)

    companion object {
        /** Nothing to revoke (a pure-JVM construction). */
        val NONE = HubSessionRevoke { }
    }
}

/**
 * [HubSessionRevoke] for any wallet on the device: the token that wallet's stores hold is sent once
 * to `POST /api/auth/logout`, as [DigiScopeClient.logout] sends the running one.
 *
 * It does not build or ask the Hub client, on purpose (WIPE-WRITES-NOTHING). The client loads its
 * token when it is built, and that load moves a token from before the encrypted store into it,
 * which creates that store's file. A wipe can be the first thing in a process to need the Hub (the
 * launch backstop), and the revoke runs before anything is erased: built there, the client would
 * make a file the wipe then finds "already on the device". So the token is read where it is
 * ([HubTokenStore.stored]: nothing moved, written or reset) and sent by the Hub client's own
 * request ([DigiScopeClient.sendLogout]) over the pinned client's own settings
 * ([DigiScopeClient.pinned]), built from the shared client only when there is a token to send.
 * Erasing the token is the caller's next step.
 */
class StoredHubSessionRevoke internal constructor(
    private val tokenStore: (WalletScope) -> HubTokenStore,
    private val send: (String) -> Unit,
) : HubSessionRevoke {

    constructor(context: Context, baseClient: () -> OkHttpClient) : this(
        { scope -> HubTokenStore(context.applicationContext ?: context, scope) },
        pinnedSender(baseClient),
    )

    override fun revoke(scope: WalletScope) {
        val token = try {
            tokenStore(scope).stored()
        } catch (e: Exception) {
            Log.w(TAG, "stored session not read for revoke: ${e.javaClass.simpleName}")
            null
        }
        if (!token.isNullOrBlank()) send(token)
    }

    private companion object {
        const val TAG = "DigiScope"

        /** Sends over [baseClient] pinned, built once and only when the first token is sent. */
        fun pinnedSender(baseClient: () -> OkHttpClient): (String) -> Unit {
            val client by lazy { DigiScopeClient.pinned(baseClient()) }
            return { token -> DigiScopeClient.sendLogout(client, token) }
        }
    }
}
