package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.RecordingPrefs
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.asKeystoreAliases
import app.aroundtheblock.wallet.core.keyStoreManagerOver
import app.aroundtheblock.wallet.core.keyTestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wallet other than `legacy` keeps its seed record in its own prefs file, under its own Keystore
 * aliases, and [WalletManager.storeRecordOnly] stores that record without loading the wallet into
 * native, touching any other store, or writing over state the scope already holds.
 *
 * The legacy scope is byte-for-byte the manager of 4.1.4: every test of `SeedRecordWriteTest`,
 * `SeedRecordIdentityTest` and `SeedKeyBindAtCreationTest` runs unchanged through the defaulted
 * `scope` parameter (legacy) and the defaulted `alias` of `keyStoreManagerOver` (`dgb_wallet_master`),
 * so no new assertion for legacy is made here.
 *
 * Pure JVM: native calls are injected; the Keystore is [FakeVault].
 */
class ScopedSeedRecordTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val scopeB = WalletScope("0123456789abcdef", testnet = false)
    private val seedPrefsB = "dgb_wallet_seed_0123456789abcdef"
    private val syncPrefsB = "dgb_sync_data_0123456789abcdef"

    private inner class Harness {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val seedStoreB = RecordingPrefs(prefs.prefsFor(seedPrefsB))
        val ctx: Context = keyTestContext(prefs, tmp.newFolder()).also {
            every { it.getSharedPreferences(seedPrefsB, any()) } returns seedStoreB
        }
        var restoredWith: ByteArray? = null
        // The production read-back over this harness: a prefs file is "left" when it holds something.
        val eraser = WalletStoreEraser(ctx, vault.asKeystoreAliases(), { true }, { name -> prefs.prefsFor(name).all.isNotEmpty() })

        fun walletManager(
            createNative: (ByteArray, ByteArray?) -> Boolean = { _, _ -> fail("native create called"); false },
            recoverNative: (ByteArray, Long, ByteArray?) -> Boolean = { _, _, _ -> fail("native recover called"); false },
            rescanNative: () -> Unit = { fail("rescan called") },
        ) = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(scopeB)),
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            createNative = createNative,
            recoverNative = recoverNative,
            seedNative = { _, _ -> ByteArray(64) { 7 } },
            rescanNative = rescanNative,
            birthHeightNative = { 21_500_000L },
            releaseDatabaseKey = { true },
            stopSyncForRebuild = { },
            scope = scopeB,
            restoreNative = { m, _, _ -> restoredWith = m; true },
            stopSyncNative = { },
            peerCountNative = { 0 },
            loadTxNative = { 0 },
            sleep = { },
            storesLeft = eraser::storesLeft,
        )
    }

    private fun store(wm: WalletManager) =
        wm.storeRecordOnly(phrase, null, 1_704_067_200L, WalletOrigin.RESTORED)

    @Test fun `a new wallet's record lands in its own prefs under its own aliases`() {
        val h = Harness()
        assertTrue(store(h.walletManager()))

        val record = h.prefs.prefsFor(seedPrefsB)
        assertTrue("record not in the wallet's own prefs", record.contains("encrypted_seed_v2"))
        assertTrue("the legacy record store was written", h.prefs.prefsFor("dgb_wallet_seed").all.isEmpty())
        assertEquals(setOf("dgb_wallet_master_0123456789abcdef_v2"), h.vault.keys.keys)
        assertEquals(1_704_067_200L, record.getLong("wallet_creation_time", -1L))
        assertEquals("restored", record.getString("wallet_origin", null))
        assertEquals("mainnet", record.getString("wallet_network", null))
        assertEquals("the record must be one write", 1, h.seedStoreB.writes.size)
        assertTrue("the record write must be a checked commit()", h.seedStoreB.writes.single().checked)
    }

    @Test fun `storing a record touches no native wallet and no other store`() {
        val h = Harness()
        val wm = h.walletManager()
        val before = h.prefs.names().associateWith { h.prefs.prefsFor(it).all }

        assertTrue(store(wm))

        for (name in h.prefs.names() - seedPrefsB) {
            assertEquals("store $name changed", before[name] ?: emptyMap<String, Any?>(), h.prefs.prefsFor(name).all)
        }
    }

    @Test fun `a record is never stored over existing state of its scope`() {
        val h = Harness()
        h.prefs.prefsFor(syncPrefsB).seed("has_synced", true)
        val wm = h.walletManager()

        assertFalse(store(wm))

        assertTrue("the record store was written", h.seedStoreB.writes.isEmpty())
        assertTrue("the record store holds something", h.prefs.prefsFor(seedPrefsB).all.isEmpty())
        assertTrue("a key was made", h.vault.keys.isEmpty())
        assertEquals(mapOf("has_synced" to true), h.prefs.prefsFor(syncPrefsB).all)
    }

    @Test fun `a record is never stored over an existing record of its scope`() {
        val h = Harness()
        val wm = h.walletManager()
        assertTrue(store(wm))
        val held = h.prefs.prefsFor(seedPrefsB).all

        assertFalse(store(wm))

        assertEquals(held, h.prefs.prefsFor(seedPrefsB).all)
        assertEquals(1, h.seedStoreB.writes.size)
    }

    @Test fun `restoreFromDisk for B reads B's record and does not clear B's sync data`() {
        val h = Harness()
        assertTrue(store(h.walletManager()))
        h.prefs.prefsFor(syncPrefsB).seed("has_synced", true)

        assertTrue(h.walletManager().restoreFromDisk())

        assertEquals(true, h.prefs.prefsFor(syncPrefsB).getBoolean("has_synced", false))
        val passed = h.restoredWith
        assertNotNull("restoreNative was not called", passed)
        assertTrue("the mnemonic handed to native was not zeroed", passed!!.all { it == 0.toByte() })
    }

    @Test fun `the record's identity and v1 fingerprint are read from the wallet's own record`() {
        val h = Harness()
        val wm = h.walletManager()
        assertEquals(null, wm.recordIdentity())
        assertEquals(null, wm.recordFingerprintV1())
        assertTrue(store(wm))

        assertEquals(h.prefs.prefsFor(seedPrefsB).getString("seed_fingerprint_v2", null), wm.recordIdentity())
        assertNotNull(wm.recordIdentity())
        h.prefs.prefsFor(seedPrefsB).seed("seed_fingerprint", "v1-fp")
        assertEquals("v1-fp", wm.recordFingerprintV1())
    }
}
