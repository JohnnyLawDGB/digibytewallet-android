package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.SeedFingerprint
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.asKeystoreAliases
import app.aroundtheblock.wallet.core.hex
import app.aroundtheblock.wallet.core.keyStoreManagerOver
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wipeTestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Adding a wallet writes only the new wallet's own record (and the alias its seed key needs), refuses
 * a phrase the app already holds, and leaves nothing behind when it does not complete.
 *
 * Pure JVM: prefs in memory, files under a scratch directory, the Keystore is [FakeVault], and the
 * BIP39 derivation is a stand-in (SHA-256 of mnemonic + passphrase, padded to 64 bytes).
 */
class WalletProvisionerTest {

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

    private val phraseA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val phraseB = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
    private val phraseC = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private inner class Device {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val root: File = tmp.newFolder()
        val ctx: Context = wipeTestContext(prefs, root).also {
            every { it.deleteSharedPreferences(any()) } returns true
        }
        val derived = mutableListOf<ByteArray>()
        val deriveSeed: (ByteArray, ByteArray?) -> ByteArray? = { m, p ->
            val digest = MessageDigest.getInstance("SHA-256").apply { update(m); p?.let { update(it) } }.digest()
            digest.copyOf(64).also { derived += it }
        }
        val eraser = WalletStoreEraser(ctx, vault.asKeystoreAliases(), { true }, { name -> prefs.prefsFor(name).all.isNotEmpty() })
        val registry = WalletRegistry(
            prefs = prefs.prefsFor(WalletRegistry.PREFS),
            recordExists = { scope ->
                val p = prefs.prefsFor(WalletStores.seedRecordPrefs(scope))
                p.contains("encrypted_seed") || p.contains("encrypted_seed_v2")
            },
            recordsOnDisk = {
                prefs.names().mapNotNull { WalletStores.walletIdOfSeedPrefsFile("$it.xml") }.map { it to false }
            },
        )
        val factory = WalletManagerFactory { scope ->
            WalletManager(
                context = ctx,
                keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(scope)),
                utxoManager = mockk(relaxed = true),
                dataEraser = mockk(relaxed = true),
                quiesceNative = { },
                seedNative = deriveSeed,
                releaseDatabaseKey = { true },
                scope = scope,
                storesLeft = eraser::storesLeft,
            )
        }
        val provisioner = WalletProvisioner(registry, factory, eraser, deriveSeed, nowSecs = { 1_800_000_000L })

        /** The legacy wallet of a 4.1.4 install, holding [phrase] (both fingerprints, as 4.1.4 wrote them). */
        fun installLegacy(phrase: String = phraseA) {
            vault.createUnboundKey("dgb_wallet_master")
            vault.createUnboundKey("dgb_wallet_master_v2")
            val enc = vault.encrypt("dgb_wallet_master_v2", phrase.toByteArray())
            prefs.prefsFor("dgb_wallet_seed").apply {
                seed("encrypted_seed_v2", hex(enc.ciphertext))
                seed("encrypted_seed_iv_v2", hex(enc.iv))
                seed("wallet_creation_time", 1_767_225_600L)
                seed("seed_fingerprint", SeedFingerprint.v1(phrase.toByteArray()))
                seed("seed_fingerprint_v2", SeedFingerprint.v2(deriveSeed(phrase.toByteArray(), null)!!))
            }
            prefs.prefsFor("dgb_sync_data").seed("has_synced", true)
            prefs.prefsFor("dgb_settings").seed("cf_birth_height", 21_000_000L)
            File(root, "files/saved_blocks.bin").writeBytes(byteArrayOf(1, 2, 3))
            derived.clear()
        }

        fun prefsSnapshot(): Map<String, Map<String, Any?>> = prefs.names().associateWith { prefs.prefsFor(it).all.toMap() }

        fun prepared(origin: WalletOrigin = WalletOrigin.RESTORED): WalletEntry =
            provisioner.prepareSeedKey(false, origin)!!.first

