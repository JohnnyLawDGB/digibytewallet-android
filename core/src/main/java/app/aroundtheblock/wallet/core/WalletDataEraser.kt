package app.aroundtheblock.wallet.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.settings.AutoLockPrefs
import app.aroundtheblock.wallet.core.sync.CfAbandonmentStore
import app.aroundtheblock.wallet.core.sync.CfScanLedgerStore
import app.aroundtheblock.wallet.core.sync.FilterHeaderStore
import app.aroundtheblock.wallet.core.sync.SavedBlockStore
import app.aroundtheblock.wallet.core.sync.SavedTransactionsStore
import app.aroundtheblock.wallet.core.wallets.StoreScope
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wallets.prefsFileOnDisk
import app.aroundtheblock.wallet.core.wallets.prefsFilesOf
import app.aroundtheblock.wallet.core.wallets.storageSuffix

/**
 * The persistent, non-native side of a complete wallet wipe, factored behind an
 * interface so [WalletManager.wipeWallet]'s destructive routine is unit-testable
 * in pure JVM (the core module has no Robolectric — see [app.aroundtheblock.wallet.core.security]
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
 * only the selected one — the legacy wallet's one seed serves both.
 *
 * There is one wipe for every wallet: one PIN guards them all. These steps are the `legacy`
 * wallet's stores (the names 4.1.4 used) and the device-wide ones the wipe also clears (the
 * database password's record, the peer caches); [WalletManager.wipeWallet] erases every other
 * wallet through `WalletStoreEraser`'s phases, and deletes and reads back every wallet's seed key
 * aliases itself. `WalletDataEraserReachTest` holds these steps to every per-wallet store in
 * [WalletStores].
 *
 * The names multi-wallet step 2 reserves go here too, legacy's and the device-wide one
 * (WIPE-RESERVED-NAMES): no build writes them yet, and a wipe that left one would leave it for a
 * later downgrade or rollback to find. Legacy's band prefs go with the sync data, its band files
 * with the compact-filter state, its key record and the alias that wraps the key records with the
 * leftover state. Each is looked for by its exact name, deleted (a prefs file with its backup, not
 * only cleared) and read back gone; one that is not there costs nothing and creates no file.
 *
 * A verified wipe leaves no preferences file that a wipe made (WIPE-WRITES-NOTHING), this run or an
 * earlier one that could not finish. Three rules give that, all in [creatingNoPrefsFile]:
 *  - no step commits to a store that holds nothing, since a commit to a store with no file writes
 *    an empty one;
 *  - a file a step did make is removed and read back gone before the step answers, whether the
 *    step succeeded or not; one that will not go makes the step false;
 *  - the file of a store the wipe has emptied is deleted and read back gone, whoever made it. This
 *    is how a later wipe deals with a file an earlier one made and could not remove: the wipe keeps
 *    no list of what it made (a list would be one more store to leave behind), it decides by what a
 *    store holds. An empty preferences file and no file read the same, so nothing is lost with it.
 * Whether the seed is gone never waits on a file's removal: the legacy seed record's emptied file
 * is deleted with the stores ([eraseLeftoverState]), not by the step that clears the record.
 *
 * So after a verified wipe, the preferences files the wipe has written and left are these, and no
 * others. This is the one list; `WipeWritesNothingTest` holds the core half of it by name.
 *  1. `dgb_price<net>`: gains the display currency, and is made if it was not there, when legacy's
 *     sync data on that network held one and this file held none. The currency is a device setting
 *     a wipe keeps (owner decision, 2026-11-12). It is copied here before the sync data is cleared
 *     (`DisplayCurrencyPrefs.copyFromLegacy`); the sync data itself is not written for it.
 *  2. `dgb_settings`: device settings (the language, the network, the own node). The wipe takes one
 *     key out, the scan floor, and keeps the file; when this process holds it with no file on the
 *     device, that removal writes the file and it stays.
 *  3. Legacy's encrypted Hub store `dgb_hub_session`, only when it was on the device before: the
 *     token is taken out and the file, which then holds the store's keyset and nothing else, is
 *     kept. The wipe never makes this file: `HubTokenStore` opens the encrypted store only when it
 *     can hold something, the revoke reads a token where it is and does not build the Hub client
 *     (`StoredHubSessionRevoke`), and the client the identity step logs out is built, if nothing
 *     built it before, after the token is erased, when its load finds nothing to move.
 *  4. The app module's two marks, which core cannot see: `dgb_web_session_owner`, set to `wiped` by
 *     the identity step (`IdentitySessionEraser`), and `dgb_erasure_owed`, written before the wipe
 *     starts and emptied, not deleted, once a wipe verifies (`FreshStartAfterWipe`).
 * Every other preferences store the wipe opens has no file afterwards: the stores these steps
 * empty, the registry `dgb_wallets` and the record `dgb_wipe_owed` ([WalletManager.wipeWallet]),
 * and every other wallet's stores (`WalletStoreEraser`).
 *
 * Outside that rule: files that are not preferences. The wipe deletes the databases and the sync
 * files and reads them back gone, as above; the PIN store's sealed file is cleared after the
 * verdict, by [WalletManager.wipeThenReleasePin], and a clear that does not land takes the verdict
 * back; the WebView's data is the app module's.
 *
 * `WipeWritesNothingTest` lists the data directory before and after, over every starting state it
 * can build, and again after a second and a third wipe. It runs on a JVM over a model of the
 * platform's preferences; the PIN store's file, the database's open, the WebView's data and the
 * app module's two marks are not in it.
 */
