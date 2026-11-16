package app.aroundtheblock.wallet.core.wallets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletScopeTest {
    private val id = "0123456789abcdef"

    @Test
    fun `a new wallet's names carry its id before the network`() {
        val s = WalletScope(id, true)
        assertEquals("dgb_sync_data_${id}_testnet", WalletStores.prefsName("dgb_sync_data", s))
        assertEquals("dgb_watched_addrs_$id", WalletStores.prefsName("dgb_watched_addrs", s))
        assertEquals("wallet_${id}_testnet.db", WalletStores.databaseName(s))
        assertEquals("dgb_wallet_master_$id", WalletStores.keyAlias(s))
        assertEquals("_testnet", storageSuffix(s, StoreScope.NETWORK))
        assertEquals("saved_blocks_${id}_testnet.bin", WalletStores.fileName("saved_blocks", s))
        assertEquals("dgb_wallet_seed_$id", WalletStores.seedRecordPrefs(s))
        assertEquals("cf_birth_height_$id", WalletStores.cfBirthHeightKey(s))
        assertEquals("dgb_price_testnet", WalletStores.prefsName("dgb_price", s))
    }

    @Test
    fun `an id that is not legacy or 16 hex is refused`() {
        listOf("../x", "ABCDEF0123456789", "v2", "", "0123456789abcde", "0123456789abcdef0").forEach {
            assertThrows(IllegalArgumentException::class.java) { WalletScope(it, false) }
        }
        assertTrue(WalletIds.isValid("legacy"))
        assertFalse(WalletIds.isValid("legacy "))
    }

    @Test
    fun `an unlisted base is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletStores.prefsName("dgb_unknown", WalletScope.legacy(false))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletStores.fileName("dgb_sync_data", WalletScope.legacy(false))
        }
    }

    @Test
    fun `newId is 16 lowercase hex`() {
        val r = java.security.SecureRandom()
        repeat(50) { assertTrue(WalletIds.isValid(WalletIds.newId(r)) && WalletIds.newId(r) != "legacy") }
    }

    @Test
    fun `aliases parse strictly`() {
        assertEquals("legacy", WalletStores.walletIdOfAlias("dgb_wallet_master"))
        assertEquals("legacy", WalletStores.walletIdOfAlias("dgb_wallet_master_v2"))
        assertEquals(id, WalletStores.walletIdOfAlias("dgb_wallet_master_${id}_v2"))
        assertEquals(id, WalletStores.walletIdOfAlias("dgb_wallet_master_$id"))
        assertNull(WalletStores.walletIdOfAlias("dgb_db_passphrase"))
        assertNull(WalletStores.walletIdOfAlias("dgb_wallet_master_xyz"))
        assertNull(WalletStores.walletIdOfAlias("dgb_wallet_master_legacy"))
    }

    @Test
    fun `seed prefs files parse strictly`() {
        assertEquals(id, WalletStores.walletIdOfSeedPrefsFile("dgb_wallet_seed_$id.xml"))
        assertNull(WalletStores.walletIdOfSeedPrefsFile("dgb_wallet_seed.xml"))
        assertNull(WalletStores.walletIdOfSeedPrefsFile("dgb_wallet_seed_xyz.xml"))
        assertNull(WalletStores.walletIdOfSeedPrefsFile("dgb_wallet_seed_$id"))
    }
}
