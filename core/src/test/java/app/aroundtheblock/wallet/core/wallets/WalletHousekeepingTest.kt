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
 * At launch, an add the process did not live to complete is erased, a removal is finished, and a
 * READY wallet whose seed record is gone (left by a wipe that erased the records but did not clear
 * the registry) is erased and dropped, so it holds no slot toward the cap. An entry is dropped only
 * when its erase verified, so one that did not is retried at the next launch. Legacy, and a READY
 * wallet that still has its record, are never touched.
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

        /**
         * Prefs files still on disk that read as empty: what the platform makes of a file it cannot
         * parse. The eraser's read-back (production: the file is on disk) still sees them.
         */
        val unreadableOnDisk = mutableSetOf<String>()

        fun eraser(releaseLegacyDbWrap: () -> Boolean = { true }) = WalletStoreEraser(
            ctx,
            vault.asKeystoreAliases(),
            releaseLegacyDbWrap,
            { name -> name in unreadableOnDisk || prefs.prefsFor(name).all.isNotEmpty() },
        )

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

        /** A wallet added to the end: its record and key written, its entry READY. */
        fun addReady(testnet: Boolean = false): WalletEntry {
            val entry = registry.reserve(testnet, WalletOrigin.CREATED, 1L)!!
            assertTrue(manager(WalletScope(entry.id, testnet)).storeRecordOnly(phrase, null, 1L, WalletOrigin.CREATED))
            assertTrue(registry.complete(entry.id, makeActive = false))
            return entry
        }

        /** Something in each kind of store of [scope]: a per-network and a per-wallet prefs file, a file, the database and the floor key. */
        fun leaveStores(scope: WalletScope) {
            prefs.prefsFor(WalletStores.prefsName("dgb_sync_data", scope)).seed("has_synced", true)
            prefs.prefsFor(WalletStores.prefsName("dgb_outgoing_tx", scope)).seed("sent", "a recorded send")
            File(root, "files/${WalletStores.fileName("saved_blocks", scope)}").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "databases/${WalletStores.databaseName(scope)}").writeBytes(byteArrayOf(4, 5, 6))
            prefs.prefsFor("dgb_settings").seed(WalletStores.cfBirthHeightKey(scope), 21_000_000L)
        }

        /** A wipe cut short after its first phase: [scope]'s record is gone, its key and its stores are not. */
        fun cutShortWipe(scope: WalletScope) {
            assertTrue(eraser().eraseRecord(scope))
            assertTrue(eraser().anythingLeft(scope))
        }

        fun prefsSnapshot(): Map<String, Map<String, Any?>> = prefs.names().associateWith { prefs.prefsFor(it).all.toMap() }

        fun fileSnapshot(): Map<String, List<Byte>> =
            root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
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

    @Test fun `a READY wallet whose record a cut-short wipe took is erased and dropped, freeing its slot and its number`() {
        val d = Device()
        d.installLegacy()
        // Legacy and seven more: the cap.
        val wallets = List(WalletRegistry.MAX_PER_NETWORK - 1) { d.addReady() }
        assertFalse(d.registry.canReserve(false))
        val cut = wallets[2]
        val scope = WalletScope(cut.id, false)
        d.leaveStores(scope)
        d.cutShortWipe(scope)
        // Hidden from the list, yet it still holds the eighth slot.
        assertFalse(d.registry.list(false).any { it.id == cut.id })
        assertFalse(d.registry.canReserve(false))

        assertEquals(1, d.housekeeping().run())

        assertNull(d.registry.find(cut.id))
        assertFalse("its key or a store is still on the device", d.eraser().anythingLeft(scope))
        assertTrue("its slot was not freed", d.registry.canReserve(false))
        assertEquals("its number was not freed", cut.number, d.registry.reserve(false, WalletOrigin.CREATED, 2L)!!.number)
    }

    @Test fun `a READY wallet with no record whose erase does not verify is kept for the next launch`() {
        val d = Device()
        d.installLegacy()
        val cut = d.addReady()
        val scope = WalletScope(cut.id, false)
        d.leaveStores(scope)
        d.cutShortWipe(scope)
        val sync = WalletStores.prefsName("dgb_sync_data", scope)
        d.prefs.refuseWritesTo(sync)

        assertEquals(0, d.housekeeping().run())
        assertEquals(EntryState.READY, d.registry.find(cut.id)?.state)
        assertTrue(d.eraser().storesLeft(scope))

        // The next launch, whose write lands.
        d.prefs.prefsFor(sync).refuseWrites = false
        assertEquals(1, d.housekeeping().run())
        assertNull(d.registry.find(cut.id))
        assertFalse(d.eraser().anythingLeft(scope))
    }

    @Test fun `only the READY wallet that lost its record is erased, never one that keeps it`() {
        val d = Device()
        d.installLegacy()
        val keep = d.addReady()
        val cut = d.addReady()
        val keepScope = WalletScope(keep.id, false)
        val cutScope = WalletScope(cut.id, false)
        d.leaveStores(keepScope)
        d.leaveStores(cutScope)
        d.cutShortWipe(cutScope)
        val prefsBefore = d.prefsSnapshot()
        val filesBefore = d.fileSnapshot()
        val keysBefore = d.vault.keys.keys.toSet()

        assertEquals(1, d.housekeeping().run())

        assertFalse(d.eraser().anythingLeft(cutScope))
        for ((name, held) in prefsBefore) {
            when {
                cut.id in name || name == WalletRegistry.PREFS -> Unit
                name == "dgb_settings" -> assertEquals(held - WalletStores.cfBirthHeightKey(cutScope), d.prefs.prefsFor(name).all)
                else -> assertEquals("store $name changed", held, d.prefs.prefsFor(name).all)
            }
        }
        assertEquals(filesBefore.filterKeys { cut.id !in it }, d.fileSnapshot())
        assertEquals(keysBefore.filterNot { cut.id in it }.toSet(), d.vault.keys.keys.toSet())
        assertEquals(listOf("legacy", keep.id), d.registry.list(false).map { it.id })
    }

    @Test fun `a testnet wallet that lost its record is erased on testnet`() {
        val d = Device()
        d.installLegacy()
        val cut = d.addReady(testnet = true)
        val scope = WalletScope(cut.id, true)
        d.leaveStores(scope)
        d.cutShortWipe(scope)

        assertEquals(1, d.housekeeping().run())

        assertNull(d.registry.find(cut.id))
        assertFalse(d.eraser().anythingLeft(scope))
        assertTrue(d.registry.canReserve(true))
    }

    @Test fun `legacy is never touched, even with its record gone`() {
        val d = Device()
        d.installLegacy()
        val cut = d.addReady()
        val legacy = WalletScope.legacy(false)
        val cutScope = WalletScope(cut.id, false)
        d.leaveStores(legacy)
        d.leaveStores(cutScope)
        // The wipe's first phase took every record, legacy's included.
        d.cutShortWipe(legacy)
        d.cutShortWipe(cutScope)
        val legacyPrefs = (WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS)
            .map { WalletStores.prefsName(it, legacy) }
            .associateWith { d.prefs.prefsFor(it).all.toMap() }
        val legacyFloor = d.prefs.prefsFor("dgb_settings").getLong(WalletStores.cfBirthHeightKey(legacy), -1L)
        val legacyFiles = d.fileSnapshot().filterKeys { cut.id !in it }

        assertEquals(1, d.housekeeping().run())

        assertFalse(d.eraser().anythingLeft(cutScope))
        assertEquals(legacyPrefs, legacyPrefs.keys.associateWith { d.prefs.prefsFor(it).all.toMap() })
        assertEquals(legacyFloor, d.prefs.prefsFor("dgb_settings").getLong(WalletStores.cfBirthHeightKey(legacy), -1L))
        assertEquals(legacyFiles, d.fileSnapshot())
        assertEquals(setOf("dgb_wallet_master", "dgb_wallet_master_v2"), d.vault.keys.keys.toSet())
        assertTrue(d.eraser().storesLeft(legacy))
    }

    @Test fun `a READY wallet whose record file is still on disk is never touched, though no seed can be read from it`() {
        val d = Device()
        d.installLegacy()
        val entry = d.addReady()
        val scope = WalletScope(entry.id, false)
        d.leaveStores(scope)
        // A record the platform cannot parse reads as empty, but its file is still on the disk.
        val record = WalletStores.seedRecordPrefs(scope)
        assertTrue(d.prefs.prefsFor(record).edit().clear().commit())
        d.unreadableOnDisk += record
        assertFalse(d.registry.list(false).any { it.id == entry.id })
        val prefsBefore = d.prefsSnapshot()
        val filesBefore = d.fileSnapshot()
        val keysBefore = d.vault.keys.keys.toSet()

        assertEquals(0, d.housekeeping().run())

        assertEquals(EntryState.READY, d.registry.find(entry.id)?.state)
        assertEquals(prefsBefore, d.prefsSnapshot())
        assertEquals(filesBefore, d.fileSnapshot())
        assertEquals(keysBefore, d.vault.keys.keys.toSet())
    }
}
