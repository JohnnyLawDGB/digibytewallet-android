package app.aroundtheblock.wallet.core.wallets

/** The one list of per-wallet stores. Name resolution and the eraser both read it. */
object WalletStores {
    /** Prefs files owned by one wallet, per network. */
    val WALLET_AND_NETWORK_PREFS: List<String> =
        listOf("dgb_sync_data", "dgb_cf_abandonment", "dgb_history_rebuild", "dgb_reconcile")

    /** Prefs files owned by one wallet, not network-suffixed in 4.1.4. */
    val WALLET_PREFS: List<String> = listOf(
        "dgb_wallet_seed", "dgb_watched_addrs", "dgb_outgoing_tx", "dgb_asset_backfill",
        "dgb_asset_heal", "dgb_diag", "dgb_quickstart", "dgb_digiscope", "dgb_hub_session",
    )

    /** File bases in filesDir, per network, each "<base><suffix>.bin" (+ ".bin.tmp"). */
    val WALLET_AND_NETWORK_FILES: List<String> =
        listOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks", "saved_transactions")

    /** Prefs files shared by every wallet, per network. */
    val NETWORK_PREFS: List<String> =
        listOf("dgb_bloom_peers", "dgb_filter_peers", "dgb_dandelion_peers", "dgb_price")

    /**
     * RESERVED for multi-wallet step 2 (plan task A1, "Names reserved here"): prefs files owned by
     * one wallet, not network-suffixed: its key record, legacy `dgb_wallet_keys`, others
     * `dgb_wallet_keys_<id>`. No build writes them yet. They are on the per-wallet list all the
     * same: removing a wallet and the full wipe erase them, so a later version's file is not left
     * behind by this one (WIPE-RESERVED-NAMES). Apart from [WALLET_PREFS] only so that list stays
     * the 4.1.4 store set.
     */
    val RESERVED_WALLET_PREFS: List<String> = listOf("dgb_wallet_keys")

    /**
     * RESERVED for multi-wallet step 2 (ruling G97): the Keystore alias that will wrap the key
     * records. One alias for every wallet, so only the full wipe deletes it, never a removal. It is
     * not a seed key: [walletIdOfAlias] does not know it.
     */
    const val KEYS_WRAP_ALIAS = "dgb_wallet_keys_wrap"

    /**
     * RESERVED for multi-wallet step 2 (ruling G97): the chain stores a wallet's own band uses,
     * the ONLY bases that have a band spelling ([bandFileName], [bandPrefsName]). For legacy that
     * spelling is `_legacy`, names 4.1.4 never wrote and no wallet id can spell. No build writes
     * them yet; the full wipe erases legacy's on both networks.
     */
    val BAND_FILES: List<String> = listOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks")
    val BAND_PREFS: List<String> = listOf("dgb_sync_data")

    /** The key in dgb_reconcile<net> that belongs to the device, not to the wallet. */
    const val SHARED_RECONCILE_KEY = "custom_rpc_endpoint"

    /**
     * The seed record's network field, written for every wallet but `legacy` (whose record is the one
     * 4.1.4 wrote and serves both networks), so the registry can be rebuilt from the records on disk.
     */
    const val NETWORK_KEY = "wallet_network"
    const val NETWORK_MAINNET = "mainnet"
    const val NETWORK_TESTNET = "testnet"

    private const val SEED_PREFS = "dgb_wallet_seed"
    private const val KEY_RECORD_PREFS = "dgb_wallet_keys"
    private const val PREFS_BACKUP_SUFFIX = ".bak"
    private const val KEY_ALIAS_BASE = "dgb_wallet_master"
    private const val CF_BIRTH_KEY = "cf_birth_height"
    private const val SEED_PREFS_SUFFIX = ".xml"
    private const val V2 = "_v2"

