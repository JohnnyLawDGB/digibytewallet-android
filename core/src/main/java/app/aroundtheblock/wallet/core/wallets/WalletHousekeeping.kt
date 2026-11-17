package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log

/**
 * Launch-time cleanup of the wallet changes a process did not live to finish: an add that never
 * completed (PENDING) and a removal (REMOVING). Runs in `Application.onCreate`, before anything opens
 * the database or starts the sync service, because removing the running wallet leaves its erase to
 * this (its database cannot be deleted under the live process).
 *
 * An entry is dropped only when its erase verified. One that did not keeps its state and is
 * retried at the next launch (legacy's key, for one, stays while the database password's record
 * still depends on it).
 */
class WalletHousekeeping internal constructor(
    private val registry: WalletRegistry,
    private val eraser: WalletStoreEraser,
) {
    constructor(context: Context) : this(ActiveWallet.registry(context), WalletStoreEraser(context))

    /** Erases each PENDING or REMOVING entry's scope. Returns the number of entries dropped. Writes nothing when none is incomplete. */
    fun run(): Int {
        var dropped = 0
        for (entry in registry.incomplete()) {
            if (entry.state == EntryState.READY) continue
            val scope = WalletScope(entry.id, entry.testnet == true)
            val report = eraser.erase(scope)
            if (report.verified && registry.drop(entry.id)) {
                dropped++
            } else {
                Log.w(TAG, "a ${entry.state} wallet was not fully erased; retried at the next launch")
            }
        }
        return dropped
    }

    private companion object {
        const val TAG = "WalletHousekeeping"
    }
}
