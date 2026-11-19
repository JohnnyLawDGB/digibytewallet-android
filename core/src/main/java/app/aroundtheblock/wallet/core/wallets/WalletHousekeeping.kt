package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log

/**
 * Launch-time cleanup of the wallet changes a process did not live to finish: an add that never
 * completed (PENDING), a removal (REMOVING), and a READY wallet whose seed record is gone (left by a
 * wipe that erased the records but did not clear the registry). Runs in `Application.onCreate`,
 * before anything opens the database or starts the sync service, because removing the running
 * wallet leaves its erase to this (its database cannot be deleted under the live process).
 *
 * An entry is dropped only when its erase verified. One that did not keeps its state and is
 * retried at the next launch (legacy's key, for one, stays while the database password's record
 * still depends on it).
 *
 * A READY wallet with no record is hidden from the list, yet it would hold a slot toward the cap
 * for good and leave its key and stores on the device. It is erased only when nothing of its record
 * is left: the registry reads no sealed seed AND the eraser's read-back finds no record file. A record
 * file still on disk, even one no seed can be read from, leaves the wallet as it is. Legacy is never
 * one of these (its record going is what unlists it), nor is any READY wallet that has its record.
 *
 * Such a wallet is erased on BOTH networks, as the wipe erases a wallet, and before its key aliases
 * and its entry go. With no record left, those two are all a wipe finds the wallet by, and this runs
 * before the wipe a launch may still owe: an erase of the one network the entry records would leave
 * whatever is under the other where nothing looks again.
 *
 * A removal is erased on both networks for the same reason, its seed record and its key first as in
 * any removal. An add that never completed (PENDING) was never loaded: its record and its key are
 * all it wrote, neither name carries a network, and it is erased as [WalletProvisioner] erases an
 * abandoned add.
 */
class WalletHousekeeping internal constructor(
    private val registry: WalletRegistry,
    private val eraser: WalletStoreEraser,
) {
    constructor(context: Context) : this(ActiveWallet.registry(context), WalletStoreEraser(context))

    /**
     * Erases each PENDING or REMOVING entry, and each READY entry whose record is gone. Returns the
     * number of entries dropped. Writes nothing when there is none of either.
     */
    fun run(): Int {
        var dropped = 0
        for (entry in registry.incomplete()) {
            if (entry.state == EntryState.READY) continue
            val scope = WalletScope(entry.id, entry.testnet == true)
            // An add that never completed was never loaded: only a removal can hold anything under the other network.
            val report = if (entry.state == EntryState.PENDING) eraser.erase(scope) else eraser.eraseOnBothNetworks(scope)
            if (report.verified && registry.drop(entry.id)) {
                dropped++
            } else {
                Log.w(TAG, "a ${entry.state} wallet was not fully erased; retried at the next launch")
            }
        }
        for (entry in registry.readyWithoutRecord()) {
            val scope = WalletScope(entry.id, entry.testnet == true)
            if (eraser.recordLeft(scope)) {
                Log.w(TAG, "a READY wallet's record reads empty but its file is still there; left as it is")
                continue
            }
            // With no record left, the key guards nothing: it and this entry only name the wallet. So the
            // stores go first, and the key and the entry only once both networks read back empty.
            val erased = eraser.eraseStoresOnBothNetworks(scope) && eraser.erase(scope).verified
            if (erased && registry.drop(entry.id)) {
                dropped++
            } else {
                Log.w(TAG, "a READY wallet with no record was not fully erased; retried at the next launch")
            }
        }
        return dropped
    }

    private companion object {
        const val TAG = "WalletHousekeeping"
    }
}
