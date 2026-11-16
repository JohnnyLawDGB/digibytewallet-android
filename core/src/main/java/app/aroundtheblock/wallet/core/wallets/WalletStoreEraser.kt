package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.AndroidKeystoreAliases
import app.aroundtheblock.wallet.core.KeystoreAliases
import app.aroundtheblock.wallet.core.db.DatabaseFiles
import app.aroundtheblock.wallet.core.db.DatabaseKeyProvisioner
import app.aroundtheblock.wallet.core.readWhatTheFileHolds
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import java.io.File

/** What [WalletStoreEraser.eraseAliases] established about a wallet's seed key aliases. */
enum class AliasOutcome {
    /** Every alias of the wallet reads back absent. */
    GONE,

    /** Legacy only: the database password's record still depends on `dgb_wallet_master`, so nothing was deleted. */
    KEPT_FOR_DB_KEY,

    /** An alias could not be deleted, or the Keystore could not say it is gone. */
    LEFT,
}

/**
 * What one [WalletStoreEraser.erase] established, each part by read-back.
 *
 * @property dbKeyHeldLegacyKey the legacy aliases were kept because the database password's record
 *   could not be moved off `dgb_wallet_master` ([AliasOutcome.KEPT_FOR_DB_KEY]).
 */
data class EraseReport(
    val recordGone: Boolean,
    val aliasesGone: Boolean,
    val storesGone: Boolean,
    val dbKeyHeldLegacyKey: Boolean,
) {
    /** The only result a caller may treat as "the wallet was removed". */
    val verified: Boolean get() = recordGone && aliasesGone && storesGone
}

/**
 * Erases ONE wallet's scope: what removing a wallet does. The single sanctioned exception to
 * "nothing deletes a database or a key outside the user's wipe", and scoped to exactly the one
 * wallet — every name it touches is resolved from [WalletStores] for that scope, so another
 * wallet's stores cannot be reached.
 *
 * Three phases, in this order (see [erase]): the seed record first, so a process that dies
 * mid-erase leaves no loadable half-wallet; then the seed key aliases; then every other store.
 * Every phase reports only what it read back.
 *
 * Never touched, for any scope: the network-shared prefs ([WalletStores.NETWORK_PREFS]),
 * `dgb_settings` except the scope's own floor key, the device's custom node endpoint
 * ([WalletStores.SHARED_RECONCILE_KEY]) in legacy's `dgb_reconcile<net>`, the database password's
 * record `dgb_db_key`, the PIN store `dgb_pin_store` and the registry `dgb_wallets`.
 *
 * The legacy wallet is one wallet on both networks (one seed), so for it every per-network store
 * is erased on both.
 */
