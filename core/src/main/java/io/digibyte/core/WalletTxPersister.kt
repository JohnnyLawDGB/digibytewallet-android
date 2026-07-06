package io.digibyte.core

import android.content.Context
import io.digibyte.core.bridge.NativeBridge

/**
 * Snapshots the C wallet's transaction set to SharedPreferences so a
 * just-broadcast tx survives an app force-stop or crash before
 * SyncService's next sync-complete save fires. Without this, a user
 * who sends and immediately closes the app loses the tx from
 * persistent state (in-memory only); after restart the C wallet
 * rebuilds from the stale snapshot and the tx vanishes from the UI.
 *
 * Companion to the C-side change in `jni_transaction.c`'s
 * publishTransaction, which calls BRWalletRegisterTransaction
 * synchronously so getSerializedTransactions includes the new tx by
 * the time persist() runs here.
 */
class WalletTxPersister(private val context: Context) {

    /**
     * Serialize the wallet's tx set and write it through to disk
     * synchronously. Uses commit() (not apply()) because the
     * intended caller is the post-broadcast path — a user who
     * force-stops the app moments later would otherwise lose the
     * tx from the async flush.
     */
    fun persist(): Boolean {
        val txData = NativeBridge.getSerializedTransactions() ?: return false
        val hex = txData.joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences(PREFS_NAME + networkSuffix(context), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED_TRANSACTIONS, hex)
            .commit()
    }

    companion object {
        private const val PREFS_NAME = "dgb_sync_data"
        private const val KEY_SAVED_TRANSACTIONS = "saved_transactions"
    }
}
