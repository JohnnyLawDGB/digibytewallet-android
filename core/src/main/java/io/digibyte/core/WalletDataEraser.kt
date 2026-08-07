package io.digibyte.core

import android.content.Context
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.FilterHeaderStore

/**
 * The persistent, non-native side of a complete wallet wipe, factored behind an
 * interface so [WalletManager.wipeWallet]'s destructive routine is unit-testable
 * in pure JVM (the core module has no Robolectric — see [io.digibyte.core.security]
 * tests) and so the manual (Settings) and automatic (PIN wipe-after-N) wipe paths
 * share ONE correct routine.
 *
 * Each method targets a distinct persisted store; [WalletManager] invokes them in a
 * crash-safe order (seed ciphertext FIRST — if the process dies mid-wipe,
 * `hasSavedWallet()` already reads false so no half-wiped wallet is left loadable).
 */
interface WalletDataEraser {
    /** Clear the encrypted-seed prefs (`dgb_wallet_seed`). MUST run first. */
    fun eraseSeedCiphertext()
    /** Clear the SPV sync blob (`dgb_sync_data<net>`: blocks/peers/tx/has_synced/balance). */
    fun eraseSyncData()
    /** Clear the cached bloom-peer list (`dgb_bloom_peers<net>`). */
    fun eraseBloomPeerCache()
    /** Clear the persisted Receive-address watch set (`dgb_watched_addrs`). */
    fun eraseWatchedAddresses()
    /** Forget every locally-recorded outgoing send (`dgb_outgoing_tx`). */
    fun eraseOutgoingTx()
    /**
     * Delete every file-backed BIP158 sync artifact: the compact-filter-header chain
     * AND the compact-filter scan ledger. Both are keyed to the wallet that built
     * them, so a wipe that leaves either behind hands the next wallet another
     * wallet's scan state.
     */
    fun eraseCfSyncState()
    /** Delete the encrypted Room DB (tx/utxo/header/asset cache) + its key material. */
    fun eraseDatabase()
}

/**
 * Production [WalletDataEraser] operating on real SharedPreferences / files.
 *
 * All stores here hold regenerable, non-seed data (chain-derived caches, address
 * hints, recorded sends) plus the encrypted-seed prefs. For the wrench-attack
 * threat a security wipe must destroy the tx history / full address set too, not
 * just the seed — leaving them is a privacy leak.
 */
class AndroidWalletDataEraser(private val context: Context) : WalletDataEraser {

    private fun suffix() = networkSuffix(context)

    override fun eraseSeedCiphertext() {
        // commit() (synchronous): if the process is killed right after, the seed
        // blob is already gone so hasSavedWallet() reads false (crash-safety).
        context.getSharedPreferences("dgb_wallet_seed", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    override fun eraseSyncData() {
        context.getSharedPreferences("dgb_sync_data${suffix()}", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // The .clear() above removes the persisted display tip, but ChainTipStore mirrors it in a
        // process-lifetime field so the 5s UI poll doesn't hit disk. Without this the mirror would
        // outlive the wipe and be written straight back on the next poll — a tip from the WIPED
        // wallet reappearing under the newly restored one.
        io.digibyte.core.sync.ChainTipStore.invalidateCache()
    }

    override fun eraseBloomPeerCache() {
        context.getSharedPreferences("dgb_bloom_peers${suffix()}", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    override fun eraseWatchedAddresses() {
        context.getSharedPreferences("dgb_watched_addrs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    override fun eraseOutgoingTx() {
        OutgoingTxStore(context).clearAll()
    }

    override fun eraseCfSyncState() {
        FilterHeaderStore.delete(context)
        // The scan ledger records which heights this wallet has already had a cfilter
        // evaluated for. Carried into a different wallet it is actively wrong: heights
        // the old wallet scanned are treated as scanned for the new one, so the new
        // wallet's transactions in those blocks are never looked for.
        CfScanLedgerStore.delete(context)
    }

    override fun eraseDatabase() {
        // Mirrors io.digibyte.StaleDataWiper.wipeDatabase (app module — not importable
        // from core). Deletes the DB files + the DB passphrase (prefs + Keystore alias).
        // Does NOT touch "dgb_wallet_master": that seed key is destroyed separately via
        // KeyStoreManager.deleteKey() in wipeWallet.
        val dbFileName = "wallet${suffix()}.db"
        context.getDatabasePath(dbFileName).delete()
        context.getDatabasePath("$dbFileName-journal").delete()
        context.getDatabasePath("$dbFileName-shm").delete()
        context.getDatabasePath("$dbFileName-wal").delete()
        context.getSharedPreferences("dgb_db_key", Context.MODE_PRIVATE).edit().clear().commit()
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias("dgb_db_passphrase")) ks.deleteEntry("dgb_db_passphrase")
        } catch (e: Exception) {
            android.util.Log.w("WalletDataEraser", "Could not clean DB Keystore key: ${e.message}")
        }
    }
}
