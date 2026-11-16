package app.aroundtheblock.wallet.core.wallets

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every name below is a literal from the 4.1.4 sources, not computed from the new code. */
class LegacyStoreNameParityTest {
    private val main = WalletScope.legacy(false)
    private val test = WalletScope.legacy(true)

    @Test
    fun `every legacy name is the 4-1-4 name`() {
        val perNetwork = listOf("dgb_sync_data", "dgb_cf_abandonment", "dgb_history_rebuild", "dgb_reconcile")
        assertEquals(perNetwork, WalletStores.WALLET_AND_NETWORK_PREFS)
        perNetwork.forEach {
            assertEquals(it, WalletStores.prefsName(it, main))
            assertEquals(it + "_testnet", WalletStores.prefsName(it, test))
        }
        val unsuffixed = listOf(
            "dgb_wallet_seed", "dgb_watched_addrs", "dgb_outgoing_tx", "dgb_asset_backfill",
            "dgb_asset_heal", "dgb_diag", "dgb_quickstart", "dgb_digiscope", "dgb_hub_session",
        )
        assertEquals(unsuffixed, WalletStores.WALLET_PREFS)
        unsuffixed.forEach {
            assertEquals(it, WalletStores.prefsName(it, main))
            assertEquals(it, WalletStores.prefsName(it, test))
        }
        val shared = listOf("dgb_bloom_peers", "dgb_filter_peers", "dgb_dandelion_peers", "dgb_price")
        assertEquals(shared, WalletStores.NETWORK_PREFS)
        shared.forEach {
            assertEquals(it, WalletStores.prefsName(it, main))
            assertEquals(it + "_testnet", WalletStores.prefsName(it, test))
        }
        val files = listOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks", "saved_transactions")
        assertEquals(files, WalletStores.WALLET_AND_NETWORK_FILES)
        files.forEach {
            assertEquals("$it.bin", WalletStores.fileName(it, main))
            assertEquals("${it}_testnet.bin", WalletStores.fileName(it, test))
        }
        assertEquals("saved_blocks.bin", WalletStores.fileName("saved_blocks", main))
        assertEquals("saved_blocks_testnet.bin", WalletStores.fileName("saved_blocks", test))
        assertEquals("wallet.db", WalletStores.databaseName(main))
        assertEquals("wallet_testnet.db", WalletStores.databaseName(test))
        assertEquals("dgb_wallet_seed", WalletStores.seedRecordPrefs(main))
        assertEquals("dgb_wallet_seed", WalletStores.seedRecordPrefs(test))
        assertEquals("dgb_wallet_master", WalletStores.keyAlias(main))
        assertEquals("dgb_wallet_master", WalletStores.keyAlias(test))
        assertEquals("cf_birth_height", WalletStores.cfBirthHeightKey(main))
        assertEquals("cf_birth_height", WalletStores.cfBirthHeightKey(test))
        assertEquals("custom_rpc_endpoint", WalletStores.SHARED_RECONCILE_KEY)
    }

    /**
     * F12. The 4.1.4 WalletDataEraser prefs set (dgb_wallet_seed, dgb_sync_data, dgb_bloom_peers,
     * dgb_watched_addrs, dgb_outgoing_tx, dgb_filter_peers, dgb_dandelion_peers, dgb_reconcile,
     * dgb_history_rebuild, dgb_asset_backfill, dgb_asset_heal, dgb_diag, plus the stores it reaches
     * through helpers: dgb_cf_abandonment, dgb_digiscope, dgb_hub_session), minus dgb_log_tag (per
     * install) and dgb_db_key (shared DB password record), plus dgb_quickstart (new) and dgb_price
     * (shared; the eraser keeps it on purpose).
     */
    @Test
    fun `the table is the 4-1-4 store set`() {
        val expectedPrefs = setOf(
            "dgb_wallet_seed", "dgb_sync_data", "dgb_bloom_peers", "dgb_watched_addrs", "dgb_outgoing_tx",
            "dgb_filter_peers", "dgb_dandelion_peers", "dgb_reconcile", "dgb_history_rebuild",
            "dgb_asset_backfill", "dgb_asset_heal", "dgb_diag", "dgb_cf_abandonment", "dgb_digiscope",
            "dgb_hub_session", "dgb_quickstart", "dgb_price",
        )
        val all = WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS + WalletStores.NETWORK_PREFS
        assertEquals(expectedPrefs, all.toSet())
        assertEquals("a base is in exactly one list", expectedPrefs.size, all.size)
        assertEquals(
            setOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks", "saved_transactions"),
            WalletStores.WALLET_AND_NETWORK_FILES.toSet(),
        )
    }
}
