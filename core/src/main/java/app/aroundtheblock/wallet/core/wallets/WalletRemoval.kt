package app.aroundtheblock.wallet.core.wallets

import android.util.Log
import app.aroundtheblock.wallet.core.WipeReport
import app.aroundtheblock.wallet.core.digiscope.HubSessionRevoke
import kotlin.coroutines.cancellation.CancellationException
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

    /**
     * The wallet is marked REMOVING (unlisted) but its erase did not verify or did not run to the
     * end; housekeeping finishes it at the next launch.
     */
    object Incomplete : RemoveResult()

    /** Nothing was written: the wallet is unchanged and still listed. The user can retry. */
    object NotRemoved : RemoveResult()

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
    /** Ends a removed wallet's Hub session on the server, best effort, before its token is erased. */
    private val revokeHub: HubSessionRevoke = HubSessionRevoke.NONE,
) {
    /**
     * [typedName] must equal the entry's shown name (trimmed, exact).
     * Callers must not run an add and a remove concurrently: the last-wallet check is outside the registry lock.
     */
    suspend fun remove(id: String, typedName: String, shownName: String): RemoveResult {
        if (shownName.isBlank() || typedName.trim() != shownName.trim()) return RemoveResult.NameMismatch

        val ready = withContext(Dispatchers.IO) { (registry.list(false) + registry.list(true)).map { it.id }.distinct() }
        if (ready == listOf(id)) return RemoveResult.WipedEverything(wipeAll())

        return withContext(Dispatchers.IO) { markThenErase(id) }
    }

    private fun markThenErase(id: String): RemoveResult {
        val entry = try {
            registry.find(id)?.takeIf { registry.markRemoving(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "marking for removal failed (${e.javaClass.simpleName})")
            null
        }
        if (entry == null) return notMarked(id)
        if (id == activeScope.walletId) return RemoveResult.RemovedRestartNeeded
        val scope = WalletScope(id, entry.testnet == true)
        // While its token is still readable. Its outcome decides nothing.
        try {
            revokeHub.revoke(scope)
        } catch (e: Exception) {
            Log.w(TAG, "Hub session not revoked (${e.javaClass.simpleName})")
        }
        return try {
            val report = eraser.erase(scope)
            when {
                report.verified && registry.drop(id) -> RemoveResult.Removed
                report.dbKeyHeldLegacyKey -> RemoveResult.DatabaseKeyStillOnLegacy
                else -> {
                    Log.w(TAG, "the wallet was not fully erased; finished at the next launch")
                    RemoveResult.Incomplete
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "removal did not complete (${e.javaClass.simpleName}); finished at the next launch")
            RemoveResult.Incomplete
        }
    }

    /** The mark did not land in this call. Already REMOVING (a double submit) is housekeeping's to finish. */
    private fun notMarked(id: String): RemoveResult {
        val state = try { registry.find(id)?.state } catch (_: Exception) { null }
        if (state == EntryState.REMOVING) return RemoveResult.Incomplete
        Log.w(TAG, "the wallet could not be marked for removal; nothing was written")
        return RemoveResult.NotRemoved
    }

    private companion object {
        const val TAG = "WalletRemoval"
    }
}