interface WalletDataEraser {
    /**
     * Clear the encrypted-seed prefs (`dgb_wallet_seed`). MUST run first. True when the clear landed
     * and no file was made by it; the emptied file itself goes in [eraseLeftoverState].
     */
    fun eraseSeedCiphertext(): Boolean
    /**
     * Clear the SPV sync blob (`dgb_sync_data<net>`: blocks/peers/tx/has_synced/balance), and delete
     * the reserved prefs of legacy's own band (`dgb_sync_data_legacy<net>`) on both networks.
     */
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
     * leaves any behind hands the next wallet another wallet's scan/header state. Also the
     * reserved files of legacy's own band, on both networks (`saved_filter_headers_legacy<net>.bin`,
     * `saved_cf_ledger_legacy<net>.bin`, `saved_blocks_legacy<net>.bin`, each with its `.bin.tmp`).
     */
    fun eraseCfSyncState(): Boolean
    /**
     * Delete every file of the encrypted Room DB (tx/utxo/header/asset cache, Digi-ID history) on
     * every network — the live one, its companions, and every copy recovery set aside — and its key
     * material. True only when none of those files remains when looked for again.
     */
    fun eraseDatabase(): Boolean
    /**
     * Clear the per-wallet state no other step owns: the filter-peer and Dandelion peer caches
     * (`dgb_filter_peers<net>`, `dgb_dandelion_peers<net>`), the asset history bookkeeping
     * (`dgb_asset_backfill`, `dgb_asset_heal`), the quick-start choice (`dgb_quickstart`), the
     * reconcile bookkeeping (`dgb_reconcile<net>`)
     * with the one-time history rebuild state (`dgb_history_rebuild<net>`),
     * and the compact-filter scan floor (`cf_birth_height` in `dgb_settings`). The scan floor
     * belongs to the wallet that set it: the next wallet starts from its own birthday. Nothing
     * else in `dgb_settings` is wallet state — the language and the network selection stay.
     * Also the auto-lock timeout ([AutoLockPrefs], `dgb_auto_lock`): app-wide lock policy that goes
     * with the one PIN, as it went with the wallet database before it was app-wide.
     * Also the two reserved names of the key records: legacy's `dgb_wallet_keys` (deleted, not only
     * cleared) and the Keystore alias `dgb_wallet_keys_wrap`. That alias is not a seed key (it will
     * wrap public master keys, for every wallet at once), so it goes here, with the stores, and
     * never in the seed phases: no seed record or seed key waits on it, and it is not part of
     * "the seed is gone".
     * Also the file of legacy's seed record (`dgb_wallet_seed`), once [eraseSeedCiphertext] has
     * emptied it: deleted here and read back gone, so that a file that will not go makes the wipe
     * unverified without saying the seed is still on the device. A record that still holds a seed
     * is left exactly as it is.
     */
    fun eraseLeftoverState(): Boolean
    /** Remove the persisted Hub session token, from the encrypted store and the legacy one. */
    fun eraseHubSession(): Boolean
}

