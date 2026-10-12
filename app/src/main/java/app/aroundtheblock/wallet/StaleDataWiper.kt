package app.aroundtheblock.wallet

import android.content.Context
import app.aroundtheblock.wallet.core.networkSuffix
import app.aroundtheblock.wallet.core.sync.CfAbandonmentStore
import app.aroundtheblock.wallet.core.sync.CfRecoveryPolicy
import app.aroundtheblock.wallet.core.sync.CfScanLedgerStore
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.sync.FilterHeaderStore
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade
import app.aroundtheblock.wallet.core.sync.SavedBlockStore

/**
 * Recovers from corruption — WITHOUT ever touching the wallet seed (`dgb_wallet_seed`)
 * or the PIN store. Split into two tiers so the startup crash-loop breaker ([BootGuard])
 * can escalate gradually: try the cheap fix (sync blobs — only costs a header re-sync)
 * before the expensive one (the encrypted DB, set aside so Room creates a new one).
 *
 * The sync state is re-downloaded from the network. The encrypted DB is NOT only a cache
 * of on-chain data — it holds Digi-ID login history that cannot be rebuilt — so it is
 * never deleted here: it is set aside with its key kept. User funds live only in the
 * seed, which is never in scope.
 *
 * All prefs/DB files are network-suffixed ([networkSuffix]) so a recovery of the
 * mainnet session never touches a testnet DB/prefs on disk, and vice versa.
 * `dgb_db_key` stays unsuffixed/shared (same password encrypts either DB), and is kept.
 */
object StaleDataWiper {

    /** Cheapest recovery: drop the saved SPV sync state (blocks/peers/tx/filter
     *  headers) and the bloom-peer cache. Forces a header re-sync but nothing more.
     *  This is the prime native-deserialization crash vector (corrupt `saved_blocks`
     *  hex → BRMerkleBlock deserialization SIGSEGV in loadSavedBlocks).
     *
     *  The compact-filter state goes as the project's recovery policy says a wallet reset takes
     *  it — [CfRecoveryPolicy.Reason.WALLET_RESET] names this breaker — rather than by a list kept
     *  here: the filter-header chain and the scan ledger, with the abandoned band recorded against
     *  that ledger. Every write lands before this returns, and the result says whether all of them
     *  did (files read back); a start that dies again runs this again. Never throws: it runs first
     *  thing at start, where a throw is itself a crash on every launch. */
    fun wipeSyncBlobs(context: Context): Boolean {
        android.util.Log.w("StaleDataWiper", "Wiping saved sync state (blocks/peers/tx) — seed preserved")
        val suffix = networkSuffix(context)
        // commit(), not apply(): the risky start this breaker guards follows at once, and a clear
        // still queued when that start dies is lost — the next launch would find what it cleared.
        val syncData = clearPrefs(context, "dgb_sync_data$suffix")
        val peerCache = clearPrefs(context, "dgb_bloom_peers$suffix")

        val reset = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.WALLET_RESET)
        var filterState = true
        if (reset.dropFilterChain) {
            // FILE-BACKED (`saved_filter_headers<net>.bin`, see FilterHeaderStore) — the prefs
            // clear above never reaches it. A corrupt .bin left behind here could re-crash the very
            // next restore/re-anchor.
            FilterHeaderStore.delete(context)
            filterState = gone { FilterHeaderStore.file(context) }
        }
        if (reset.dropScanLedger) {
            // FILE-BACKED too. The abandoned band is recorded against this ledger's floor: the next
            // scan starts a new ledger at the wallet's own floor and records its own.
            CfScanLedgerStore.delete(context)
            val bandCleared = runCatching { CfAbandonmentStore.clear(context) }.getOrDefault(false)
            // With the record gone the scan has to start from the wallet's floor, and keep starting
            // there until it has a ledger again: a synced wallet has no persisted floor, so a start
            // that dies before its first ledger write would otherwise be followed by one that
            // resumes near the saved tip. The wallet is not loaded yet, so the next sync start pins
            // the floor at its anchor — the step a rebuild's floor takes.
            val floorPinned = runCatching { HistoryRebuildOnUpgrade.requestFloorAtAnchor(context) }.getOrDefault(false)
            filterState = filterState && gone { CfScanLedgerStore.file(context) } && bandCleared && floorPinned
        }
        // ...and the FILE-BACKED saved-blocks window (`saved_blocks<net>.bin`,
        // I2 fix) — same reasoning: it moved out of dgb_sync_data too, so the
        // .clear() above never touches it.
        SavedBlockStore.delete(context)
        val savedBlocks = gone { SavedBlockStore.file(context) }
        // ChainTipStore's persisted value went out with the dgb_sync_data .clear() above, but its
        // in-memory mirror did not — and this runs from BootGuard on a crash-loop recovery, in a
        // process that keeps running afterwards. Left stale, the mirror would be re-persisted on
        // the next UI poll and quietly undo the wipe.
        ChainTipStore.invalidateCache()

        val landed = syncData && peerCache && filterState && savedBlocks
        if (!landed) {
            android.util.Log.e("StaleDataWiper", "Sync state not fully cleared (sync=$syncData peers=$peerCache " +
                "filter=$filterState blocks=$savedBlocks); a start that dies again runs this again")
        }
        return landed
    }

    /** Read back: the file is not there. A file that cannot even be resolved is not confirmed gone. */
    private fun gone(file: () -> java.io.File): Boolean = runCatching { !file().exists() }.getOrDefault(false)

    private fun clearPrefs(context: Context, name: String): Boolean =
        runCatching {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }.getOrDefault(false)

    /** Heavier recovery: set the encrypted Room DB aside so a clean DB is created on next
     *  open (Room creates it under the same key). Never deleted: the DB holds what the chain
     *  cannot give back — Digi-ID login history, labels, asset metadata — so it is moved to a
     *  name of its own with its companions (`DatabaseFiles.setAside`), and its key record and
     *  Keystore key are KEPT, so the set-aside copy stays readable. Seed/PIN untouched.
     *
     *  The crash-loop breaker calls this on every start while starts keep dying; those asides
     *  are bounded (`DatabaseFiles.MAX_RECOVERY_ASIDES`), and past the bound the database is
     *  left where it is — the crash is then not the database's. A database whose re-key is pending
     *  is left where it is too: the re-key resumes on its own. Nothing aside is removed here: a full
     *  wallet wipe (WalletDataEraser.eraseDatabase) is the one place that removes asides. */
    fun wipeDatabase(context: Context) {
        // A re-key in progress resumes on its own at the next open (WalletDatabaseOpener). A start
        // killed while re-keying is exactly what this breaker counts, so it must not move that file.
        if (app.aroundtheblock.wallet.core.db.DatabaseKeyProvisioner.rekeyPending(context)) {
            android.util.Log.w("StaleDataWiper", "Encrypted DB left in place: its re-key is pending and resumes on the next open")
            return
        }
        val dbFileName = "wallet${networkSuffix(context)}.db"
        val path = context.getDatabasePath(dbFileName)
        val dir = path.parentFile ?: return
        val setAside = app.aroundtheblock.wallet.core.db.DatabaseFiles(dir, dbFileName)
            .setAside(app.aroundtheblock.wallet.core.db.AsideReason.RECOVERY)
        android.util.Log.w("StaleDataWiper", "Encrypted DB set aside=$setAside (key kept; seed preserved)")
    }

    /** Both tiers at once: the DB set aside, the sync blobs cleared.
     *  NEVER clears the PIN store or the wallet seed. */
    fun wipeAll(context: Context) {
        wipeDatabase(context)
        wipeSyncBlobs(context)
    }
}
