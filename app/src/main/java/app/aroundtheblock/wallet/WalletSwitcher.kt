package app.aroundtheblock.wallet

import android.util.Log
import app.aroundtheblock.wallet.core.wallets.EntryState
import app.aroundtheblock.wallet.core.wallets.WalletRegistry

/**
 * Switches the wallet by persisting the active id, ending the DigiStamp web session and restarting.
 * Hub logout is deliberately not called: wallet A's server session stays valid for A and its token
 * is in A's store.
 */
class WalletSwitcher internal constructor(
    private val registry: WalletRegistry,
    private val testnet: () -> Boolean,
    /** The wallet THIS process runs, not the registry's active id (an add already made the new one active). */
    private val currentId: () -> String,
    private val endWebSession: () -> Boolean,
    private val restart: () -> Unit,
) {
    enum class Result { RESTARTING, ALREADY_ACTIVE, UNKNOWN_WALLET, NOT_SAVED }

    fun switchTo(id: String): Result {
        val net = testnet()
        val entry = registry.find(id)
        if (entry == null || entry.state != EntryState.READY || (entry.testnet != null && entry.testnet != net)) return Result.UNKNOWN_WALLET
        if (id == currentId()) return Result.ALREADY_ACTIVE
        if (!registry.setActive(net, id)) return Result.NOT_SAVED
        val ended = try { endWebSession() } catch (t: Throwable) { false }
        runCatching { Log.i("WalletSwitcher", "web session end confirmed=$ended") }
        restart()
        return Result.RESTARTING
    }
}