/** Every network suffix the full wipe walks for the `legacy` wallet (see [storageSuffix]). */
internal val WALLET_NETWORK_SUFFIXES: List<String> =
    listOf(false, true).map { storageSuffix(WalletScope.legacy(it), StoreScope.NETWORK) }

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
 * Something that runs the wallet's session in this process — the sync service — and so writes the
 * stores a wipe erases. It registers with [WalletManager] for as long as it runs
 * ([WalletManager.addSessionStop]), and every wipe stops each one BEFORE it erases anything, so
 * nothing the session holds of the wiped wallet is written after the erase.
 *
 * Called on the wiping thread. Returns once the session has stopped writing, within a bound.
 */
fun interface WalletSessionStop {
    fun stopForWipe()
}

/**
 * What one run of [WalletManager.wipeWallet] established.
 *
 * @property seedGone READ-BACK after the erase, over EVERY wallet on the device: every seed
 *   record's write landed, no wallet's seed record is readable, and no seed key alias of any
 *   wallet exists. Never inferred from "no step threw".
 * @property everythingCleared every other store of every wallet reported cleared (both networks),
 *   the registry was cleared, the identity sessions ended, every session running for the wallet
 *   stopped, and the native session stopped. It also says that no preferences file a wipe made is
 *   left, this run's or an earlier one's (WIPE-WRITES-NOTHING): every store the wipe emptied has no
 *   file, read back. The preferences files a verified wipe has written and left are the four named
 *   on [WalletDataEraser] and no others: `dgb_price<net>`, `dgb_settings`, legacy's `dgb_hub_session`
 *   when it was already on the device, and the app module's marks `dgb_web_session_owner` and
 *   `dgb_erasure_owed`. That list, and the reason for each, is there.
 */
data class WipeReport(val seedGone: Boolean, val everythingCleared: Boolean) {
    /** The only result an entry point may treat as "the wallet was wiped". */
    val verified: Boolean get() = seedGone && everythingCleared
}

/** The Keystore operations the erasers and the wipe need, behind a seam so they run on a plain JVM. */
interface KeystoreAliases {
    fun contains(alias: String): Boolean
    fun delete(alias: String)
    /** Every alias the Keystore holds now. */
    fun aliases(): List<String>
}

internal object AndroidKeystoreAliases : KeystoreAliases {
    private fun keyStore() = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun contains(alias: String): Boolean = keyStore().containsAlias(alias)
    override fun delete(alias: String) { keyStore().deleteEntry(alias) }
    override fun aliases(): List<String> = keyStore().aliases().toList()
}

/**
 * After a write to [store] that did not land, put [held] — what the view showed before that
 * write, which is what the file still holds — back into the view. The rewrite carries the same
 * content the file has, so whether or not it lands, the file and this process agree.
 */