class WalletStoreEraser internal constructor(
    private val context: Context,
    private val keystore: KeystoreAliases,
    private val releaseLegacyDbWrap: () -> Boolean,
    /** True when the prefs file [name] still holds something (production: it is on disk). */
    private val prefsLeft: (String) -> Boolean,
) {
    constructor(context: Context) : this(
        context,
        AndroidKeystoreAliases,
        { DatabaseKeyProvisioner.forContext(context).releaseLegacyWrap() },
        { name -> prefsFileOnDisk(context, name) },
    )

    /** Phase 1: the scope's seed record (one file, shared by both networks for legacy). True when it reads back gone. */
    fun eraseRecord(scope: WalletScope): Boolean = clearAndDelete(WalletStores.seedRecordPrefs(scope))

    /**
     * Phase 2: every Keystore alias of the scope's seed key (`dgb_wallet_master[_<id>]` and its `_v2`).
     * Legacy's are deleted only once [releaseLegacyDbWrap] reports the database password's record no
     * longer depends on `dgb_wallet_master`; otherwise nothing is deleted.
     */
    fun eraseAliases(scope: WalletScope): AliasOutcome {
        val present = aliasesOf(scope) ?: return AliasOutcome.LEFT
        if (present.isEmpty()) return AliasOutcome.GONE
        if (scope.isLegacy && !step("release the database key") { releaseLegacyDbWrap() }) {
            Log.w(TAG, "the database password's record still depends on the legacy key; it stays")
            return AliasOutcome.KEPT_FOR_DB_KEY
        }
        present.forEach { alias -> step("delete a seed key alias") { keystore.delete(alias); true } }
        return if (aliasesLeft(scope)) AliasOutcome.LEFT else AliasOutcome.GONE
    }

    /** Phase 3: every other store of [scope] (for legacy: on both networks). True when none reads back present. */
    fun eraseStores(scope: WalletScope): Boolean {
        val scopes = scopesOf(scope)
        val prefs = otherPrefsOf(scope).map(::clearAndDelete)
        val reconcile = scopes.filter { it.isLegacy }.map { clearReconcileKeepingEndpoint(legacyReconcile(it)) }
        val floor = removeFloorKey(scope)
        val files = filesOf(scope).map(::deleteFile)
        val databases = scopes.map(::deleteDatabase)
        scopes.forEach(ChainTipStore::invalidateCache)
        val cleared = (prefs + reconcile + files + databases).all { it } && floor
        return cleared && !otherStoresLeft(scope)
    }

    /** [eraseRecord], then [eraseAliases], then [eraseStores]. Every phase runs, whatever the one before reported. */
    fun erase(scope: WalletScope): EraseReport {
        val record = eraseRecord(scope)
        val aliases = eraseAliases(scope)
        val stores = eraseStores(scope)
        return EraseReport(
            recordGone = record,
            aliasesGone = aliases == AliasOutcome.GONE,
            storesGone = stores,
            dbKeyHeldLegacyKey = aliases == AliasOutcome.KEPT_FOR_DB_KEY,
        )
    }

    /**
     * READ-BACK, deletes nothing: any prefs file (the seed record included), file or database file of
     * [scope] is still present, or its floor key. Keystore aliases are not stores ([aliasesLeft]).
     * What cannot be read counts as present.
     */
    fun storesLeft(scope: WalletScope): Boolean = recordLeft(scope) || otherStoresLeft(scope)

    /** READ-BACK: an alias of [scope]'s seed key exists, or the Keystore cannot say. */
    fun aliasesLeft(scope: WalletScope): Boolean = aliasesOf(scope)?.isNotEmpty() ?: true

    fun anythingLeft(scope: WalletScope): Boolean = storesLeft(scope) || aliasesLeft(scope)

    private fun recordLeft(scope: WalletScope): Boolean = try {
        prefsLeft(WalletStores.seedRecordPrefs(scope))
    } catch (t: Throwable) {
        Log.w(TAG, "record read-back: ${t.javaClass.simpleName}")
        true
    }

    /** What [eraseStores] reads back: every store of [scope] but the seed record. */
    private fun otherStoresLeft(scope: WalletScope): Boolean = try {
        val scopes = scopesOf(scope)
        otherPrefsOf(scope).any(prefsLeft) ||
            scopes.filter { it.isLegacy }.any { walletKeysLeftIn(legacyReconcile(it)) } ||
            settings().contains(WalletStores.cfBirthHeightKey(scope)) ||
            filesOf(scope).any { it.exists() } ||
            scopes.any { databaseFiles(it)?.isNotEmpty() ?: true }
    } catch (t: Throwable) {
        Log.w(TAG, "stores read-back: ${t.javaClass.simpleName}")
        true
    }

    // --- names, all from WalletStores -------------------------------------------------------

    /** The scopes a wallet's stores span: legacy is one wallet on both networks. */
    private fun scopesOf(scope: WalletScope): List<WalletScope> =
        if (scope.isLegacy) listOf(WalletScope.legacy(false), WalletScope.legacy(true)) else listOf(scope)

    /** Every per-wallet prefs file except the seed record ([eraseRecord]) and legacy's reconcile file (shared key). */
    private fun otherPrefsOf(scope: WalletScope): List<String> {
        val perNetwork = scopesOf(scope).flatMap { s ->
            WalletStores.WALLET_AND_NETWORK_PREFS
                .filterNot { s.isLegacy && it == RECONCILE }
                .map { WalletStores.prefsName(it, s) }
        }
        val perWallet = WalletStores.WALLET_PREFS.map { WalletStores.prefsName(it, scope) }
        return (perNetwork + perWallet).distinct() - WalletStores.seedRecordPrefs(scope)
    }

    private fun legacyReconcile(scope: WalletScope): String = WalletStores.prefsName(RECONCILE, scope)

    private fun filesOf(scope: WalletScope): List<File> = scopesOf(scope).flatMap { s ->
        WalletStores.WALLET_AND_NETWORK_FILES.flatMap { base ->
            val name = WalletStores.fileName(base, s)
            listOf(File(context.filesDir, name), File(context.filesDir, "$name$TMP"))
        }
    }

    /** Every file of [scope]'s database on disk now; null when its directory cannot be listed. */
    private fun databaseFiles(scope: WalletScope): List<File>? {
        val live = context.getDatabasePath(WalletStores.databaseName(scope))
        val dir = live.parentFile ?: return null
        return DatabaseFiles.everyFileOf(dir, live.name)
    }

    /** The scope's seed key aliases the Keystore holds now; null when it cannot be asked. */
    private fun aliasesOf(scope: WalletScope): List<String>? = try {
        keystore.aliases().filter { WalletStores.walletIdOfAlias(it) == scope.walletId }
    } catch (t: Throwable) {
        Log.w(TAG, "keystore aliases: ${t.javaClass.simpleName}")
        null
    }

    // --- steps ------------------------------------------------------------------------------

    private fun settings() = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    /**
     * Clear [name] with a checked commit, then delete the file (which also removes the Tink keyset
     * entries an EncryptedSharedPreferences file keeps through a clear). A clear that did not land
     * leaves the file and this process reading what it holds, and the file is not deleted under it.
     */
    private fun clearAndDelete(name: String): Boolean = step("erase $name") {
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val held = store.all
        val landed = store.edit().clear().commit()
        if (!landed) {
            readWhatTheFileHolds(TAG, name, store, held)
            return@step false
        }
        val deleted = context.deleteSharedPreferences(name)
        deleted && !prefsLeft(name)
    }

    /** Legacy's reconcile file: every key but the device's custom node endpoint. The file stays. */
    private fun clearReconcileKeepingEndpoint(name: String): Boolean = step("erase $name") {
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val held = store.all
        val editor = store.edit()
        held.keys.filter { it != WalletStores.SHARED_RECONCILE_KEY }.forEach { editor.remove(it) }
        val landed = editor.commit()
        if (!landed) readWhatTheFileHolds(TAG, name, store, held)
        landed && !walletKeysLeftIn(name)
    }

    private fun walletKeysLeftIn(reconcile: String): Boolean =
        context.getSharedPreferences(reconcile, Context.MODE_PRIVATE).all.keys.any { it != WalletStores.SHARED_RECONCILE_KEY }

    /** ONE key of the shared `dgb_settings`: the scope's scan floor. */
    private fun removeFloorKey(scope: WalletScope): Boolean = step("scan floor") {
        val store = settings()
        val held = store.all
        val landed = store.edit().remove(WalletStores.cfBirthHeightKey(scope)).commit()
        if (!landed) readWhatTheFileHolds(TAG, SETTINGS, store, held)
        landed && !store.contains(WalletStores.cfBirthHeightKey(scope))
    }

    /** Judged by reading back that it is gone, since delete() of a missing file is false. */
    private fun deleteFile(file: File): Boolean = step("delete ${file.name}") {
        file.delete()
        !file.exists()
    }

    private fun deleteDatabase(scope: WalletScope): Boolean = step("database") {
        val files = databaseFiles(scope) ?: return@step false
        val deleted = files.map(::deleteFile).all { it }
        val noneLeft = databaseFiles(scope)?.isEmpty() ?: false
        deleted && noneLeft
    }

    /** A step that throws has not reported success. Logged by exception class only. */
    private inline fun step(what: String, block: () -> Boolean): Boolean = try {
        block().also { ok -> if (!ok) Log.w(TAG, "$what: not confirmed") }
    } catch (t: Throwable) {
        Log.w(TAG, "$what: ${t.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "WalletStoreEraser"
        const val RECONCILE = "dgb_reconcile"
        const val SETTINGS = "dgb_settings"
        const val TMP = ".tmp"

        /** The platform's file for prefs [name], or its backup, is on disk. */
        fun prefsFileOnDisk(context: Context, name: String): Boolean {
            val dir = File(context.dataDir, "shared_prefs")
            return File(dir, "$name.xml").exists() || File(dir, "$name.xml.bak").exists()
        }
    }
}
