package app.aroundtheblock.wallet.core

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletIds
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStoreEraser
import app.aroundtheblock.wallet.core.wallets.WalletStores
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * One PIN guards every wallet, so the wipe (the PIN-failure wipe and Settings → Wipe Wallet) removes
 * every wallet: every seed record first, then every seed key alias (legacy's included, whichever
 * wallet is active), then every store, then the registry. The seed is gone only when no wallet's
 * record is readable and no wallet's alias remains.
 *
 * Pure JVM: prefs are in memory, files and databases live under a scratch directory, the Keystore
 * is [FakeVault]. The id set is injected (in-memory prefs create no files to list).
 */
class WalletWipeEveryWalletTest {

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

    private val legacy = WalletIds.LEGACY
    private val idB = "0123456789abcdef"
    private val idC = "fedcba9876543210"
    private val idD = "abcdefabcdefabcd"
    private val scopeB = WalletScope(idB, testnet = false)
    private val scopeC = WalletScope(idC, testnet = true)
    private val scopeD = WalletScope(idD, testnet = false)

    /** A PIN store as it stands after the tenth wrong PIN with wipe-after-N on. */
    private fun trippedPinStore() = RecordingPinStore().apply {
        map["pin_hash"] = "00"
        map["pin_salt"] = "00"
        map["pin_method"] = "pbkdf2"
        map["pin_fail_count"] = PinManager.WIPE_THRESHOLD
        map["pin_wipe_after_n"] = true
        map["pin_wipe_pending"] = true
    }

    /** The registry `dgb_wallets` naming legacy and [others], as WalletRegistry writes it. */
    private fun registryJson(others: List<WalletScope>): String {
        val entries = JSONArray()
        others.forEachIndexed { i, s ->
            entries.put(
                JSONObject().put("id", s.walletId).put("name", JSONObject.NULL).put("number", i + 2)
                    .put("testnet", s.testnet).put("createdAt", 1_790_000_000L).put("origin", "CREATED")
                    .put("order", i + 1).put("state", "READY"),
            )
        }
        return JSONObject().put("v", 1).put("entries", entries)
            .put("legacy", JSONObject().put("name", JSONObject.NULL).put("order", 0))
            .put("active", JSONObject()).toString()
    }

