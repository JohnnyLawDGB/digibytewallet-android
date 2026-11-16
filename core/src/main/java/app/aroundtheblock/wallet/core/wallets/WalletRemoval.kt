package app.aroundtheblock.wallet.core.wallets

import android.util.Log
import app.aroundtheblock.wallet.core.WipeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [WalletRemoval.remove] did. */
sealed class RemoveResult {
    /** A wallet other than the running one: erased now and dropped from the registry. */
    object Removed : RemoveResult()

    /**
     * The running wallet: marked REMOVING and nothing erased yet. The caller restarts (AppRestart);
     * at the next launch [WalletHousekeeping] erases it before the database or the sync service opens.
     */
    object RemovedRestartNeeded : RemoveResult()

    /** It was the last wallet on the device: the full wipe ran. */
    data class WipedEverything(val report: WipeReport) : RemoveResult()

    /** Legacy, kept REMOVING: the database password's record still depends on its key. Housekeeping retries at each launch. */
    object DatabaseKeyStillOnLegacy : RemoveResult()

    /** The erase did not verify (or did not run to the end); housekeeping finishes it at the next launch. */
    object Incomplete : RemoveResult()

    /** The typed name is not the wallet's shown name. Nothing was written. */
    object NameMismatch : RemoveResult()
}

/**
 * Removes one wallet, never another wallet's data. The entry leaves the list, and every network
 * whose active wallet it was moves to its next READY wallet, in one registry commit before anything
 * is erased. The running wallet ([activeScope]) is never erased under the live process: its database
 * and native session stay until the restart, and the next launch's housekeeping erases it. Removing
 * the last wallet on the device is the existing full wipe ([wipeAll]).
 *
 * The legacy wallet is one seed on both networks, so its stores go on both (the eraser does that).
 */
class WalletRemoval(
    private val registry: WalletRegistry,
    private val eraser: WalletStoreEraser,
    /** The wallet this process runs. */
    private val activeScope: WalletScope,
    private val wipeAll: suspend () -> WipeReport,
) {
    /** [typedName] must equal the entry's shown name (trimmed, exact). */
    suspend fun remove(id: String, typedName: String, shownName: String): RemoveResult {
        if (shownName.isBlank() || typedName.trim() != shownName.trim()) return RemoveResult.NameMismatch

        val ready = withContext(Dispatchers.IO) { (registry.list(false) + registry.list(true)).map { it.id }.distinct() }
        if (ready == listOf(id)) return RemoveResult.WipedEverything(wipeAll())

        return withContext(Dispatchers.IO) { markThenErase(id) }
    }

    private fun markThenErase(id: String): RemoveResult = try {
        val entry = registry.find(id)
        if (entry == null || !registry.markRemoving(id)) {
            Log.w(TAG, "the wallet could not be marked for removal")
            RemoveResult.Incomplete
        } else if (id == activeScope.walletId) {
            RemoveResult.RemovedRestartNeeded
        } else {
            val report = eraser.erase(WalletScope(id, entry.testnet == true))
            when {
                report.verified && registry.drop(id) -> RemoveResult.Removed
                report.dbKeyHeldLegacyKey -> RemoveResult.DatabaseKeyStillOnLegacy
                else -> {
                    Log.w(TAG, "the wallet was not fully erased; finished at the next launch")
                    RemoveResult.Incomplete
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "removal did not complete (${e.javaClass.simpleName}); finished at the next launch")
        RemoveResult.Incomplete
    }

    private companion object {
        const val TAG = "WalletRemoval"
    }
}