internal fun readWhatTheFileHolds(tag: String, name: String, store: SharedPreferences, held: Map<String, *>) {
    val editor = store.edit().clear()
    for ((key, value) in held) {
        when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    val rewritten = editor.commit()
    Log.w(tag, "$name: write not confirmed; this process reads what the file holds (rewrite landed=$rewritten)")
}

/**
 * Clear one preferences file: true only when the write LANDED. The in-memory view of a
 * preferences file takes an edit before the file does, so for these stores the write result is
 * the evidence and a read-back would add nothing; the seed is read back separately, by
 * [WalletManager.wipeWallet].
 *
 * A store that holds nothing is not written: a commit to a store with no file creates an empty one.
 *
 * A write that did not land leaves the file as it was, and this process goes on reading what
 * the file holds: the view is put back to what it showed before the clear. A seed store that
 * refused its clear therefore still reads as the wallet it holds, so the PIN that opens that
 * wallet can load it in this same process — never an empty store over a record still on disk.
 */
internal fun clearHeldPrefs(context: Context, tag: String, name: String): Boolean {
    val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val held = store.all
    if (held.isEmpty()) return true
    val landed = store.edit().clear().commit()
    if (!landed) readWhatTheFileHolds(tag, name, store, held)
    return landed
}

/**
 * Runs [erase] over the preferences stores [names] and leaves no file a wipe made
 * (WIPE-WRITES-NOTHING): true only when [erase] reported its stores cleared AND, looked for again,
 * no file is on the device for a store whose file was not there before, nor for a store that now
 * holds nothing.
 *
 * Opening a preferences file creates nothing, and the steps do not commit to a store that holds
 * nothing. A file can still appear: a store this process shows values for while its file is not
 * on the device is cleared by a commit, which writes an empty file; an encrypted store writes its
 * keyset when it is opened; and after a write that did not land, the rewrite of what the store
 * held can land where there was no file. Such a file is one the wipe made, so it is removed and
 * read back gone, whatever [erase] reported:
 *
 *  - behind an erase that cleared its stores, with the platform's delete, which also lets go of
 *    the emptied store;
 *  - behind an erase that did not (one store of several refused, or the step threw), by taking
 *    the file away and nothing else, so the device and this process are both as the step found
 *    them: a store this process held with no file is held still, and the next run clears it.
 *
 * A removal can be refused, and the wipe keeps no list of the files it made: a list would be one
 * more store to leave behind, and a write that can fail. The next run decides by what a store
 * holds instead ([emptiedFilesGo]): behind an erase that cleared its stores, the file of a store
 * that holds nothing is deleted and read back gone, whether this run made it, an earlier one did,
 * or it was the device's own. An empty preferences file and no file read the same to every reader,
 * so nothing is lost with it; and a file an earlier wipe made is either empty (its clear landed)
 * or is emptied by this run's clear, so no later wipe can verify with it on the device. A store
 * that still holds something after the erase (an encrypted store's keyset; keys another step
 * owns) keeps its file unless this run made it.
 *
 * [emptiedFilesGo] is false for the one step the seed's verdict waits on, the legacy seed record's
 * clear: that record is gone when its clear has landed, and a file that will not go must not say
 * otherwise. Its emptied file is deleted with the stores ([WalletDataEraser.eraseLeftoverState]).
 *
 * What cannot be looked for counts as present, as everywhere in the eraser.
 */
internal fun creatingNoPrefsFile(
    context: Context,
    tag: String,
    names: List<String>,
    emptiedFilesGo: Boolean = true,
    erase: () -> Boolean,
): Boolean {
    val absentBefore = names.filter { name -> runCatching { !prefsFileOnDisk(context, name) }.getOrDefault(false) }
    val erased = runCatching(erase)
    val cleared = erased.getOrDefault(false)
    val noneLeft = names.map { name ->
        try {
            val made = name in absentBefore
            if (!prefsFileOnDisk(context, name)) return@map true
            when {
                !cleared && made -> prefsFilesOf(context, name).forEach { it.delete() }
                !cleared -> return@map true
                made || (emptiedFilesGo && context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty()) ->
                    context.deleteSharedPreferences(name)
                else -> return@map true
            }
            (!prefsFileOnDisk(context, name)).also { gone -> if (!gone) Log.w(tag, "remove the file of $name: not confirmed") }
        } catch (t: Throwable) {
            Log.w(tag, "remove the file of $name: ${t.javaClass.simpleName}")
            false
        }
    }.all { it }
    return erased.getOrThrow() && noneLeft
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

    /**
     * The Hub session step erases LEGACY's stores (`dgb_digiscope`, `dgb_hub_session`), never the
     * active wallet's: with another wallet active, the store `HubTokenStore(context)` resolves is
     * that wallet's (erased by `WalletStoreEraser`), and legacy's token would survive for the next
     * wallet made as `legacy` to inherit. The Hub stores are not network-suffixed, so one scope
     * names them on both networks.
     */
    constructor(context: Context) : this(
        context,
        AndroidKeystoreAliases,
        { HubTokenStore(context, WalletScope.legacy(false)) },
    )

    /** [clearHeldPrefs] as a step of this eraser, leaving no file of the emptied store ([creatingNoFile]). */
    private fun clearPrefs(name: String): Boolean = creatingNoFile(listOf(name)) {
        step("clear $name") { clearHeldPrefs(context, TAG, name) }
    }

    private fun creatingNoFile(names: List<String>, erase: () -> Boolean): Boolean =
        creatingNoPrefsFile(context, TAG, names, erase = erase)

    /**
     * Clear [name] with a checked commit, then DELETE its file and backup, and read back that
     * neither is on the device: for a name the next wallet must not find at all, where a cleared,
     * empty file would still be found. A clear that did not land leaves the file, and this process
     * reading what it holds. A store that is not there is not committed to (which would create its
     * file); the delete and the read-back still run.
     */
    private fun clearAndDeletePrefs(name: String): Boolean = step("erase $name") {
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val held = store.all
        val landed = held.isEmpty() || store.edit().clear().commit()
        if (!landed) {
            readWhatTheFileHolds(TAG, name, store, held)
            return@step false
        }
        // Judged by the read-back, as [deleteFile] is: the platform's own answer is that same look.
        context.deleteSharedPreferences(name)
        !prefsFileOnDisk(context, name)
    }

    /** Legacy on each network: the scopes whose reserved band names the full wipe erases. */
    private val legacyOnEveryNetwork = listOf(false, true).map(WalletScope::legacy)

    /** The reserved prefs of legacy's own band, both networks. All are attempted even when one refuses. */
    private fun eraseReservedBandPrefs(): Boolean = legacyOnEveryNetwork
        .flatMap { scope -> WalletStores.BAND_PREFS.map { WalletStores.bandPrefsName(it, scope) } }
        .map(::clearAndDeletePrefs).all { it }

    /** The reserved files of legacy's own band, both networks, each with its `.tmp`. All are attempted. */
    private fun eraseReservedBandFiles(): Boolean = legacyOnEveryNetwork
        .flatMap { scope -> WalletStores.BAND_FILES.map { WalletStores.bandFileName(it, scope) } }
        .flatMap { name -> listOf(java.io.File(context.filesDir, name), java.io.File(context.filesDir, name + TMP)) }
        .map(::deleteFile).all { it }

    /** Legacy's reserved key record, and the alias that wraps every wallet's. Both are attempted. */
    private fun eraseReservedKeyRecord(): Boolean {
        val record = WalletStores.RESERVED_WALLET_PREFS
            .map { clearAndDeletePrefs(WalletStores.prefsName(it, WalletScope.legacy(false))) }.all { it }
        val wrapAlias = step("key record alias") {
            if (keystore.contains(WalletStores.KEYS_WRAP_ALIAS)) keystore.delete(WalletStores.KEYS_WRAP_ALIAS)
            !keystore.contains(WalletStores.KEYS_WRAP_ALIAS)
        }
        return record && wrapAlias
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
        // The emptied file is not deleted here but with the stores (eraseLeftoverState): whether the
        // seed record is gone must not wait on a file's removal.
        creatingNoPrefsFile(context, TAG, listOf(SEED_RECORD), emptiedFilesGo = false) {
            step("clear $SEED_RECORD") { clearHeldPrefs(context, TAG, SEED_RECORD) }
        }

    override fun eraseSyncData(): Boolean {
        // The display currency is a device setting that used to live in this file, and a wipe keeps
        // it (owner decision, 2026-11-12): on both networks it is copied to its own file before the
        // clear. The copy writes `dgb_price<net>` and nothing else; the old file is looked at before
        // it and cleared after it, in one step, so what that step leaves is judged as a whole.
        val cleared = WALLET_NETWORK_SUFFIXES.map { net ->
            val name = "dgb_sync_data$net"
            creatingNoFile(listOf(name)) {
                step("clear $name") {
                    app.aroundtheblock.wallet.core.settings.DisplayCurrencyPrefs.copyFromLegacy(context, net)
                    clearHeldPrefs(context, TAG, name)
                }
            }
        }.all { it }
        // The .clear() above removes the persisted display tip, but ChainTipStore mirrors it in a
        // process-lifetime field so the 5s UI poll doesn't hit disk. Without this the mirror would
        // outlive the wipe and be written straight back on the next poll — a tip from the WIPED
        // wallet reappearing under the newly restored one.
        app.aroundtheblock.wallet.core.sync.ChainTipStore.invalidateCache()
        // Reserved for step 2: legacy's own band keeps its chain keys under these names.
        val reservedBand = eraseReservedBandPrefs()
        return cleared && reservedBand
    }

    override fun eraseBloomPeerCache(): Boolean = clearPrefsOnEveryNetwork("dgb_bloom_peers")

    override fun eraseWatchedAddresses(): Boolean = clearPrefs("dgb_watched_addrs")

    // The same preferences file [OutgoingTxStore] writes. Cleared here rather than through
    // OutgoingTxStore.clearAll(), which has no result to report.
    override fun eraseOutgoingTx(): Boolean = clearPrefs("dgb_outgoing_tx")

    override fun eraseCfSyncState(): Boolean {
        val syncData = legacyOnEveryNetwork.map { WalletStores.prefsName("dgb_sync_data", it) }
        val headerChain = creatingNoFile(syncData) { step("filter-header chain") { FilterHeaderStore.deleteAllNetworks(context) } }
        // The scan ledger records which heights this wallet has already had a cfilter
        // evaluated for. Carried into a different wallet it is actively wrong: heights
        // the old wallet scanned are treated as scanned for the new one, so the new
        // wallet's transactions in those blocks are never looked for.
        val scanLedger = step("scan ledger") { CfScanLedgerStore.deleteAllNetworks(context) }
        // The saved-blocks window (I2 fix) is now file-backed too — eraseSyncData()'s
        // dgb_sync_data .clear() no longer reaches it, so it must be deleted here.
        // Left behind, the next wallet would restore against a header window built
        // from a DIFFERENT wallet's chain position.
        val savedBlocks = creatingNoFile(syncData) { step("saved blocks") { SavedBlockStore.deleteAllNetworks(context) } }
        // The transaction checkpoint (TX-CKPT-CAP) is a file per network too: the next wallet must not
        // restore this one's history.
        val savedTransactions = creatingNoFile(syncData) { step("saved transactions") { SavedTransactionsStore.deleteAllNetworks(context) } }
        // The abandoned-band record describes the scan of the wallet that made it. Its store, and the
        // sync data the stores above keep their legacy keys in, are opened here by name: none of
        // those files may be on the device afterwards unless it was there before.
        val abandonedBand = creatingNoFile(legacyOnEveryNetwork.map { WalletStores.prefsName("dgb_cf_abandonment", it) }) {
            step("abandoned band") { CfAbandonmentStore.clearAllNetworks(context) }
        }
        // Reserved for step 2: the files of legacy's own band, a scan like the ones above.
        val reservedBand = eraseReservedBandFiles()
        return headerChain && scanLedger && savedBlocks && savedTransactions && abandonedBand && reservedBand
    }

    override fun eraseDatabase(): Boolean {
        // Deletes every DB file + the DB password (prefs + Keystore alias).
        // Does NOT touch "dgb_wallet_master": every wallet's seed key is deleted separately, by
        // wipeWallet, once every seed record is gone.
        // BOTH networks' databases go, and every copy recovery set aside
        // (DatabaseFiles.setAside — recovery never removes those; this wipe is the one place that
        // does). Most asides could not be opened once the password below is gone, but one set
        // aside under the published legacy password could be read by anyone with the file.
        // A directory that cannot be listed is not one with nothing left in it: both steps fail.
        val files = databaseFiles()?.map(::deleteFile)?.all { it } ?: false
        val passphrase = clearPrefs("dgb_db_key")
        val passphraseKey = step("database key alias") {
            if (keystore.contains(DB_KEY_ALIAS)) keystore.delete(DB_KEY_ALIAS)
            !keystore.contains(DB_KEY_ALIAS)
        }
        // READ-BACK: the database is gone only when no file of it is left on any network — not the
        // live one, not an aside, not a companion.
        val noneLeft = step("database files read-back") { databaseFiles()?.isEmpty() ?: false }
        return files && noneLeft && passphrase && passphraseKey
    }

    /**
     * Every file of the wallet database on every network that is on disk now (see
     * DatabaseFiles.everyFileOf); null when a directory that should hold them cannot be listed.
     */
    private fun databaseFiles(): List<java.io.File>? = WALLET_NETWORK_SUFFIXES.map { net ->
        val live = context.getDatabasePath("wallet$net.db")
        val dir = live.parentFile ?: return null
        app.aroundtheblock.wallet.core.db.DatabaseFiles.everyFileOf(dir, live.name) ?: return null
    }.flatten()

    override fun eraseLeftoverState(): Boolean {
        val filterPeers = clearPrefsOnEveryNetwork("dgb_filter_peers")
        val dandelionPeers = clearPrefsOnEveryNetwork("dgb_dandelion_peers")
        // Upgrade bookkeeping: the reconcile's and the one-time history rebuild's. Both are
        // always attempted (`and` does not short-circuit).
        val reconcile = clearPrefsOnEveryNetwork("dgb_reconcile") and clearPrefsOnEveryNetwork("dgb_history_rebuild")
        val assetBackfill = clearPrefs("dgb_asset_backfill")
        val assetHeal = clearPrefs("dgb_asset_heal")
        // The last compact-filter reset's reason, time and heights: this wallet's history (4.1.3).
        val diag = clearPrefs("dgb_diag")
        // The log-tag key: the next wallet's logs must not correlate with this one's (4.1.3, review R7).
        val logTag = clearPrefs(LogTag.PREFS)
        // The quick-start choice and what it showed: this wallet's.
        val quickstart = clearPrefs(WalletStores.prefsName("dgb_quickstart", WalletScope.legacy(false)))
        // The auto-lock timeout is app-wide lock policy: it goes with the PIN store the wipe releases.
        // It went with the wallet database while it lived there, so a wipe resets it as it always did.
        val autoLock = clearPrefs(AutoLockPrefs.PREFS)
        // NOT Settings → Display → Show prices (PricePrefs, "dgb_price"): a device setting, like the
        // own node (dgb_settings) and the balance mask (dgb_privacy), it survives a wipe or a restore
        // (owner decision, 2026-11-09). The cached price itself is in the database erased above.
        // Nor the display currency it shares that file with (DisplayCurrencyPrefs, 2026-11-12).
        // ONE key, not the file: dgb_settings also holds the language and the network selection.
        val scanFloor = step("scan floor") {
            val settings = context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
            val held = settings.all
            // This full wipe is the legacy wallet's: its floor key (`cf_birth_height`).
            val floorKey = WalletStores.cfBirthHeightKey(WalletScope.legacy(false))
            if (!settings.contains(floorKey)) return@step true
            val landed = settings.edit().remove(floorKey).commit()
            // As clearPrefs: a write that did not land leaves this process reading the file.
            if (!landed) readWhatTheFileHolds(TAG, "dgb_settings", settings, held)
            landed
        }
        // Reserved for step 2: legacy's key record and the alias that wraps every wallet's. Here, in
        // the store phase, because neither is seed material (see the interface).
        val reservedKeys = eraseReservedKeyRecord()
        // The legacy seed record's file, once the record holds nothing (its clear is the wipe's first
        // step, and that step leaves the file so that "the seed is gone" waits on no removal). A
        // record that still holds a seed keeps its file: nothing is written or erased here.
        val emptiedRecord = creatingNoFile(listOf(SEED_RECORD)) { true }
        return emptiedRecord && filterPeers && dandelionPeers && reconcile && assetBackfill && assetHeal && diag && logTag && quickstart && autoLock && scanFloor && reservedKeys
    }

    // The encrypted store writes its keyset into its file when it is opened: on a device where it
    // was never opened, asking it for the token would leave a file the wipe made. The plain store
    // holds nothing once the token is out, so its file goes; the encrypted one keeps its keyset, and
    // its file when it was on the device.
    override fun eraseHubSession(): Boolean = creatingNoFile(
        listOf(HubTokenStore.LEGACY_PREFS_NAME, HubTokenStore.SECURE_PREFS_NAME).map { WalletStores.prefsName(it, WalletScope.legacy(false)) },
    ) { step("hub session") { hubTokenStore().clearConfirmed() } }

    private companion object {
        const val TAG = "WalletDataEraser"
        /** Legacy's seed record. Not network-suffixed: one seed serves both networks. */
        const val SEED_RECORD = "dgb_wallet_seed"
        const val DB_KEY_ALIAS = "dgb_db_passphrase"
        const val TMP = ".tmp"
    }
}