        fun masterAliasesOf(id: String) = vault.keys.keys.filter { it.startsWith("dgb_wallet_master_$id") }
    }

    @Test fun `adding a restored wallet stores its record and makes it active`() {
        val d = Device()
        d.installLegacy()
        val entry = d.prepared()

        val result = d.provisioner.add(entry, phraseB, null, 1_704_067_200L)

        assertEquals(entry.id, (result as AddResult.Added).entry.id)
        assertEquals(entry.id, d.registry.activeId(false))
        assertEquals(EntryState.READY, d.registry.find(entry.id)!!.state)
        assertEquals(EntryState.READY, result.entry.state)
        val record = d.prefs.prefsFor("dgb_wallet_seed_${entry.id}")
        assertEquals(1_704_067_200L, record.getLong("wallet_creation_time", -1L))
        assertEquals("restored", record.getString("wallet_origin", null))
        assertTrue(d.registry.incomplete().isEmpty())
    }

    @Test fun `a created wallet's record says it was created`() {
        val d = Device()
        d.installLegacy()
        val entry = d.prepared(WalletOrigin.CREATED)

        assertTrue(d.provisioner.add(entry, phraseB, null, 1_800_000_000L) is AddResult.Added)

        assertEquals("created", d.prefs.prefsFor("dgb_wallet_seed_${entry.id}").getString("wallet_origin", null))
        assertEquals(WalletOrigin.CREATED, d.registry.find(entry.id)!!.origin)
    }

    @Test fun `a phrase already in the app is refused and names the wallet it matches`() {
        val d = Device()
        d.installLegacy(phraseA)

        assertEquals("legacy", d.provisioner.duplicateOf(false, phraseA, null)?.id)

        val entry = d.prepared()
        val result = d.provisioner.add(entry, phraseA, null, 1_704_067_200L)

        assertEquals("legacy", (result as AddResult.Duplicate).existing.id)
        assertTrue(d.prefs.prefsFor("dgb_wallet_seed_${entry.id}").all.isEmpty())
        assertEquals("legacy", d.registry.activeId(false))
    }

    @Test fun `a duplicate found at add leaves no entry and no alias`() {
        val d = Device()
        d.installLegacy(phraseA)
        val entry = d.prepared()
        assertTrue("prepareSeedKey made no alias", d.masterAliasesOf(entry.id).isNotEmpty())

        assertTrue(d.provisioner.add(entry, phraseA, null, 1_704_067_200L) is AddResult.Duplicate)

        assertTrue(d.registry.incomplete().isEmpty())
        assertNull(d.registry.find(entry.id))
        assertFalse(d.eraser.anythingLeft(WalletScope(entry.id, false)))
        assertEquals(emptyList<String>(), d.masterAliasesOf(entry.id))
    }

    @Test fun `a duplicate of a wallet added earlier is found`() {
        val d = Device()
        d.installLegacy(phraseA)
        val b = (d.provisioner.add(d.prepared(), phraseB, null, 1L) as AddResult.Added).entry

        assertEquals(b.id, d.provisioner.duplicateOf(false, phraseB, null)?.id)
        assertNull("a mainnet wallet is not a testnet duplicate", d.provisioner.duplicateOf(true, phraseB, null))
    }

    @Test fun `the same mnemonic with a passphrase is a different wallet`() {
        val d = Device()
        d.installLegacy(phraseA)

        assertNull(d.provisioner.duplicateOf(false, phraseA, "x".toByteArray()))
    }

    @Test fun `an empty passphrase is no passphrase`() {
        val d = Device()
        d.installLegacy(phraseA)

        assertEquals("legacy", d.provisioner.duplicateOf(false, phraseA, ByteArray(0))?.id)
    }

    @Test fun `a legacy record from before fingerprint v2 is still matched`() {
        val d = Device()
        d.installLegacy(phraseA)
        d.prefs.prefsFor("dgb_wallet_seed").edit().remove("seed_fingerprint_v2").commit()

        assertEquals("legacy", d.provisioner.duplicateOf(false, phraseA, null)?.id)
        assertNull("a v1 fingerprint cannot say which passphrase wallet it is", d.provisioner.duplicateOf(false, phraseA, "x".toByteArray()))
    }

    @Test fun `every derived seed is zeroed`() {
        val d = Device()
        d.installLegacy(phraseA)

        d.provisioner.duplicateOf(false, phraseA, null)
        d.provisioner.duplicateOf(false, phraseC, "x".toByteArray())

        assertEquals(2, d.derived.size)
        d.derived.forEach { seed -> assertTrue("a derived seed was not zeroed", seed.all { it == 0.toByte() }) }
    }

    @Test fun `a phrase whose seed cannot be derived is not added`() {
        val d = Device()
        d.installLegacy(phraseA)
        val provisioner = WalletProvisioner(d.registry, d.factory, d.eraser, { _, _ -> null })
        val entry = provisioner.prepareSeedKey(false, WalletOrigin.RESTORED)!!.first

        assertEquals(AddResult.Failed, provisioner.add(entry, phraseB, null, 1L))

        assertTrue(d.registry.incomplete().isEmpty())
        assertFalse(d.eraser.anythingLeft(WalletScope(entry.id, false)))
    }

    @Test fun `the ninth wallet is refused before anything is written`() {
        val d = Device()
        d.installLegacy()
        repeat(7) {
            val e = d.registry.reserve(false, WalletOrigin.CREATED, 1L)!!
            assertTrue(d.registry.complete(e.id, makeActive = false))
        }
        assertEquals(8, d.registry.list(false).size)
        val keysBefore = d.vault.keys.keys.toSet()
        val registryBefore = d.prefs.prefsFor(WalletRegistry.PREFS).all

        assertNull(d.provisioner.prepareSeedKey(false, WalletOrigin.RESTORED))

        assertEquals(keysBefore, d.vault.keys.keys.toSet())
        assertEquals(registryBefore, d.prefs.prefsFor(WalletRegistry.PREFS).all)
    }

    @Test fun `a prepared key does not block the add`() {
        val d = Device()
        d.installLegacy()
        val (entry, _) = d.provisioner.prepareSeedKey(false, WalletOrigin.RESTORED)!!
        assertEquals(EntryState.PENDING, entry.state)
        assertTrue("dgb_wallet_master_${entry.id}_v2" in d.vault.keys.keys)

        assertTrue(d.provisioner.add(entry, phraseB, null, 1L) is AddResult.Added)
    }

    @Test fun `a failed record write leaves no entry and no store`() {
        val d = Device()
        d.installLegacy()
        val entry = d.prepared()
        d.prefs.refuseWritesTo("dgb_wallet_seed_${entry.id}")

        assertEquals(AddResult.Failed, d.provisioner.add(entry, phraseB, null, 1L))

        assertTrue(d.registry.incomplete().isEmpty())
        assertNull(d.registry.find(entry.id))
        assertFalse(d.eraser.anythingLeft(WalletScope(entry.id, false)))
        assertEquals("legacy", d.registry.activeId(false))
    }

    @Test fun `adding never touches the active wallet's stores`() {
        val d = Device()
        d.installLegacy()
        val entry = d.prepared()
        val newRecord = "dgb_wallet_seed_${entry.id}"
        val before = d.prefsSnapshot() - WalletRegistry.PREFS - newRecord
        val legacyKeys = d.vault.keys.filterKeys { WalletStores.walletIdOfAlias(it) == WalletIds.LEGACY }
        val blocks = File(d.root, "files/saved_blocks.bin").readBytes().toList()

        assertTrue(d.provisioner.add(entry, phraseB, null, 1L) is AddResult.Added)

        val after = d.prefsSnapshot()
        for ((name, held) in before) assertEquals("store $name changed", held, after[name])
        for (name in after.keys - before.keys - WalletRegistry.PREFS - newRecord) {
            assertTrue("store $name was written: ${after[name]}", after.getValue(name).isEmpty())
        }
        assertEquals(legacyKeys, d.vault.keys.filterKeys { WalletStores.walletIdOfAlias(it) == WalletIds.LEGACY })
        assertEquals(blocks, File(d.root, "files/saved_blocks.bin").readBytes().toList())
        assertNotNull(d.prefs.prefsFor("dgb_wallet_seed").getString("encrypted_seed_v2", null))
    }
}
