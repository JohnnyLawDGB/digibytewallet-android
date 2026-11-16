package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.BOUND_ALIAS
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.LEGACY_ALIAS
import app.aroundtheblock.wallet.core.SeedFingerprint
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.hex
import app.aroundtheblock.wallet.core.keyStoreManagerOver
import app.aroundtheblock.wallet.core.keyTestContext
import app.aroundtheblock.wallet.core.sync.SavedBlockStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The guard against the worst upgrade failure: a user updates from 4.1.4 and the wallet opens
 * empty, or re-syncs from birth. A 4.1.4 install is laid out on disk from a fixture of dummy
 * values, then the launch-time reads of this build run over it. Nothing may change, the wallet
 * resolves as `legacy` and is the only registry entry.
 *
 * The fixture lists the stores of 4.1.4 by the names it used; [the fixture covers every listed store]
 * ties it to [WalletStores] so a store added later cannot escape this test.
 */
class UpgradeFrom414Test {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun silenceLog() {
        ActiveWallet.resetForTest()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun restoreLog() {
        unmockkStatic(Log::class)
        ActiveWallet.resetForTest()
    }

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun derive(m: ByteArray, p: ByteArray?): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { update(m); p?.let { update(it) } }.digest()

    private class Fixture(val prefs: List<Triple<String, String, Any>>, val files: List<Pair<String, ByteArray>>)

    private fun fixture(): Fixture {
        val text = javaClass.classLoader!!.getResourceAsStream("upgrade-4.1.4-layout.txt")!!.bufferedReader().readText()
        val prefs = mutableListOf<Triple<String, String, Any>>()
        val files = mutableListOf<Pair<String, ByteArray>>()
        for (line in text.lines().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val parts = line.split(" ", limit = 3)
            when (parts[0]) {
                "prefs" -> {
                    val (key, typed) = parts[2].split("=", limit = 2)
                    val value: Any = when (typed.substringBefore(":")) {
                        "b" -> typed.substringAfter(":").toBoolean()
                        "l" -> typed.substringAfter(":").toLong()
                        "s" -> typed.substringAfter(":")
                        else -> error("bad type in fixture: $line")
                    }
                    prefs += Triple(parts[1], key, value)
                }
                "file" -> files += parts[1] to parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                else -> error("bad fixture line: $line")
            }
        }
        return Fixture(prefs, files)
    }

    private inner class Install {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val root: File = tmp.newFolder()
        val ctx: Context = keyTestContext(prefs, root)
        private val dbDir = File(root, "databases")
        private val filesDir = File(root, "files")

        init {
            val fx = fixture()
            fx.prefs.forEach { (name, key, value) -> prefs.prefsFor(name).seed(key, value) }
            fx.files.forEach { (name, bytes) ->
                val dir = if (name.startsWith("wallet.db")) dbDir else filesDir
                File(dir, name).writeBytes(bytes)
            }
            // The wallet's record: a phrase sealed under the auth-bound key, as 4.1.4 left it.
            vault.createUnboundKey(LEGACY_ALIAS)
            vault.createUnboundKey(BOUND_ALIAS)
            val enc = vault.encrypt(BOUND_ALIAS, phrase.toByteArray())
            prefs.prefsFor("dgb_wallet_seed").apply {
                seed("encrypted_seed_v2", hex(enc.ciphertext))
                seed("encrypted_seed_iv_v2", hex(enc.iv))
                seed("wallet_creation_time", 1_767_225_600L)
                seed("seed_fingerprint_v2", SeedFingerprint.v2(derive(phrase.toByteArray(), null)))
            }
            vault.events.clear()
        }

        fun manager(scope: WalletScope = WalletScope.legacy(false)) = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault),
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            seedNative = { m, p -> derive(m, p) },
            releaseDatabaseKey = { true },
            scope = scope,
            restoreNative = { _, _, _ -> true },
            stopSyncNative = { },
            peerCountNative = { 0 },
            loadTxNative = { 0 },
            sleep = { },
        )

        fun prefsSnapshot(): Map<String, Map<String, Any?>> = prefs.names().associateWith { prefs.prefsFor(it).all.toMap() }

        fun fileSnapshot(): Map<String, String> =
            root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to hex(it.readBytes()) }
    }

    @Test fun `the fixture covers every listed store`() {
        val fx = fixture()
        val prefsNames = fx.prefs.map { it.first }.toSet()
        val fileNames = fx.files.map { it.first }.toSet()
        for (testnet in listOf(false, true)) {
            val s = WalletScope.legacy(testnet)
            val listed = WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS + WalletStores.NETWORK_PREFS
            for (base in listed - "dgb_wallet_seed") {
                assertTrue("fixture lacks prefs $base on testnet=$testnet", WalletStores.prefsName(base, s) in prefsNames)
            }
            for (base in WalletStores.WALLET_AND_NETWORK_FILES) {
                assertTrue("fixture lacks file $base", WalletStores.fileName(base, s) in fileNames)
            }
        }
        assertEquals(setOf("wallet.db", "wallet.db-wal", "wallet.db-shm", "wallet.db-journal"), fileNames.filter { it.startsWith("wallet.db") }.toSet())
    }

    @Test fun `an upgraded launch reads the 4_1_4 wallet as legacy and writes nothing`() {
        val install = Install()
        val prefsBefore = install.prefsSnapshot()
        val filesBefore = install.fileSnapshot()
        val aliasesBefore = install.vault.keys.keys.toSet()
        assertEquals(setOf("dgb_wallet_master", "dgb_wallet_master_v2"), aliasesBefore)
        assertEquals(21_000_000L, prefsBefore.getValue("dgb_settings")["cf_birth_height"])

        // The launch-time reads, in production order.
        val scope = ActiveWallet.scope(install.ctx)
        val listed = ActiveWallet.registry(install.ctx).list(false)
        val dbName = WalletStores.databaseName(scope)
        val hasWallet = install.manager(scope).hasSavedWallet()

        assertEquals(WalletScope.legacy(false), scope)
        assertEquals(listOf("legacy"), listed.map { it.id })
        assertTrue(hasWallet)
        assertEquals("wallet.db", dbName)

        val prefsAfter = install.prefsSnapshot()
        for ((name, held) in prefsBefore) assertEquals("prefs $name changed", held, prefsAfter[name])
        for (name in prefsAfter.keys - prefsBefore.keys) {
            assertTrue("a new store $name was written: ${prefsAfter[name]}", prefsAfter.getValue(name).isEmpty())
        }
        assertTrue("dgb_wallets holds something", (prefsAfter["dgb_wallets"] ?: emptyMap()).isEmpty())
        assertEquals(filesBefore, install.fileSnapshot())
        assertEquals(aliasesBefore, install.vault.keys.keys.toSet())
    }

    @Test fun `a restore of the legacy wallet after upgrade opens its sync state`() {
        val install = Install()

        assertTrue(install.manager(ActiveWallet.scope(install.ctx)).restoreFromDisk())

        assertEquals(true, install.prefs.prefsFor("dgb_sync_data").getBoolean("has_synced", false))
        assertTrue("the saved block window is gone", SavedBlockStore.file(install.ctx).exists())
    }
}
