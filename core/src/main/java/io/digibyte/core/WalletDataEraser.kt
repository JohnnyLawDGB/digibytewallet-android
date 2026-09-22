package io.digibyte.core

import android.content.Context
import android.util.Log
import io.digibyte.core.digiscope.HubTokenStore
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.FilterHeaderStore
import io.digibyte.core.sync.SavedBlockStore

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
 *
 * Every step REPORTS: true only when every write landed, every file is gone when looked for
 * again, and every Keystore alias is absent when asked again. A wipe may only claim what it
 * checked, so no step may drop a write result on the floor (`WalletWipeSourceGateTest`).
 * Every network-suffixed store is cleared for EVERY network ([WALLET_NETWORK_SUFFIXES]), not
 * only the selected one — there is one seed, so there is one wipe.
 */
interface WalletDataEraser {
    /** Clear the encrypted-seed prefs (`dgb_wallet_seed`). MUST run first. */
    fun eraseSeedCiphertext(): Boolean
    /** Clear the SPV sync blob (`dgb_sync_data<net>`: blocks/peers/tx/has_synced/balance). */
    fun eraseSyncData(): Boolean
    /** Clear the cached bloom-peer list (`dgb_bloom_peers<net>`). */
    fun eraseBloomPeerCache(): Boolean
    /** Clear the persisted Receive-address watch set (`dgb_watched_addrs`). */
    fun eraseWatchedAddresses(): Boolean
    /** Forget every locally-recorded outgoing send (`dgb_outgoing_tx`). */
    fun eraseOutgoingTx(): Boolean
    /**
     * Delete every file-backed BIP158 sync artifact: the compact-filter-header chain,
     * the compact-filter scan ledger, AND the saved-blocks window (I2 fix — moved out
     * of `dgb_sync_data` into a file, so [eraseSyncData]'s prefs `.clear()` no longer
     * reaches it). All three are keyed to the wallet that built them, so a wipe that
     * leaves any behind hands the next wallet another wallet's scan/header state.
     */
    fun eraseCfSyncState(): Boolean
    /** Delete the encrypted Room DB (tx/utxo/header/asset cache) + its key material. */
    fun eraseDatabase(): Boolean
    /**
     * Clear the per-wallet state no other step owns: the filter-peer and Dandelion peer caches
     * (`dgb_filter_peers<net>`, `dgb_dandelion_peers<net>`), the asset history bookkeeping
     * (`dgb_asset_backfill`, `dgb_asset_heal`), the reconcile bookkeeping (`dgb_reconcile<net>`)
     * and the compact-filter scan floor (`cf_birth_height` in `dgb_settings`). The scan floor
     * belongs to the wallet that set it: the next wallet starts from its own birthday. Nothing
     * else in `dgb_settings` is wallet state — the language and the network selection stay.
     */
    fun eraseLeftoverState(): Boolean
    /** Remove the persisted Hub session token, from the encrypted store and the legacy one. */
    fun eraseHubSession(): Boolean
    /**
     * READ-BACK, deletes nothing: true only on a positive statement that neither seed key alias
     * (`dgb_wallet_master`, `dgb_wallet_master_v2`) exists. A keystore that cannot answer is
     * "not absent".
     */
    fun seedKeyAbsent(): Boolean
}

/** Every suffix a network-suffixed store or file can carry (see [networkSuffix]). */
internal val WALLET_NETWORK_SUFFIXES = listOf("", "_testnet")

/**
 * The sessions a wallet's identity opened outside the wallet's own stores — the Hub login held
 * in memory and the DigiStamp web session. They live in the app module, which `core` cannot
 * see, so [WalletManager] ends them through this seam as part of the ONE wipe routine.
 * Returns true only when every session was ended.
 */
fun interface IdentitySessionWipe {
    fun eraseIdentitySessions(): Boolean
}

/**
 * What one run of [WalletManager.wipeWallet] established.
 *
 * @property seedGone READ-BACK after the erase: the seed store's write landed, no saved wallet
 *   is readable, and neither seed key alias exists. Never inferred from "no step threw".
 * @property everythingCleared every other store reported cleared (both networks), the identity
 *   sessions ended, and the native session stopped.
 */
data class WipeReport(val seedGone: Boolean, val everythingCleared: Boolean) {
    /** The only result an entry point may treat as "the wallet was wiped". */
    val verified: Boolean get() = seedGone && everythingCleared
}

/** The two Keystore operations the eraser needs, behind a seam so it runs on a plain JVM. */
internal interface KeystoreAliases {
    fun contains(alias: String): Boolean
    fun delete(alias: String)
}

internal object AndroidKeystoreAliases : KeystoreAliases {
    private fun keyStore() = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun contains(alias: String): Boolean = keyStore().containsAlias(alias)
    override fun delete(alias: String) { keyStore().deleteEntry(alias) }
}

/**
 * Production [WalletDataEraser] operating on real SharedPreferences / files.
 *
 * All stores here hold regenerable, non-seed data (chain-derived caches, address
 * hints, recorded sends) plus the encrypted-seed prefs. For the wrench-attack
 * threat a security wipe must destroy the tx history / full address set too, not
 * just the seed — leaving them is a privacy leak.
 */
