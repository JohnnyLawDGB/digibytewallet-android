package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.asKeystoreAliases
import app.aroundtheblock.wallet.core.sync.ChainTipPolicy
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wipeTestContext
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Removing one wallet erases exactly that wallet's scope — its seed record, its Keystore aliases
 * and every store [WalletStores] lists for it — and nothing of any other wallet or of the device.
 * Each phase reads back what it removed.
 *
 * Pure JVM: prefs are in memory, files and databases live under a scratch directory, the Keystore
 * is [FakeVault].
 */
class WalletStoreEraserTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        ChainTipStore.invalidateCache()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() {
        unmockkStatic(Log::class)
        ChainTipStore.invalidateCache()
    }

    private val idB = "0123456789abcdef"
    private val idC = "fedcba9876543210"
    private val legacyMain = WalletScope.legacy(false)
    private val legacyTest = WalletScope.legacy(true)
    private val scopeB = WalletScope(idB, testnet = false)
    private val scopeC = WalletScope(idC, testnet = false)

    /** The names a scope owns, from the one list. */
    private fun prefsOf(scope: WalletScope): Set<String> =
        (WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS).map { WalletStores.prefsName(it, scope) }.toSet()

    private fun filesOf(scope: WalletScope): Set<String> =
        WalletStores.WALLET_AND_NETWORK_FILES.flatMap { listOf(WalletStores.fileName(it, scope), WalletStores.fileName(it, scope) + ".tmp") }.toSet()

    private fun dbFilesOf(scope: WalletScope): Set<String> {
        val db = WalletStores.databaseName(scope)
        return setOf(db, "$db-wal", "$db.aside-recovery-1")
    }

    private val sharedPrefs = listOf(
        "dgb_bloom_peers", "dgb_bloom_peers_testnet", "dgb_price", "dgb_filter_peers",
        "dgb_db_key", "dgb_wallets", "dgb_pin_store",
    )

    private inner class Device(releaseLegacyDbWrap: () -> Boolean = { true }) {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val root: File = tmp.newFolder()
        val deleted = mutableListOf<String>()
        val ctx: Context = wipeTestContext(prefs, root).also {
            every { it.deleteSharedPreferences(any()) } answers { deleted += firstArg<String>(); true }
        }
        val filesDir = File(root, "files")
        val dbDir = File(root, "databases")
        val eraser = WalletStoreEraser(
            ctx,
            vault.asKeystoreAliases(),
            releaseLegacyDbWrap,
            { name -> prefs.prefsFor(name).all.isNotEmpty() },
        )

        fun populate(scope: WalletScope) {
            prefsOf(scope).forEach { name -> prefs.prefsFor(name).seed("held", "$name-value") }
            filesOf(scope).forEach { File(filesDir, it).writeBytes(it.toByteArray()) }
            dbFilesOf(scope).forEach { File(dbDir, it).writeBytes(it.toByteArray()) }
            prefs.prefsFor("dgb_settings").seed(WalletStores.cfBirthHeightKey(scope), 21_000_000L)
        }

        fun populateAll() {
            listOf(legacyMain, legacyTest, scopeB, scopeC).forEach(::populate)
            sharedPrefs.forEach { prefs.prefsFor(it).seed("shared", "$it-value") }
            prefs.prefsFor("dgb_settings").seed("app_language", "de")
            listOf(
                "dgb_wallet_master", "dgb_wallet_master_v2",
                "dgb_wallet_master_$idB", "dgb_wallet_master_${idB}_v2",
                "dgb_wallet_master_${idC}_v2",
            ).forEach(vault::createUnboundKey)
        }

        fun prefsSnapshot(): Map<String, Map<String, Any?>> = prefs.names().associateWith { prefs.prefsFor(it).all }

        fun fileSnapshot(): Map<String, List<Byte>> =
            root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
    }

    @Test fun `erasing B leaves every byte of legacy and C`() {
        val d = Device()
        d.populateAll()
        val prefsBefore = d.prefsSnapshot()
        val filesBefore = d.fileSnapshot()

        val report = d.eraser.erase(scopeB)

        assertTrue("erase(B) not verified: $report", report.verified)
        assertFalse(report.dbKeyHeldLegacyKey)
        assertFalse("B still holds something", d.eraser.anythingLeft(scopeB))

        val ofB = prefsOf(scopeB)
        for ((name, held) in prefsBefore) {
            when (name) {
                in ofB -> assertTrue("$name of B still holds data", d.prefs.prefsFor(name).all.isEmpty())
                "dgb_settings" -> assertEquals(held - WalletStores.cfBirthHeightKey(scopeB), d.prefs.prefsFor(name).all)
                else -> assertEquals("store $name changed", held, d.prefs.prefsFor(name).all)
            }
        }
        val filesOfB = (filesOf(scopeB).map { "files/$it" } + dbFilesOf(scopeB).map { "databases/$it" }).toSet()
        assertEquals(filesBefore - filesOfB, d.fileSnapshot())
        assertEquals(
            setOf("dgb_wallet_master", "dgb_wallet_master_v2", "dgb_wallet_master_${idC}_v2"),
            d.vault.keys.keys,
        )
    }

    @Test fun `shared stores survive`() {
        val d = Device()
        d.populateAll()
        val before = d.prefsSnapshot()

        assertTrue(d.eraser.erase(scopeB).verified)
        assertTrue(d.eraser.erase(legacyMain).verified)

        for (name in sharedPrefs) assertEquals("shared store $name changed", before[name], d.prefs.prefsFor(name).all)
        assertEquals("de", d.prefs.prefsFor("dgb_settings").getString("app_language", null))
        assertEquals(21_000_000L, d.prefs.prefsFor("dgb_settings").getLong(WalletStores.cfBirthHeightKey(scopeC), -1L))
        for (name in sharedPrefs) assertFalse("shared store $name was deleted", name in d.deleted)
        assertFalse("dgb_settings was deleted", "dgb_settings" in d.deleted)
    }

    @Test fun `erasing legacy keeps the custom node endpoint`() {
        val d = Device()
        d.populate(legacyMain)
        d.prefs.prefsFor("dgb_reconcile").apply {
            seed("custom_rpc_endpoint", "https://node.example")
            seed("reconcile_done", true)
        }
        d.vault.createUnboundKey("dgb_wallet_master")

        assertTrue(d.eraser.erase(legacyMain).verified)

        assertEquals(mapOf("custom_rpc_endpoint" to "https://node.example"), d.prefs.prefsFor("dgb_reconcile").all)
        assertFalse("dgb_reconcile must not be deleted", "dgb_reconcile" in d.deleted)
        assertFalse(d.eraser.storesLeft(legacyMain))
    }

    @Test fun `erasing legacy removes it on both networks`() {
        val d = Device()
        d.populateAll()

        assertTrue(d.eraser.erase(legacyMain).verified)

        assertTrue(d.prefs.prefsFor("dgb_sync_data_testnet").all.isEmpty())
        assertTrue(d.prefs.prefsFor("dgb_sync_data").all.isEmpty())
        assertTrue("dgb_sync_data_testnet" in d.deleted)
        assertFalse(File(d.dbDir, "wallet_testnet.db").exists())
        assertFalse(File(d.dbDir, "wallet.db").exists())
        assertFalse(File(d.filesDir, "saved_blocks_testnet.bin").exists())
        assertFalse(d.eraser.anythingLeft(legacyTest))
        assertTrue("B was touched", File(d.dbDir, "wallet_$idB.db").exists())
        assertEquals(setOf("dgb_wallet_master_$idB", "dgb_wallet_master_${idB}_v2", "dgb_wallet_master_${idC}_v2"), d.vault.keys.keys)
    }

    @Test fun `legacy's key stays while the database record depends on it`() {
        val d = Device(releaseLegacyDbWrap = { false })
        d.populateAll()

        assertEquals(AliasOutcome.KEPT_FOR_DB_KEY, d.eraser.eraseAliases(legacyMain))
        val report = d.eraser.erase(legacyMain)

        assertTrue(report.dbKeyHeldLegacyKey)
        assertFalse(report.aliasesGone)
        assertFalse(report.verified)
        assertTrue("dgb_wallet_master" in d.vault.keys.keys)
        assertTrue("dgb_wallet_master_v2" in d.vault.keys.keys)
    }

    @Test fun `an alias alone is not a store`() {
        val d = Device()
        d.vault.createUnboundKey("dgb_wallet_master_${idB}_v2")

        assertFalse(d.eraser.storesLeft(scopeB))
        assertTrue(d.eraser.aliasesLeft(scopeB))
        assertTrue(d.eraser.anythingLeft(scopeB))
    }

    @Test fun `each phase runs alone`() {
        val d = Device()
        d.populateAll()
        val syncB = "dgb_sync_data_$idB"

        assertTrue(d.eraser.eraseRecord(scopeB))
        assertTrue(d.prefs.prefsFor("dgb_wallet_seed_$idB").all.isEmpty())
        assertTrue(d.eraser.aliasesLeft(scopeB))
        assertTrue(d.prefs.prefsFor(syncB).all.isNotEmpty())

        assertEquals(AliasOutcome.GONE, d.eraser.eraseAliases(scopeB))
        assertFalse(d.eraser.aliasesLeft(scopeB))
        assertTrue(d.prefs.prefsFor(syncB).all.isNotEmpty())
        assertTrue(d.eraser.storesLeft(scopeB))

        assertTrue(d.eraser.eraseStores(scopeB))
        assertFalse(d.eraser.anythingLeft(scopeB))
    }

    @Test fun `a cleared prefs file is deleted, not just emptied`() {
        val d = Device()
        d.populateAll()

        assertTrue(d.eraser.erase(scopeB).verified)

        val missing = prefsOf(scopeB) - d.deleted.toSet()
        assertTrue("not deleted: $missing", missing.isEmpty())
        assertTrue("dgb_hub_session_$idB" in d.deleted)
        assertEquals("deleted a store of another scope: ${d.deleted - prefsOf(scopeB)}", prefsOf(scopeB), d.deleted.toSet())
    }

    @Test fun `a refused commit is reported`() {
        val d = Device()
        d.populateAll()
        d.prefs.refuseWritesTo("dgb_sync_data_$idB")

        val report = d.eraser.erase(scopeB)

        assertFalse(report.storesGone)
        assertFalse(report.verified)
        assertTrue(d.eraser.storesLeft(scopeB))
        assertFalse("a store whose clear did not land must not be deleted", "dgb_sync_data_$idB" in d.deleted)
    }

    @Test fun `a refused delete is reported`() {
        val d = Device()
        d.populateAll()
        every { d.ctx.deleteSharedPreferences("dgb_outgoing_tx_$idB") } returns false

        assertFalse(d.eraser.erase(scopeB).storesGone)
    }

    @Test fun `the chain tip of one wallet is never read as another's, and erasing B drops only B's`() {
        val d = Device()
        assertTrue(ChainTipStore.record(d.ctx, 100L, legacyMain))
        assertTrue(ChainTipStore.record(d.ctx, 200L, scopeB))

        assertEquals(100L, ChainTipStore.read(d.ctx, legacyMain))
        assertEquals(200L, ChainTipStore.read(d.ctx, scopeB))
        assertEquals(ChainTipPolicy.UNKNOWN_TIP, ChainTipStore.read(d.ctx, scopeC))

        assertTrue(d.eraser.erase(scopeB).storesGone)

        assertEquals(ChainTipPolicy.UNKNOWN_TIP, ChainTipStore.read(d.ctx, scopeB))
        assertEquals(100L, ChainTipStore.read(d.ctx, legacyMain))
    }
}