    /** Throws [IllegalArgumentException] for a base in no list. */
    fun prefsName(base: String, scope: WalletScope): String {
        val store = when (base) {
            in WALLET_AND_NETWORK_PREFS -> StoreScope.WALLET_AND_NETWORK
            in WALLET_PREFS, in RESERVED_WALLET_PREFS -> StoreScope.WALLET
            in NETWORK_PREFS -> StoreScope.NETWORK
            else -> throw IllegalArgumentException("not a listed prefs store: $base")
        }
        return base + storageSuffix(scope, store)
    }

    fun fileName(base: String, scope: WalletScope): String {
        require(base in WALLET_AND_NETWORK_FILES) { "not a listed file store: $base" }
        return base + storageSuffix(scope, StoreScope.WALLET_AND_NETWORK) + ".bin"
    }

    /**
     * A band's file for [scope]: "<base>_<id><net>.bin", the id spelled out for legacy too. Throws
     * [IllegalArgumentException] for a base outside [BAND_FILES]: no other store has a band spelling.
     */
    fun bandFileName(base: String, scope: WalletScope): String {
        require(base in BAND_FILES) { "not a band file store: $base" }
        return base + storageSuffix(scope, StoreScope.BAND) + ".bin"
    }

    /** A band's prefs file for [scope], as [bandFileName]. Throws for a base outside [BAND_PREFS]. */
    fun bandPrefsName(base: String, scope: WalletScope): String {
        require(base in BAND_PREFS) { "not a band prefs store: $base" }
        return base + storageSuffix(scope, StoreScope.BAND)
    }

    fun databaseName(scope: WalletScope): String =
        "wallet" + storageSuffix(scope, StoreScope.WALLET_AND_NETWORK) + ".db"

    fun seedRecordPrefs(scope: WalletScope): String =
        SEED_PREFS + storageSuffix(scope, StoreScope.WALLET)

    fun keyAlias(scope: WalletScope): String =
        KEY_ALIAS_BASE + storageSuffix(scope, StoreScope.WALLET)

    /** The auth-bound (_v2) alias of the same wallet's seed key. */
    fun keyAliasV2(scope: WalletScope): String = keyAlias(scope) + V2

    fun cfBirthHeightKey(scope: WalletScope): String =
        CF_BIRTH_KEY + storageSuffix(scope, StoreScope.WALLET)

    /** A Keystore alias of some wallet's seed key to its wallet id, or null. Strict. */
    fun walletIdOfAlias(alias: String): String? {
        if (alias == KEY_ALIAS_BASE || alias == KEY_ALIAS_BASE + V2) return WalletIds.LEGACY
        val prefix = "${KEY_ALIAS_BASE}_"
        if (!alias.startsWith(prefix)) return null
        val rest = alias.removePrefix(prefix).removeSuffix(V2)
        return rest.takeIf { it != WalletIds.LEGACY && WalletIds.isValid(it) }
    }

    /**
     * A shared_prefs file name "dgb_wallet_keys_<id>.xml" (or its ".bak") to its id, or null. Strict:
     * legacy's own record, the wrap alias's name and anything that is not a wallet id name no wallet.
     */
    fun walletIdOfKeyRecordPrefsFile(fileName: String): String? {
        val prefix = "${KEY_RECORD_PREFS}_"
        val name = fileName.removeSuffix(PREFS_BACKUP_SUFFIX)
        if (!name.startsWith(prefix) || !name.endsWith(SEED_PREFS_SUFFIX)) return null
        val id = name.removePrefix(prefix).removeSuffix(SEED_PREFS_SUFFIX)
        return id.takeIf { it != WalletIds.LEGACY && WalletIds.isValid(it) }
    }

    /** A shared_prefs file name "dgb_wallet_seed_<id>.xml" to its id, or null. */
    fun walletIdOfSeedPrefsFile(fileName: String): String? {
        val prefix = "${SEED_PREFS}_"
        if (!fileName.startsWith(prefix) || !fileName.endsWith(SEED_PREFS_SUFFIX)) return null
        val id = fileName.removePrefix(prefix).removeSuffix(SEED_PREFS_SUFFIX)
        return id.takeIf { it != WalletIds.LEGACY && WalletIds.isValid(it) }
    }
}
