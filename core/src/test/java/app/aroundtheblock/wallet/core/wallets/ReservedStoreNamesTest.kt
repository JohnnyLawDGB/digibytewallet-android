package app.aroundtheblock.wallet.core.wallets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The names multi-wallet step 2 reserves (plan task A1, "Names reserved here"; ruling G97), each a
 * literal from that list and not computed from the code. No build writes them yet.
 *
 * Legacy's own band uses `_legacy` where another wallet has `_<id>`, for four chain stores and no
 * other: every other store of legacy keeps its 4.1.4 name (`LegacyStoreNameParityTest`), and asking
 * for its band spelling is refused, so no caller can make a second name for a store legacy already has.
 */
class ReservedStoreNamesTest {
    private val id = "0123456789abcdef"
    private val main = WalletScope.legacy(false)
    private val test = WalletScope.legacy(true)

    @Test fun `legacy's band stores are the four reserved names, on both networks`() {
        assertEquals(listOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks"), WalletStores.BAND_FILES)
        assertEquals(listOf("dgb_sync_data"), WalletStores.BAND_PREFS)
        assertEquals("saved_filter_headers_legacy.bin", WalletStores.bandFileName("saved_filter_headers", main))
        assertEquals("saved_filter_headers_legacy_testnet.bin", WalletStores.bandFileName("saved_filter_headers", test))
        assertEquals("saved_cf_ledger_legacy.bin", WalletStores.bandFileName("saved_cf_ledger", main))
        assertEquals("saved_cf_ledger_legacy_testnet.bin", WalletStores.bandFileName("saved_cf_ledger", test))
        assertEquals("saved_blocks_legacy.bin", WalletStores.bandFileName("saved_blocks", main))
        assertEquals("saved_blocks_legacy_testnet.bin", WalletStores.bandFileName("saved_blocks", test))
        assertEquals("dgb_sync_data_legacy", WalletStores.bandPrefsName("dgb_sync_data", main))
        assertEquals("dgb_sync_data_legacy_testnet", WalletStores.bandPrefsName("dgb_sync_data", test))
    }

    @Test fun `another wallet's band stores are its step-1 names`() {
        for (testnet in listOf(false, true)) {
            val s = WalletScope(id, testnet)
            WalletStores.BAND_FILES.forEach { assertEquals(WalletStores.fileName(it, s), WalletStores.bandFileName(it, s)) }
            WalletStores.BAND_PREFS.forEach { assertEquals(WalletStores.prefsName(it, s), WalletStores.bandPrefsName(it, s)) }
        }
    }

    @Test fun `the band spelling is refused for every other store`() {
        // The wallet's own stores, the database, the seed record, the seed key alias and the floor key.
        val refused = listOf(
            "saved_transactions", "dgb_history_rebuild", "dgb_reconcile", "dgb_cf_abandonment",
            "wallet", "wallet.db", "dgb_wallet_seed", "dgb_wallet_master", "cf_birth_height",
            "dgb_wallet_keys", "dgb_watched_addrs", "dgb_price",
        )
        for (scope in listOf(main, test, WalletScope(id, false))) {
            for (base in refused) {
                assertThrows("$base as a band file", IllegalArgumentException::class.java) { WalletStores.bandFileName(base, scope) }
                assertThrows("$base as band prefs", IllegalArgumentException::class.java) { WalletStores.bandPrefsName(base, scope) }
            }
            // A file base is not a prefs base, and the other way round.
            assertThrows(IllegalArgumentException::class.java) { WalletStores.bandFileName("dgb_sync_data", scope) }
            assertThrows(IllegalArgumentException::class.java) { WalletStores.bandPrefsName("saved_blocks", scope) }
        }
    }

    @Test fun `no name the legacy wallet resolves today is spelled _legacy`() {
        val names = listOf(main, test).flatMap { s ->
            (WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS + WalletStores.NETWORK_PREFS + WalletStores.RESERVED_WALLET_PREFS)
                .map { WalletStores.prefsName(it, s) } +
                WalletStores.WALLET_AND_NETWORK_FILES.map { WalletStores.fileName(it, s) } +
                listOf(
                    WalletStores.databaseName(s), WalletStores.seedRecordPrefs(s), WalletStores.keyAlias(s),
                    WalletStores.keyAliasV2(s), WalletStores.cfBirthHeightKey(s),
                ) +
                listOf(StoreScope.NETWORK, StoreScope.WALLET_AND_NETWORK, StoreScope.WALLET).map { storageSuffix(s, it) }
        }
        assertEquals(emptyList<String>(), names.filter { "legacy" in it })
        assertEquals("wallet.db", WalletStores.databaseName(main))
        assertEquals("wallet_testnet.db", WalletStores.databaseName(test))
    }

    @Test fun `the key record is a per-wallet store, and its wrap alias is no wallet's seed key`() {
        assertEquals(listOf("dgb_wallet_keys"), WalletStores.RESERVED_WALLET_PREFS)
        assertEquals("dgb_wallet_keys", WalletStores.prefsName("dgb_wallet_keys", main))
        assertEquals("dgb_wallet_keys", WalletStores.prefsName("dgb_wallet_keys", test))
        assertEquals("dgb_wallet_keys_$id", WalletStores.prefsName("dgb_wallet_keys", WalletScope(id, false)))
        assertEquals("dgb_wallet_keys_$id", WalletStores.prefsName("dgb_wallet_keys", WalletScope(id, true)))
        assertEquals("dgb_wallet_keys_wrap", WalletStores.KEYS_WRAP_ALIAS)
        assertNull(WalletStores.walletIdOfAlias(WalletStores.KEYS_WRAP_ALIAS))
        // No wallet id can spell the alias or a band name: `wrap` and `legacy` are not sixteen hex.
        assertFalse(WalletIds.isValid("wrap"))
        assertNull(WalletStores.walletIdOfSeedPrefsFile("dgb_wallet_keys_$id.xml"))
        assertFalse("a reserved base in two lists", WalletStores.RESERVED_WALLET_PREFS.any { it in WalletStores.WALLET_PREFS })
    }
}
