package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * At launch, an add the process did not live to complete is erased, and a removal is finished; an
 * entry is dropped only when its erase verified, so one that did not is retried at the next launch.
 */
class WalletHousekeepingTest {

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

    private val phrase = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

    private inner class Device {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val root: File = tmp.newFolder()
        val ctx: Context = wipeTestContext(prefs, root).also {
            every { it.deleteSharedPreferences(any()) } returns true
        }
        val registry = WalletRegistry(
            prefs = prefs.prefsFor(WalletRegistry.PREFS),
            recordExists = { scope ->
                val p = prefs.prefsFor(WalletStores.seedRecordPrefs(scope))
                p.contains("encrypted_seed") || p.contains("encrypted_seed_v2")
            },
            recordsOnDisk = { emptyList() },
        )

        fun eraser(releaseLegacyDbWrap: () -> Boolean = { true }) =
            WalletStoreEraser(ctx, vault.asKeystoreAliases(), releaseLegacyDbWrap, { name -> prefs.prefsFor(name).all.isNotEmpty() })

        fun housekeeping(releaseLegacyDbWrap: () -> Boolean = { true }) = WalletHousekeeping(registry, eraser(releaseLegacyDbWrap))

        fun installLegacy() {
            vault.createUnboundKey("dgb_wallet_master")
            vault.createUnboundKey("dgb_wallet_master_v2")
            val enc = vault.encrypt("dgb_wallet_master_v2", phrase.toByteArray())
            prefs.prefsFor("dgb_wallet_seed").apply {
                seed("encrypted_seed_v2", hex(enc.ciphertext))
                seed("encrypted_seed_iv_v2", hex(enc.iv))
            }
        }

        fun manager(scope: WalletScope) = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(scope)),
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            seedNative = { _, _ -> ByteArray(64) { 7 } },
            releaseDatabaseKey = { true },
            scope = scope,
            storesLeft = eraser()::storesLeft,
        )
    }

    @Test fun `an add killed after its record landed is erased at the next launch`() {
        val d = Device()
        d.installLegacy()
        val entry = d.registry.reserve(false, WalletOrigin.RESTORED, 1L)!!
        val scope = WalletScope(entry.id, false)
        assertTrue(d.manager(scope).storeRecordOnly(phrase, null, 1L, WalletOrigin.RESTORED))
        assertTrue(d.eraser().anythingLeft(scope))

        assertEquals(1, d.housekeeping().run())

        assertTrue(d.registry.incomplete().isEmpty())
        assertFalse(d.eraser().anythingLeft(scope))
        assertEquals(listOf("legacy"), d.registry.list(false).map { it.id })
    }

    @Test fun `a REMOVING entry whose erase does not verify is kept for the next launch`() {
        val d = Device()
        d.installLegacy()
        val other = d.registry.reserve(false, WalletOrigin.RESTORED, 1L)!!
        // A READY wallet is listed only while its record exists.
        d.prefs.prefsFor(WalletStores.seedRecordPrefs(WalletScope(other.id, false))).seed("encrypted_seed_v2", "x")
        assertTrue(d.registry.complete(other.id, makeActive = false))
        assertTrue(d.registry.markRemoving("legacy"))

        assertEquals(0, d.housekeeping(releaseLegacyDbWrap = { false }).run())
        assertEquals(EntryState.REMOVING, d.registry.find("legacy")!!.state)
        assertTrue("dgb_wallet_master" in d.vault.keys.keys)

        assertEquals(1, d.housekeeping(releaseLegacyDbWrap = { true }).run())
        assertNull(d.registry.find("legacy"))
        assertFalse(d.eraser().anythingLeft(WalletScope.legacy(false)))
        assertEquals(listOf(other.id), d.registry.list(false).map { it.id })
    }

    @Test fun `a READY wallet is never touched`() {
        val d = Device()
        d.installLegacy()
        val entry = d.registry.reserve(false, WalletOrigin.RESTORED, 1L)!!
        val scope = WalletScope(entry.id, false)
        assertTrue(d.manager(scope).storeRecordOnly(phrase, null, 1L, WalletOrigin.RESTORED))
        assertTrue(d.registry.complete(entry.id, makeActive = true))
        val before = d.prefs.names().associateWith { d.prefs.prefsFor(it).all.toMap() }
        val keys = d.vault.keys.keys.toSet()

        assertEquals(0, d.housekeeping().run())

        assertEquals(before, d.prefs.names().associateWith { d.prefs.prefsFor(it).all.toMap() })
        assertEquals(keys, d.vault.keys.keys.toSet())
    }

    @Test fun `housekeeping with nothing incomplete writes nothing`() {
        val d = Device()
        d.installLegacy()
        val keys = d.vault.keys.keys.toSet()

        assertEquals(0, d.housekeeping().run())

        assertTrue("dgb_wallets holds something", d.prefs.prefsFor(WalletRegistry.PREFS).all.isEmpty())
        assertEquals(keys, d.vault.keys.keys.toSet())
        assertEquals(listOf("legacy"), d.registry.list(false).map { it.id })
    }
}
