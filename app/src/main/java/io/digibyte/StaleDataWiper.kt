package io.digibyte

import android.content.Context
import io.digibyte.core.networkSuffix
import io.digibyte.core.sync.ChainTipStore
import io.digibyte.core.sync.FilterHeaderStore
import io.digibyte.core.sync.SavedBlockStore

/**
 * Wipes regenerable app data to recover from corruption — WITHOUT ever touching
 * the wallet seed (`dgb_wallet_seed`) or the PIN store. Split into two tiers so
 * the startup crash-loop breaker ([BootGuard]) can escalate gradually: try the
 * cheap fix (sync blobs — only costs a header re-sync) before the expensive one
 * (the encrypted DB — Room re-creates it via fallbackToDestructiveMigration).
 *
 * Everything wiped here is either re-downloaded from the network (sync state) or
 * rebuilt locally (the DB is a cache of on-chain data). User funds live only in
 * the seed, which is never in scope.
 *
 * All prefs/DB files are network-suffixed ([networkSuffix]) so wiping the
 * mainnet session never touches a testnet DB/prefs on disk, and vice versa.
 * `dgb_db_key` stays unsuffixed/shared (same passphrase encrypts either DB).
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

    /** Heavier recovery: delete the encrypted Room DB and its key material so a
     *  clean DB is created on next open. The DB is a local cache (txs/utxos/headers/
     *  asset metadata), all rebuildable from chain + network. Seed/PIN untouched. */
    fun wipeDatabase(context: Context) {
        android.util.Log.w("StaleDataWiper", "Wiping encrypted DB + key (seed preserved)")
        val dbFileName = "wallet${networkSuffix(context)}.db"
        context.getDatabasePath(dbFileName).delete()
        context.getDatabasePath("$dbFileName-journal").delete()
        context.getDatabasePath("$dbFileName-shm").delete()
        context.getDatabasePath("$dbFileName-wal").delete()
        context.getSharedPreferences("dgb_db_key", Context.MODE_PRIVATE).edit().clear().apply()
        // Delete ONLY the DB-dedicated Keystore key.
        //
        // CRITICAL: never delete "dgb_wallet_master" — KeyStoreManager.KEY_ALIAS
        // is that alias, and WalletManager.persistSeed/loadSeed use it to
        // encrypt/decrypt the wallet SEED. Deleting it would leave the preserved
        // seed blob permanently UNDECRYPTABLE — bricking the wallet even though
        // the blob survives (funds lost unless the user has the recovery phrase).
        // The regenerated DB passphrase re-encrypts under the still-present
        // dgb_wallet_master (provideDatabaseInner's new-install path), so the DB
        // recovers without ever touching the seed's key. (The original
        // wipeStaleData deleted dgb_wallet_master — a latent seed-loss bug.)
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias("dgb_db_passphrase")) {
                ks.deleteEntry("dgb_db_passphrase")
                android.util.Log.w("StaleDataWiper", "Deleted stale DB Keystore key: dgb_db_passphrase")
            }
        } catch (e: Exception) {
            android.util.Log.w("StaleDataWiper", "Could not clean Keystore: ${e.message}")
        }
    }

    /** Full wipe of regenerable data (DB + sync blobs). Used by the DB-init
     *  exception path in AppModule — a DB failure warrants clearing both.
     *  NEVER clears the PIN store or the wallet seed. */
    fun wipeAll(context: Context) {
        wipeDatabase(context)
        wipeSyncBlobs(context)
    }
}
