package app.aroundtheblock.wallet.core

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.settings.DisplayCurrencyPrefs
import app.aroundtheblock.wallet.core.sync.CfAbandonmentStore
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.sync.removeHeldKeys
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStoreEraser
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wallets.WipeOwedStores
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
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
 * WIPE-WRITES-NOTHING: a verified wipe creates no file.
 *
 * Every test lists the device's whole data directory before and after the wipe, over preferences
 * that behave like the platform's ([FilePrefsFactory]): opening a name creates no file, and a
 * `commit()` to a store whose file is missing writes one, changed or not. So a wipe step that
 * commits a clear to a store that is not there leaves an empty file the wipe itself made.
 *
 * One file is the exception, and it is asserted by name: the display currency is a device setting
 * a wipe keeps (owner decision, 2026-11-12), so a value found in the sync data the wipe erases is
 * moved to its own file first. That file holds the setting and nothing of a wallet.
 */
class WipeWritesNothingTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        ActiveWallet.resetForTest()
        WipeOwedStores.resetForTest()
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
        ActiveWallet.resetForTest()
        WipeOwedStores.resetForTest()
        ChainTipStore.invalidateCache()
    }

    private inner class Device {
        val root: File = tmp.newFolder()
        val ctx: Context
        val prefs: FilePrefsFactory
        init { filePrefsContext(root).also { (c, p) -> ctx = c; prefs = p } }
        val vault = FakeVault()
        val aliases = vault.asKeystoreAliases()
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        /** Every file under the data directory now, by its path from there. */
        fun listing(): Set<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSortedSet()

        fun prefsFile(name: String) = File(root, "shared_prefs/$name.xml")

        /** A device upgraded from 4.1.4: one wallet, legacy, with the stores a synced wallet has. */
        fun populateLegacy() {
            prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "c1")
            prefs.prefsFor("dgb_sync_data").seed("has_synced", true)
            prefs.prefsFor("dgb_settings").seed("cf_birth_height", 100L)
            vault.createUnboundKey("dgb_wallet_master")
            File(ctx.filesDir, "saved_blocks.bin").writeBytes(byteArrayOf(1))
        }

        /** A second wallet, made active, with its record, a store and its key. */
        fun addWalletB(): WalletScope {
            val registry = ActiveWallet.registry(ctx)
            val entry = registry.reserve(false, WalletOrigin.CREATED, 1L)!!
            val b = WalletScope(entry.id, false)
            prefs.prefsFor(WalletStores.seedRecordPrefs(b)).seed("encrypted_seed_v2", "c2")
            prefs.prefsFor(WalletStores.prefsName("dgb_sync_data", b)).seed("held", "x")
            vault.createUnboundKey(WalletStores.keyAliasV2(b))
            assertTrue(registry.complete(entry.id, makeActive = true))
            ActiveWallet.resetForTest()
            return b
        }

        fun walletManager(scope: WalletScope = WalletScope.legacy(false), context: Context = ctx) = WalletManager(
            context = context,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(scope)),
            utxoManager = mockk(relaxed = true),
            dataEraser = AndroidWalletDataEraser(context, aliases) {
                HubTokenStore(context, WalletScope.legacy(false)) { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE) }
            },
            quiesceNative = { },
            scope = scope,
            scopeEraser = WalletStoreEraser(context, aliases, { true }) { name -> prefsFile(name).exists() },
            keystoreAliases = aliases,
        )
    }

    @Test fun `a verified wipe of a one-wallet device creates no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val before = d.listing()

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertFalse("a verified wipe clears the PIN store", d.pin.hasPin())
        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
    }

    @Test fun `a verified wipe of a device with two wallets creates no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val b = d.addWalletB()
        val before = d.listing()

        val report = d.walletManager(scope = b).wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
    }

    @Test fun `a second wipe of a wiped device creates no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        assertTrue(d.walletManager().wipeThenReleasePin(d.pin).verified)
        ActiveWallet.resetForTest()
        val before = d.listing()

        val report = d.walletManager().wipeWallet()

        assertTrue("$report", report.verified)
        assertEquals("files the second wipe created", emptySet<String>(), d.listing() - before)
    }

    /**
     * Every store the full wipe clears, held, so each step has something to erase. What was there
     * may stay as an emptied file or go; nothing new may appear.
     */
    @Test fun `a verified wipe of a device holding every store creates no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val nets = listOf("", "_testnet")
        val perNetwork = listOf(
            "dgb_sync_data", "dgb_cf_abandonment", "dgb_history_rebuild", "dgb_reconcile",
            "dgb_bloom_peers", "dgb_filter_peers", "dgb_dandelion_peers",
        ).flatMap { base -> nets.map { base + it } }
        val single = listOf(
            "dgb_watched_addrs", "dgb_outgoing_tx", "dgb_asset_backfill", "dgb_asset_heal", "dgb_diag",
            "dgb_quickstart", "dgb_db_key", "dgb_auto_lock", HubTokenStore.LEGACY_PREFS_NAME, HubTokenStore.SECURE_PREFS_NAME,
        )
        (perNetwork + single).forEach { d.prefs.prefsFor(it).seed("held", "x") }
        val before = d.listing()

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
    }

    /**
     * A store this process still shows values for while its file is not on the device (deleted under
     * it, or never written): the clear's commit writes an empty file. The wipe made that file, so
     * the wipe removes it.
     */
    @Test fun `a store held in memory with no file is cleared and leaves no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val heldOnlyInMemory = listOf("dgb_watched_addrs", "dgb_sync_data_testnet", "dgb_cf_abandonment", "dgb_bloom_peers")
        heldOnlyInMemory.forEach { name ->
            d.prefs.prefsFor(name).seed("held", "x")
            assertTrue(d.prefsFile(name).delete())
        }
        val before = d.listing()

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        heldOnlyInMemory.forEach { name ->
            assertEquals("$name is still held", emptyMap<String, Any?>(), d.ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }
        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
    }

    /** "Verified" also means nothing the wipe created: a file it made and could not remove keeps the PIN. */
    @Test fun `a file the wipe created and cannot remove keeps the wipe unverified`() = runTest {
        for (name in listOf("dgb_watched_addrs", "dgb_cf_abandonment_testnet")) {
            val d = Device().apply { populateLegacy() }
            d.prefs.prefsFor(name).seed("held", "x")
            assertTrue(d.prefsFile(name).delete())
            // The platform's delete does nothing for this one name.
            val real = d.prefs
            every { d.ctx.deleteSharedPreferences(any()) } answers { if (firstArg<String>() == name) false else real.delete(firstArg()) }

            val report = d.walletManager().wipeThenReleasePin(d.pin)

            assertTrue("$name: precondition: the file the wipe made is on the device", d.prefsFile(name).exists())
            assertTrue("$name: no seed step waits on it", report.seedGone)
            assertFalse("$name: a file the wipe created is left, yet the wipe verified", report.verified)
            assertTrue("$name: the PIN store must be kept", d.pin.hasPin())
            ActiveWallet.resetForTest()
            WipeOwedStores.resetForTest()
        }
    }

    /** The one file a wipe may create, by name, and only when it has a setting to keep in it. */
    @Test fun `a display currency in the sync data is moved to its own file and nothing else is created`() = runTest {
        val d = Device().apply { populateLegacy() }
        d.prefs.prefsFor("dgb_sync_data").seed(DisplayCurrencyPrefs.KEY, "EUR")
        val before = d.listing()

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals("files the wipe created", setOf("shared_prefs/dgb_price.xml"), d.listing() - before)
        assertEquals(mapOf(DisplayCurrencyPrefs.KEY to "EUR"), d.ctx.getSharedPreferences("dgb_price", Context.MODE_PRIVATE).all)
    }

    /**
     * The encrypted Hub store writes its keyset into its file as it is opened. On a device where it
     * was never opened, asking it for the token makes that file: the wipe made it, so it removes it.
     */
    @Test fun `a store that writes its file when it is opened leaves no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val opensWithAKeyset = { name: String -> d.prefs.prefsFor(name).also { it.seed("__keyset__", "k") } }
        val eraser = AndroidWalletDataEraser(d.ctx, d.aliases) { HubTokenStore(d.ctx, WalletScope.legacy(false), opensWithAKeyset) }
        val before = d.listing()

        assertTrue(eraser.eraseHubSession())

        assertEquals("files the step created", emptySet<String>(), d.listing() - before)
    }

    /** A store that was on the device before the wipe is not the wipe's to delete here. */
    @Test fun `a Hub store that was already on the device keeps its file and loses its token`() = runTest {
        val d = Device().apply { populateLegacy() }
        d.prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME).apply { seed("__keyset__", "k"); seed(HubTokenStore.KEY_JWT, "jwt") }
        val eraser = AndroidWalletDataEraser(d.ctx, d.aliases) {
            HubTokenStore(d.ctx, WalletScope.legacy(false)) { name -> d.prefs.prefsFor(name) }
        }

        assertTrue(eraser.eraseHubSession())

        assertTrue(d.prefsFile(HubTokenStore.SECURE_PREFS_NAME).exists())
        assertEquals(setOf("__keyset__"), d.prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME).all.keys)
    }

    @Test fun `removing legacy keys a store does not hold writes nothing`() {
        val d = Device()
        val store = d.prefs.prefsFor("dgb_sync_data_testnet")

        assertTrue(removeHeldKeys("test", store, "saved_blocks", "saved_transactions"))

        assertEquals(emptySet<String>(), d.listing())
    }

    @Test fun `removing a legacy key a store holds removes it and keeps the rest`() {
        val d = Device()
        val store = d.prefs.prefsFor("dgb_sync_data").apply { seed("saved_blocks", "00"); seed("other", "x") }

        assertTrue(removeHeldKeys("test", store, "saved_blocks", "absent"))

        assertEquals(mapOf("other" to "x"), d.ctx.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).all)
    }

    /** A removal that did not land is reported, and a retry in this process still sees the key. */
    @Test fun `a legacy key whose removal does not land is reported and still read`() {
        val store = InMemoryPrefs().apply { seed("saved_blocks", "00"); refuseWrites = true }

        assertFalse(removeHeldKeys("test", store, "saved_blocks"))

        assertTrue("this process no longer reads what the file holds", store.contains("saved_blocks"))
        store.refuseWrites = false
        assertTrue(removeHeldKeys("test", store, "saved_blocks"))
        assertFalse(store.durable.containsKey("saved_blocks"))
    }

    @Test fun `an abandoned band whose clear does not land is reported and still read`() {
        val prefs = InMemoryPrefsFactory()
        val ctx = wipeTestContext(prefs, tmp.newFolder())
        prefs.prefsFor("dgb_cf_abandonment_testnet").apply { seed("band_low", 5L); seed("band_high", 9L) }
        prefs.refuseWritesTo("dgb_cf_abandonment_testnet")

        assertFalse(CfAbandonmentStore.clearAllNetworks(ctx))

        assertEquals(setOf("band_low", "band_high"), prefs.prefsFor("dgb_cf_abandonment_testnet").all.keys)
    }

    @Test fun `clearing an abandoned band that is not there creates no file`() {
        val d = Device()

        assertTrue(CfAbandonmentStore.clearAllNetworks(d.ctx))

        assertEquals("files a clear of nothing created", emptySet<String>(), d.listing())
    }
}
