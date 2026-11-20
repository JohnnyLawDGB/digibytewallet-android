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
        /** The id set cannot be read. */
        val idsThrow: Boolean = false,
    ) {
        /** From now on the Keystore cannot list its aliases. */
        var aliasesThrow = false
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
                override fun aliases(): List<String> =
                    if (aliasesThrow) throw KeystoreHiccup() else inner.aliases()
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
            allWalletIds = { if (idsThrow) throw IllegalStateException("shared_prefs not listable") else allIds },
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

    /** The names multi-wallet step 2 reserves (plan task A1, "Names reserved here"), as literals. */
    private val reservedWrapAlias = "dgb_wallet_keys_wrap"
    private val reservedPrefs = listOf(
        "dgb_wallet_keys", "dgb_wallet_keys_$idB", "dgb_wallet_keys_$idC",
        "dgb_sync_data_legacy", "dgb_sync_data_legacy_testnet",
    )
    private val reservedFiles = listOf("", "_testnet").flatMap { net ->
        listOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks").flatMap { listOf("${it}_legacy$net.bin", "${it}_legacy$net.bin.tmp") }
    }

    private fun Device.plantReservedNames() {
        reservedPrefs.forEach { prefs.prefsFor(it).seed("held", "$it-value") }
        reservedFiles.forEach { File(filesDir, it).writeBytes(byteArrayOf(1)) }
        vault.createUnboundKey(reservedWrapAlias)
        events.clear()
    }

    @Test fun `a wipe erases every name multi-wallet step 2 reserves, on both networks`() = runTest {
        val d = Device().apply { populateDevice(); plantReservedNames() }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals("reserved prefs left", emptyList<String>(), reservedPrefs.filter { d.prefs.prefsFor(it).all.isNotEmpty() })
        assertEquals("files left", emptyList<String>(), d.filesDir.list()!!.toList())
        assertFalse("the alias $reservedWrapAlias is left", reservedWrapAlias in d.vault.keys.keys)
        assertFalse("the PIN store was not cleared", d.pin.hasPin())
    }

    @Test fun `the key-record wrap alias goes with the stores, after every seed record and seed key`() = runTest {
        val d = Device().apply { populateDevice(); plantReservedNames() }

        d.walletManager().wipeThenReleasePin(d.pin)

        val wrapDeleted = d.events.indexOf("delete:$reservedWrapAlias")
        assertTrue("the alias was never deleted: ${d.events}", wrapDeleted >= 0)
        val lastSeedKey = d.events.indexOfLast { it.startsWith("delete:dgb_wallet_master") }
        val lastRecord = d.events.indexOfLast { it.startsWith("clear:dgb_wallet_seed") }
        assertTrue("precondition: seed keys and records went", lastSeedKey >= 0 && lastRecord >= 0)
        assertTrue("the alias went before a seed key: ${d.events}", wrapDeleted > lastSeedKey)
        assertTrue("the alias went before a seed record: ${d.events}", wrapDeleted > lastRecord)
    }

    @Test fun `a wrap alias that will not go keeps the wipe unverified, and holds no seed step`() = runTest {
        val d = Device(stuckAlias = reservedWrapAlias).apply { populateDevice(); plantReservedNames() }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("precondition: the alias is still there", reservedWrapAlias in d.vault.keys.keys)
        assertTrue("it is not a seed key: the seed is gone", report.seedGone)
        assertFalse("the alias is left, yet the wipe verified", report.verified)
        assertTrue("the PIN store must be kept", d.pin.hasPin())
    }

    @Test fun `a reserved band file that will not go keeps the wipe unverified`() = runTest {
        for (name in reservedFiles) {
            val d = Device().apply { populateDevice() }
            // A directory that holds something cannot be deleted as a file.
            File(d.filesDir, name).apply { mkdirs() }.resolve("held").writeBytes(byteArrayOf(1))

            val report = d.walletManager().wipeThenReleasePin(d.pin)

            assertTrue("$name", report.seedGone)
            assertFalse("$name is on the device, yet the wipe verified", report.verified)
            assertTrue("$name: the PIN store must be kept", d.pin.hasPin())
        }
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

    @Test fun `an owed wipe is not released while any wallet's record is left`() = runTest {
        val d = Device(active = scopeB).apply { populateDevice() }
        val recordC = WalletStores.seedRecordPrefs(scopeC)
        d.prefs.refuseWritesTo(recordC)
        val wm = d.walletManager()

        // The owed wipe could not remove C's record: B (active) and legacy lost theirs, C did not,
        // and no key went (phase 2 waits for every record).
        assertFalse(wm.wipeThenReleasePin(d.pin).seedGone)
        assertTrue("precondition: the wipe is owed", d.pin.isWipePending())
        assertTrue("precondition: B's record is gone", d.prefs.prefsFor(WalletStores.seedRecordPrefs(scopeB)).all.isEmpty())

        assertFalse("C's record is still on the device, yet the owed wipe was released", wm.releaseOwedWipeIfNoWalletIsLeft(d.pin))
        assertTrue(d.pin.isWipePending())
        assertTrue("C's record must survive", d.prefs.prefsFor(recordC).durable.containsKey("encrypted_seed_v2"))
        assertTrue("no key may go while a record is left", WalletStores.keyAliasV2(scopeC) in d.vault.keys.keys)

        // C's store takes the removal now: no record of any wallet is left, so the flag goes, and
        // every listed wallet's key is deleted on the way out.
        d.prefs.prefsFor(recordC).refuseWrites = false
        assertTrue(wm.releaseOwedWipeIfNoWalletIsLeft(d.pin))
        assertFalse("the owed wipe was not released", d.pin.isWipePending())
        assertTrue("the record of C is gone", d.prefs.prefsFor(recordC).all.isEmpty())
        assertTrue("keys left: ${d.vault.keys.keys}", d.vault.keys.keys.none { WalletStores.walletIdOfAlias(it) != null })
        assertTrue("the way out must not clear the PIN store", d.pin.hasPin())
    }

    @Test fun `a key that will not go does not hold a device with no wallet record left`() = runTest {
        // A key with no record protects nothing; a hold for it would keep the unlock screen on a PIN
        // that opens nothing, with no way to onboarding.
        for (unlistable in listOf(false, true)) {
            val d = Device(active = scopeB, stuckAlias = WalletStores.keyAliasV2(scopeC)).apply { populateDevice() }
            val wm = d.walletManager()
            assertFalse("precondition: the stuck key keeps the wipe unverified", wm.wipeThenReleasePin(d.pin).seedGone)
            assertTrue(d.pin.isWipePending())
            d.aliasesThrow = unlistable

            assertTrue("no wallet record is left (keystore unlistable=$unlistable), yet the owed wipe holds", wm.releaseOwedWipeIfNoWalletIsLeft(d.pin))
            assertFalse(d.pin.isWipePending())
            assertTrue("onboarding must be reachable: PIN setup creates the wallet", wm.pinSetupCreatesWallet())
            assertTrue(d.pin.hasPin())
        }
    }

    @Test fun `an alias of a wallet that was not listed is not deleted, and the seed is not gone`() = runTest {
        // D holds a key but is not in the id set this run read: its record was never looked for.
        val d = Device(allIds = listOf(legacy, idB, idC)).apply { populateDevice() }
        d.vault.createUnboundKey(WalletStores.keyAliasV2(scopeD))

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("an unlisted wallet's key was deleted", WalletStores.keyAliasV2(scopeD) in d.vault.keys.keys)
        assertFalse("a key of some wallet is left, yet the seed was reported gone", report.seedGone)
        assertTrue("the listed wallets' keys still go", WalletStores.keyAliasV2(scopeB) !in d.vault.keys.keys)
        assertTrue(d.pin.hasPin())
    }

    @Test fun `a wipe that cannot list the wallets still erases legacy, deletes no key and says the seed is not gone`() = runTest {
        val d = Device(idsThrow = true).apply { populateDevice() }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("legacy's record survived", d.prefs.prefsFor("dgb_wallet_seed").all.isEmpty())
        assertTrue("a key was deleted: ${d.events}", d.events.none { it.startsWith("delete:dgb_wallet_master") })
        assertFalse(report.seedGone)
        assertFalse(report.verified)
        assertTrue("the PIN store must be kept", d.pin.hasPin())
    }

    @Test fun `a Keystore that cannot list its aliases deletes no key and leaves the seed not gone`() = runTest {
        val d = Device().apply { populateDevice(); aliasesThrow = true }

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertEquals("every record still goes", emptyList<String>(), d.seedPrefsHoldingAKey())
        assertTrue("a key was deleted: ${d.events}", d.events.none { it.startsWith("delete:dgb_wallet_master") })
        assertFalse(report.seedGone)
        assertTrue(d.pin.hasPin())
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