    private inner class Device(
        val active: WalletScope = WalletScope.legacy(false),
        val allIds: List<String> = listOf(legacy, idB, idC),
        /** An alias whose delete does nothing: the Keystore keeps it. */
        stuckAlias: String? = null,
    ) {
        val events = mutableListOf<String>()
        val vault = FakeVault(events)
        val prefs = InMemoryPrefsFactory(events)
        val root: File = tmp.newFolder()
        val ctx: Context = wipeTestContext(prefs, root).also {
            every { it.deleteSharedPreferences(any()) } returns true
        }
        val filesDir = File(root, "files")
        val dbDir = File(root, "databases")
        val aliases: KeystoreAliases = vault.asKeystoreAliases().let { inner ->
            object : KeystoreAliases by inner {
                override fun delete(alias: String) { if (alias != stuckAlias) inner.delete(alias) }
            }
        }
        val dataEraser = AndroidWalletDataEraser(ctx, aliases) {
            HubTokenStore(prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME), { prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME) })
        }
        val scopeEraser = WalletStoreEraser(ctx, aliases, { true }, { name -> prefs.prefsFor(name).all.isNotEmpty() })
        val pinStore = trippedPinStore()
        val pin = PinManager(pinStore)

        /** Every prefs file a wallet was given here (its record and its stores). */
        val populated = mutableSetOf<String>()

        fun walletManager() = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(active)),
            utxoManager = mockk(relaxed = true),
            dataEraser = dataEraser,
            quiesceNative = { },
            scope = active,
            scopeEraser = scopeEraser,
            keystoreAliases = aliases,
            allWalletIds = { allIds },
        )

        /** One wallet as a device holds it: record, aliases, every per-wallet store, its database. */
        fun populate(walletId: String, testnet: Boolean) {
            val scopes = if (walletId == legacy) listOf(false, true).map(WalletScope::legacy) else listOf(WalletScope(walletId, testnet))
            val scope = scopes.first()
            val record = WalletStores.seedRecordPrefs(scope)
            prefs.prefsFor(record).apply { seed("encrypted_seed_v2", "c1"); seed("encrypted_seed_iv_v2", "c2") }
            populated += record
            for (s in scopes) {
                (WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS)
                    .map { WalletStores.prefsName(it, s) }
                    .filter { it != record }
                    .forEach { name ->
                        // The Hub session stores hold the token; the wipe removes the token.
                        val key = if (name.startsWith("dgb_digiscope") || name.startsWith("dgb_hub_session")) HubTokenStore.KEY_JWT else "held"
                        prefs.prefsFor(name).seed(key, "$name-value")
                        populated += name
                    }
                WalletStores.WALLET_AND_NETWORK_FILES.forEach { File(filesDir, WalletStores.fileName(it, s)).writeBytes(byteArrayOf(1)) }
                val db = WalletStores.databaseName(s)
                listOf(db, "$db-wal").forEach { File(dbDir, it).writeBytes(byteArrayOf(2)) }
            }
            prefs.prefsFor("dgb_settings").seed(WalletStores.cfBirthHeightKey(scope), 21_000_000L)
            vault.createUnboundKey(WalletStores.keyAlias(scope))
            vault.createUnboundKey(WalletStores.keyAliasV2(scope))
        }

        fun populateDevice(registered: List<WalletScope> = listOf(scopeB, scopeC)) {
            populate(legacy, false)
            populate(idB, scopeB.testnet)
            populate(idC, scopeC.testnet)
            prefs.prefsFor("dgb_db_key").seed("wrapped", "pw")
            vault.createUnboundKey("dgb_db_passphrase")
            prefs.prefsFor("dgb_settings").seed("app_language", "de")
            prefs.prefsFor(WalletRegistry.PREFS).seed("registry", registryJson(registered))
            events.clear()
        }

        fun seedPrefsHoldingAKey(): List<String> =
            prefs.names().filter { it.startsWith("dgb_wallet_seed") && prefs.prefsFor(it).all.isNotEmpty() }

        fun walletDatabaseFiles(): List<String> = dbDir.list()!!.filter { it.startsWith("wallet") }.sorted()
    }

    @Test fun `a wipe erases every wallet's record, stores and aliases`() = runTest {
        val d = Device().apply { populateDevice() }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("a wipe of every wallet must verify: $report", report.verified)
        assertEquals("seed records left", emptyList<String>(), d.seedPrefsHoldingAKey())
        assertTrue("seed key aliases left: ${d.vault.keys.keys}", d.vault.keys.keys.none { WalletStores.walletIdOfAlias(it) != null })
        assertEquals("database files left", emptyList<String>(), d.walletDatabaseFiles())
        assertEquals("files left", emptyList<String>(), d.filesDir.list()!!.toList())
        assertEquals("stores left", emptyList<String>(), d.populated.filter { d.prefs.prefsFor(it).all.isNotEmpty() })
        assertTrue("the registry was not cleared", d.prefs.prefsFor(WalletRegistry.PREFS).all.isEmpty())
        assertFalse("the PIN store was not cleared", d.pin.hasPin())
        // The language is a device setting, not a wallet's.
        assertEquals(mapOf("app_language" to "de"), d.prefs.prefsFor("dgb_settings").all)
    }

    @Test fun `a wipe with another wallet active erases legacy's key too`() = runTest {
        val d = Device(active = scopeB).apply { populateDevice() }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertFalse("dgb_wallet_master survived", "dgb_wallet_master" in d.vault.keys.keys)
        assertFalse("dgb_wallet_master_v2 survived", "dgb_wallet_master_v2" in d.vault.keys.keys)
        assertTrue(report.seedGone)
        assertTrue("$report", report.verified)
        assertFalse("the PIN store was not cleared", d.pin.hasPin())
    }

    @Test fun `a surviving alias of any wallet means the seed is not gone`() = runTest {
        val d = Device(stuckAlias = WalletStores.keyAliasV2(scopeC)).apply { populateDevice() }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("precondition: C's alias is still there", WalletStores.keyAliasV2(scopeC) in d.vault.keys.keys)
        assertFalse("an alias of C survived, yet the seed was reported gone", report.seedGone)
        assertFalse(report.verified)
        assertTrue("the PIN store must be kept while a wallet's key remains", d.pin.hasPin())
    }

    @Test fun `every record goes before any key`() = runTest {
        val d = Device().apply { populateDevice() }

        d.walletManager().wipeThenReleasePin(d.pin)

        val records = listOf(legacy, idB, idC).map { WalletStores.seedRecordPrefs(WalletScope(it, false)) }
        val firstClear = records.associateWith { name -> d.events.indexOfFirst { it == "clear:$name" } }
        assertTrue("a record was never cleared: $firstClear", firstClear.values.none { it < 0 })
        val firstKeyDelete = d.events.indexOfFirst { it.startsWith("delete:dgb_wallet_master") }
        assertTrue("no seed key was deleted", firstKeyDelete >= 0)
        assertTrue(
            "a key was deleted before every record was cleared: ${d.events}",
            firstClear.values.all { it < firstKeyDelete },
        )
    }

    @Test fun `a wallet missing from the registry is still wiped`() = runTest {
        val d = Device(allIds = listOf(legacy, idB, idD)).apply {
            populateDevice(registered = listOf(scopeB))
            populate(idD, scopeD.testnet)
        }
        val recordD = WalletStores.seedRecordPrefs(scopeD)
        assertTrue("precondition: D holds a record", d.prefs.prefsFor(recordD).all.isNotEmpty())

        d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("D's record survived the wipe", d.prefs.prefsFor(recordD).all.isEmpty())
        assertFalse("D's alias survived the wipe", WalletStores.keyAliasV2(scopeD) in d.vault.keys.keys)
    }

    @Test fun `the production id set is the registry, the seed records on disk and the Keystore aliases`() {
        val prefs = InMemoryPrefsFactory()
        val root = tmp.newFolder()
        val ctx = wipeTestContext(prefs, root).also { every { it.dataDir } returns root }
        File(root, "shared_prefs").mkdirs()
        File(root, "shared_prefs/${WalletStores.seedRecordPrefs(scopeD)}.xml").writeText("<map/>")
        File(root, "shared_prefs/dgb_settings.xml").writeText("<map/>")
        prefs.prefsFor(WalletRegistry.PREFS).seed("registry", registryJson(listOf(scopeB, scopeC)))
        val idE = "1111222233334444"
        val aliases = listOf("dgb_wallet_master_${idE}_v2", "dgb_db_passphrase", "dgb_wallet_master_not-an-id")

        val ids = ActiveWallet.everyWalletId(ctx) { aliases }

        assertEquals(setOf(legacy, idB, idC, idD, idE), ids.toSet())
        assertEquals("each id once", ids.size, ids.toSet().size)
    }
}