class AndroidWalletDataEraser internal constructor(
    private val context: Context,
    private val keystore: KeystoreAliases,
    private val hubTokenStore: () -> HubTokenStore,
) : WalletDataEraser {

    constructor(context: Context) : this(context, AndroidKeystoreAliases, { HubTokenStore(context) })

    /**
     * Clear one preferences file: true only when the write LANDED. The in-memory view of a
     * preferences file shows an edit whether or not it reached the disk, so for these stores the
     * write result is the evidence and a read-back would add nothing; the seed is read back
     * separately, by [WalletManager.wipeWallet].
     */
    private fun clearPrefs(name: String): Boolean = step("clear $name") {
        val landed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        landed
    }

    /** [clearPrefs] for [base] on EVERY network. All are attempted even when one refuses. */
    private fun clearPrefsOnEveryNetwork(base: String): Boolean =
        WALLET_NETWORK_SUFFIXES.map { net -> clearPrefs(base + net) }.all { it }

    /** Delete one file: judged by reading back that it is gone, since delete() of a missing file is false. */
    private fun deleteFile(file: java.io.File): Boolean = step("delete ${file.name}") {
        file.delete()
        !file.exists()
    }

    /** A step that throws has not reported success. Logged by exception class only. */
    private fun step(what: String, block: () -> Boolean): Boolean = try {
        block().also { ok -> if (!ok) Log.w(TAG, "$what: not confirmed") }
    } catch (t: Throwable) {
        Log.w(TAG, "$what: ${t.javaClass.simpleName}")
        false
    }

    override fun eraseSeedCiphertext(): Boolean =
        // commit() (synchronous): if the process is killed right after, the seed
        // blob is already gone so hasSavedWallet() reads false (crash-safety).
        // NOT suffixed: one seed serves both networks.
        clearPrefs("dgb_wallet_seed")

    override fun eraseSyncData(): Boolean {
        val cleared = clearPrefsOnEveryNetwork("dgb_sync_data")
        // The .clear() above removes the persisted display tip, but ChainTipStore mirrors it in a
        // process-lifetime field so the 5s UI poll doesn't hit disk. Without this the mirror would
        // outlive the wipe and be written straight back on the next poll — a tip from the WIPED
        // wallet reappearing under the newly restored one.
        io.digibyte.core.sync.ChainTipStore.invalidateCache()
        return cleared
    }

    override fun eraseBloomPeerCache(): Boolean = clearPrefsOnEveryNetwork("dgb_bloom_peers")

    override fun eraseWatchedAddresses(): Boolean = clearPrefs("dgb_watched_addrs")

    // The same preferences file [OutgoingTxStore] writes. Cleared here rather than through
    // OutgoingTxStore.clearAll(), which has no result to report.
    override fun eraseOutgoingTx(): Boolean = clearPrefs("dgb_outgoing_tx")

    override fun eraseCfSyncState(): Boolean {
        val headerChain = step("filter-header chain") { FilterHeaderStore.deleteAllNetworks(context) }
        // The scan ledger records which heights this wallet has already had a cfilter
        // evaluated for. Carried into a different wallet it is actively wrong: heights
        // the old wallet scanned are treated as scanned for the new one, so the new
        // wallet's transactions in those blocks are never looked for.
        val scanLedger = step("scan ledger") { CfScanLedgerStore.deleteAllNetworks(context) }
        // The saved-blocks window (I2 fix) is now file-backed too — eraseSyncData()'s
        // dgb_sync_data .clear() no longer reaches it, so it must be deleted here.
        // Left behind, the next wallet would restore against a header window built
        // from a DIFFERENT wallet's chain position.
        val savedBlocks = step("saved blocks") { SavedBlockStore.deleteAllNetworks(context) }
        // The abandoned-band record describes the scan of the wallet that made it.
        val abandonedBand = step("abandoned band") { CfAbandonmentStore.clearAllNetworks(context) }
        return headerChain && scanLedger && savedBlocks && abandonedBand
    }

    override fun eraseDatabase(): Boolean {
        // Mirrors io.digibyte.StaleDataWiper.wipeDatabase (app module — not importable
        // from core). Deletes the DB files + the DB passphrase (prefs + Keystore alias).
        // Does NOT touch "dgb_wallet_master": that seed key is destroyed separately via
        // KeyStoreManager.deleteKey() in wipeWallet.
        // BOTH networks' databases go: they share the one passphrase deleted below, so a
        // database left behind could never be opened again anyway.
        val files = WALLET_NETWORK_SUFFIXES.flatMap { net ->
            listOf("", "-journal", "-shm", "-wal").map { context.getDatabasePath("wallet$net.db$it") }
        }.map(::deleteFile).all { it }
        val passphrase = clearPrefs("dgb_db_key")
        val passphraseKey = step("database key alias") {
            if (keystore.contains(DB_KEY_ALIAS)) keystore.delete(DB_KEY_ALIAS)
            !keystore.contains(DB_KEY_ALIAS)
        }
        return files && passphrase && passphraseKey
    }

    override fun eraseLeftoverState(): Boolean {
        val filterPeers = clearPrefsOnEveryNetwork("dgb_filter_peers")
        val dandelionPeers = clearPrefsOnEveryNetwork("dgb_dandelion_peers")
        val reconcile = clearPrefsOnEveryNetwork("dgb_reconcile")
        val assetBackfill = clearPrefs("dgb_asset_backfill")
        val assetHeal = clearPrefs("dgb_asset_heal")
        // ONE key, not the file: dgb_settings also holds the language and the network selection.
        val scanFloor = step("scan floor") {
            val landed = context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
                .edit().remove("cf_birth_height").commit()
            landed
        }
        return filterPeers && dandelionPeers && reconcile && assetBackfill && assetHeal && scanFloor
    }

    override fun eraseHubSession(): Boolean = step("hub session") { hubTokenStore().clearConfirmed() }

    override fun seedKeyAbsent(): Boolean = step("wallet key read-back") {
        !keystore.contains(KeyStoreManager.KEY_ALIAS) &&
            !keystore.contains(KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX)
    }

    private companion object {
        const val TAG = "WalletDataEraser"
        const val DB_KEY_ALIAS = "dgb_db_passphrase"
    }
}
