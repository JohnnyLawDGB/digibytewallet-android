package app.aroundtheblock.wallet

import android.content.Context
import app.aroundtheblock.wallet.core.networkSuffix
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.sync.FilterHeaderStore
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
     *  hex → BRMerkleBlock deserialization SIGSEGV in loadSavedBlocks). */
    fun wipeSyncBlobs(context: Context) {
        android.util.Log.w("StaleDataWiper", "Wiping saved sync state (blocks/peers/tx) — seed preserved")
        val suffix = networkSuffix(context)
        context.getSharedPreferences("dgb_sync_data$suffix", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("dgb_bloom_peers$suffix", Context.MODE_PRIVATE).edit().clear().apply()
        // Also nuke the FILE-BACKED compact-filter-header chain (`saved_filter_
        // headers<net>.bin`) — it moved out of dgb_sync_data (see FilterHeaderStore's
        // doc comment) so clearing the prefs blob above never touched it. A corrupt
        // .bin left behind here could re-crash the very next restore/re-anchor.
        FilterHeaderStore.delete(context)
        // ...and the FILE-BACKED saved-blocks window (`saved_blocks<net>.bin`,
        // I2 fix) — same reasoning: it moved out of dgb_sync_data too, so the
        // .clear() above never touches it.
        SavedBlockStore.delete(context)
        // ChainTipStore's persisted value went out with the dgb_sync_data .clear() above, but its
        // in-memory mirror did not — and this runs from BootGuard on a crash-loop recovery, in a
        // process that keeps running afterwards. Left stale, the mirror would be re-persisted on
        // the next UI poll and quietly undo the wipe.
        ChainTipStore.invalidateCache()
    }

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
