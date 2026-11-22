package app.aroundtheblock.wallet.core

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.settings.AutoLockPrefs
import app.aroundtheblock.wallet.core.settings.DisplayCurrencyPrefs
import app.aroundtheblock.wallet.core.sync.CfAbandonmentStore
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.sync.removeHeldKeys
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStoreEraser
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wallets.WipeOwedStores
import io.mockk.clearMocks
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
 * Two files are the exceptions, and each is asserted by name. The display currency is a device
 * setting a wipe keeps (owner decision, 2026-11-12), so a value found in the sync data the wipe
 * erases is moved to its own file first; that file holds the setting and nothing of a wallet. And
 * `dgb_settings` holds device settings the wipe keeps: one key is taken out, the file stays.
 *
 * The last three tests build every starting state this model can (one wallet or two, either one
 * running; every store absent, on the device, held by the process with no file, or an empty file;
 * each store in turn held another way) and then refuse a delete, and a write, for every store in
 * turn. They are a JVM model: the app module's half of a wipe is stood in for by the Hub reads
 * and clears it makes ([Device.walletManagerWithHub]); its two marks, the PIN store's file, the
 * database and the WebView's data are not here.
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
        var prefs: FilePrefsFactory
        /** Every preferences name asked for, and the names whose delete the platform refuses. */
        val opened = sortedSetOf<String>()
        val deleteRefused = HashSet<String>()
        init {
            filePrefsContext(root).also { (c, p) -> ctx = c; prefs = p }
            every { ctx.getSharedPreferences(any(), any()) } answers { opened += firstArg<String>(); prefs.prefsFor(firstArg()) }
            every { ctx.deleteSharedPreferences(any()) } answers { firstArg<String>() !in deleteRefused && prefs.delete(firstArg()) }
        }
        val vault = FakeVault()
        val aliases = vault.asKeystoreAliases()
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        private val mocks = ArrayList<Any>()

        /** Lets go of what the mocks of this device recorded: the tests over many devices would otherwise keep it all. */
        fun dispose() {
            clearMocks(ctx, *mocks.toTypedArray())
            root.deleteRecursively()
        }

        /** The next launch: a new process holds nothing of the one before, and reads what the files hold. */
        fun restart() {
            prefs = FilePrefsFactory(File(root, "shared_prefs"))
            ActiveWallet.resetForTest()
            WipeOwedStores.resetForTest()
            ChainTipStore.invalidateCache()
        }

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
        fun addWalletB(active: Boolean = true): WalletScope {
            val registry = ActiveWallet.registry(ctx)
            val entry = registry.reserve(false, WalletOrigin.CREATED, 1L)!!
            val b = WalletScope(entry.id, false)
            prefs.prefsFor(WalletStores.seedRecordPrefs(b)).seed("encrypted_seed_v2", "c2")
            prefs.prefsFor(WalletStores.prefsName("dgb_sync_data", b)).seed("held", "x")
            vault.createUnboundKey(WalletStores.keyAliasV2(b))
            assertTrue(registry.complete(entry.id, makeActive = active))
            ActiveWallet.resetForTest()
            return b
        }

        /**
         * The encrypted Hub store as the platform's behaves: its file is written (a keyset) when it
         * is opened, whatever is then asked of it.
         */
        val opensWithAKeyset = { name: String -> ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { if (!prefsFile(name).exists()) prefs.prefsFor(name).seed("__keyset__", "k") } }

        /**
         * The wipe with the Hub wired as the app wires it, modelled: each wallet's stored token is
         * read for the revoke before anything is erased (`DigiScopeClient.revokeStoredSession`), and
         * the identity step reads and clears the running wallet's (`DigiScopeClient`'s start, then
         * `logout`). Every Hub store is the kind that writes its file when it is opened.
         */
        fun walletManagerWithHub(scope: WalletScope = WalletScope.legacy(false)) = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(scope)).also { mocks += it },
            utxoManager = mockk<UtxoManager>(relaxed = true).also { mocks += it },
            dataEraser = AndroidWalletDataEraser(ctx, aliases) { HubTokenStore(ctx, WalletScope.legacy(false), opensWithAKeyset) },
            quiesceNative = { },
            identitySessions = { HubTokenStore(ctx, scope, opensWithAKeyset).run { load(); clear() }; true },
            hubRevoke = { s -> HubTokenStore(ctx, s, opensWithAKeyset).stored() },
            scope = scope,
            scopeEraser = WalletStoreEraser(ctx, aliases, { true }) { name -> prefsFile(name).exists() },
            keystoreAliases = aliases,
        )

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

    // ── The registry (review finding 1) ──────────────────────────────────────

    /**
     * A device that never had a second wallet has no registry file. The wipe's own later delete of
     * that name is taken away here, so a file the registry step made would be seen.
     */
    @Test fun `a wipe of a device with no registry file writes none`() = runTest {
        val d = Device().apply { populateLegacy() }
        d.deleteRefused += WalletRegistry.PREFS
        val before = d.listing()

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
        assertTrue("$report", report.verified)
        assertFalse("a verified wipe clears the PIN store", d.pin.hasPin())
    }

    /** A registry this process holds with its file gone: the clear writes the file, so the wipe removes it. */
    @Test fun `a registry held in memory with no file is cleared and leaves no file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val b = d.addWalletB()
        assertTrue(d.prefsFile(WalletRegistry.PREFS).delete())
        val before = d.listing()

        val report = d.walletManager(scope = b).wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals(emptyMap<String, Any?>(), d.ctx.getSharedPreferences(WalletRegistry.PREFS, Context.MODE_PRIVATE).all)
        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
    }

    /** The registry's file is under the verdict like any other: made by the wipe and not removable, the PIN stays. */
    @Test fun `a registry file the wipe created and cannot remove keeps the wipe unverified`() = runTest {
        val d = Device().apply { populateLegacy() }
        val b = d.addWalletB()
        assertTrue(d.prefsFile(WalletRegistry.PREFS).delete())
        d.deleteRefused += WalletRegistry.PREFS

        val report = d.walletManager(scope = b).wipeThenReleasePin(d.pin)

        assertTrue("precondition: the file the wipe made is on the device", d.prefsFile(WalletRegistry.PREFS).exists())
        assertTrue("no seed step waits on it", report.seedGone)
        assertFalse("a file the wipe created is left, yet the wipe verified", report.verified)
        assertTrue("the PIN store must be kept", d.pin.hasPin())
    }

    // ── A step that fails leaves no file either (review finding 2) ───────────

    /**
     * One step, two stores: the first refuses its write, the second is held in memory with no file,
     * so its clear writes one. The step is false, and the file it made must not be there for the
     * retry to find "already on the device".
     */
    @Test fun `a file made beside a store whose write did not land is not left for the retry`() = runTest {
        val d = Device().apply { populateLegacy() }
        d.prefs.prefsFor("dgb_cf_abandonment").apply { seed("band_low", 5L); refuseWrites = true }
        d.prefs.prefsFor("dgb_cf_abandonment_testnet").seed("band_low", 7L)
        assertTrue(d.prefsFile("dgb_cf_abandonment_testnet").delete())
        val eraser = AndroidWalletDataEraser(d.ctx, d.aliases) { HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset) }
        val before = d.listing()

        assertFalse("a write did not land", eraser.eraseCfSyncState())
        assertEquals("files the failed step left", emptySet<String>(), d.listing() - before)

        d.prefs.prefsFor("dgb_cf_abandonment").refuseWrites = false
        assertTrue(eraser.eraseCfSyncState())
        assertEquals("files the retry left", emptySet<String>(), d.listing() - before)
    }

    /** The same for the Hub step: the plain store refuses, the encrypted one was never opened here. */
    @Test fun `a Hub store first opened beside a refused write is not left for the retry`() = runTest {
        val d = Device().apply { populateLegacy() }
        d.prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).apply { seed(HubTokenStore.KEY_JWT, "jwt"); refuseWrites = true }
        val eraser = AndroidWalletDataEraser(d.ctx, d.aliases) { HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset) }
        val before = d.listing()

        assertFalse("a write did not land", eraser.eraseHubSession())
        assertEquals("files the failed step left", emptySet<String>(), d.listing() - before)

        d.prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).refuseWrites = false
        assertTrue(eraser.eraseHubSession())
        assertEquals("files the retry left", emptySet<String>(), d.listing() - before)
    }

    /**
     * A single store held in memory with no file, whose clear does not land while the rewrite of
     * what it held does: that rewrite is a file the step made, holding what was never on the device.
     */
    @Test fun `a failed clear of a store with no file leaves the device as it found it`() = runTest {
        val d = Device().apply { populateLegacy() }
        val store = d.prefs.prefsFor("dgb_watched_addrs").apply { seed("held", "x") }
        assertTrue(d.prefsFile("dgb_watched_addrs").delete())
        store.refuseNextWrites = 1
        val eraser = AndroidWalletDataEraser(d.ctx, d.aliases) { HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset) }
        val before = d.listing()

        assertFalse("the clear did not land", eraser.eraseWatchedAddresses())

        assertEquals("files the failed step left", emptySet<String>(), d.listing() - before)
        assertEquals("this process reads what it read before", mapOf("held" to "x"), d.ctx.getSharedPreferences("dgb_watched_addrs", Context.MODE_PRIVATE).all)
        assertTrue(eraser.eraseWatchedAddresses())
        assertEquals("files the retry left", emptySet<String>(), d.listing() - before)
    }

    // ── The Hub, wired as the app wires it ───────────────────────────────────

    /** Reading a stored token for the revoke, and the identity step's read and clear, open no store that is not there. */
    @Test fun `a wipe of a device that never opened the Hub creates no Hub file`() = runTest {
        val d = Device().apply { populateLegacy() }
        val before = d.listing()

        val report = d.walletManagerWithHub().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals("files the wipe created", emptySet<String>(), d.listing() - before)
    }

    /** A token still in the plain store is read for the revoke where it is: the read moves nothing. */
    @Test fun `reading a stored Hub token writes nothing`() {
        val d = Device()
        d.prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).seed(HubTokenStore.KEY_JWT, "plain")
        val before = d.listing()

        assertEquals("plain", HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset).stored())
        assertEquals("files the read created", emptySet<String>(), d.listing() - before)

        d.prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME).apply { seed("__keyset__", "k"); seed(HubTokenStore.KEY_JWT, "sealed") }
        assertEquals("the encrypted copy is the newer session", "sealed", HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset).stored())
        assertEquals("plain", d.prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).getString(HubTokenStore.KEY_JWT, null))
    }

    @Test fun `loading and clearing a Hub session that was never stored creates no file`() {
        val d = Device()
        val store = HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset)

        assertEquals(null, store.load())
        store.clear()
        assertTrue(store.clearConfirmed())

        assertEquals(emptySet<String>(), d.listing())
        store.save("jwt")
        assertEquals("a saved session is still stored and read", "jwt", HubTokenStore(d.ctx, WalletScope.legacy(false), d.opensWithAKeyset).load())
    }

    // ── The named exception that is not the wipe's file to delete ────────────

    /**
     * `dgb_settings` is outside the rule on purpose: it holds device settings (the language, the
     * network, the own node) and the wipe takes one key out of it. Held by this process with its
     * file gone, the removal's commit writes the file back, and the wipe keeps it.
     */
    @Test fun `device settings held in memory with no file are written back and kept`() = runTest {
        val d = Device().apply { populateLegacy() }
        d.prefs.prefsFor("dgb_settings").seed("language", "de")
        assertTrue(d.prefsFile("dgb_settings").delete())
        val before = d.listing()

        val report = d.walletManager().wipeThenReleasePin(d.pin)

        assertTrue("$report", report.verified)
        assertEquals("files the wipe created", setOf("shared_prefs/dgb_settings.xml"), d.listing() - before)
        d.restart()
        assertEquals(mapOf("language" to "de"), d.ctx.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE).all)
    }

    // ── Every starting state, listed before and after ────────────────────────

    private enum class Held { ABSENT, ON_DISK, MEMORY_ONLY, EMPTY_FILE }
    private enum class Wallets(val two: Boolean, val bActive: Boolean) { ONE(false, false), TWO_B_ACTIVE(true, true), TWO_LEGACY_ACTIVE(true, false) }

    /** One device before a wipe: how many wallets, how every optional store is held, and one store held another way. */
    private data class State(val wallets: Wallets, val base: Held, val one: Pair<String, Held>? = null) {
        override fun toString() = "$wallets, every store $base" + (one?.let { ", ${it.first} ${it.second}" } ?: "")
    }

    private val legacyOnBoth = listOf(false, true).map(WalletScope::legacy)
    private val legacySeed = WalletStores.seedRecordPrefs(WalletScope.legacy(false))

    /** Every store of legacy's and of the device that a wipe may open, and that a state here holds one way or another. */
    private val variedNames: List<String> = (
        legacyOnBoth.flatMap { s ->
            WalletStores.WALLET_AND_NETWORK_PREFS.map { WalletStores.prefsName(it, s) } +
                WalletStores.BAND_PREFS.map { WalletStores.bandPrefsName(it, s) } +
                WalletStores.NETWORK_PREFS.map { WalletStores.prefsName(it, s) }
        } +
            (WalletStores.WALLET_PREFS + WalletStores.RESERVED_WALLET_PREFS).map { WalletStores.prefsName(it, WalletScope.legacy(false)) } +
            listOf("dgb_db_key", AutoLockPrefs.PREFS, LogTag.PREFS, "dgb_settings", WipeOwedStores.PREFS)
        ).distinct()

    /** Every preferences store of another wallet, on both networks. */
    private fun namesOf(b: WalletScope): List<String> = listOf(b, b.copy(testnet = !b.testnet)).flatMap { s ->
        WalletStores.WALLET_AND_NETWORK_PREFS.map { WalletStores.prefsName(it, s) }
    }.plus((WalletStores.WALLET_PREFS + WalletStores.RESERVED_WALLET_PREFS).map { WalletStores.prefsName(it, b) }).distinct()

    private fun Device.hold(name: String, how: Held) {
        if (how == Held.ABSENT) return
        val store = prefs.prefsFor(name)
        when {
            how == Held.EMPTY_FILE -> assertTrue(store.edit().commit())
            name.startsWith("dgb_wallet_seed") -> store.seed("encrypted_seed_v2", "c1")
            name == "dgb_settings" -> { store.seed("cf_birth_height", 100L); store.seed("language", "de") }
            name.startsWith(HubTokenStore.LEGACY_PREFS_NAME) || name.startsWith(HubTokenStore.SECURE_PREFS_NAME) -> store.seed(HubTokenStore.KEY_JWT, "jwt")
            else -> store.seed("held", "x")
        }
        if (how == Held.MEMORY_ONLY) assertTrue(prefsFile(name).delete())
    }

    /** Puts the device in [state]; answers the other wallet when there is one, and the wallet that is running. */
    private fun Device.arrange(state: State): Pair<WalletScope?, WalletScope> {
        val b = if (state.wallets.two) addWalletB(active = state.wallets.bActive) else null
        val names = variedNames + b?.let(::namesOf).orEmpty()
        val held = names.associateWith { name ->
            // A wallet has its record unless the state says otherwise by name.
            if (name == legacySeed || (b != null && name == WalletStores.seedRecordPrefs(b))) Held.ON_DISK else state.base
        } + listOfNotNull(state.one?.takeIf { it.first != WalletRegistry.PREFS }).toMap()
        held.forEach { (name, how) -> hold(name, how) }
        if (held.getValue(legacySeed) != Held.ABSENT) vault.createUnboundKey("dgb_wallet_master")
        if (state.base == Held.ON_DISK) {
            legacyOnBoth.flatMap { s -> WalletStores.WALLET_AND_NETWORK_FILES.map { WalletStores.fileName(it, s) } }
                .forEach { File(ctx.filesDir, it).writeBytes(byteArrayOf(1)) }
        }
        // The registry is the registry's to write: held another way only by taking its file away, or by an empty one.
        when (state.one?.takeIf { it.first == WalletRegistry.PREFS }?.second) {
            Held.MEMORY_ONLY -> assertTrue(prefsFile(WalletRegistry.PREFS).delete())
            Held.EMPTY_FILE -> assertTrue(prefs.prefsFor(WalletRegistry.PREFS).edit().commit())
            else -> Unit
        }
        ActiveWallet.resetForTest()
        opened.clear()
        return b to (if (b != null && state.wallets.bActive) b else WalletScope.legacy(false))
    }

    /** Every name a state may hold or the wipe may open on a device with [b]. */
    private fun knownNames(b: WalletScope?): List<String> = (variedNames + WalletRegistry.PREFS + b?.let(::namesOf).orEmpty()).distinct()

    /**
     * The files a wipe may make in [state], by name: `dgb_settings`, which is not the wipe's to
     * delete (it holds device settings), when this process held it with no file and the wipe took
     * the scan floor out of it.
     */
    private fun allowed(state: State): Set<String> {
        val settings = if (state.one?.first == "dgb_settings") state.one.second else state.base
        return if (settings == Held.MEMORY_ONLY) setOf("shared_prefs/dgb_settings.xml") else emptySet()
    }

    /** The first few of [problems], one a line, and how many there are. */
    private fun summary(problems: List<String>): String = problems.distinct().let { all ->
        if (all.isEmpty()) "" else "${all.size} problems, the first:\n" + all.take(12).joinToString("\n")
    }

    private val bases = Wallets.values().flatMap { w -> Held.values().map { State(w, it) } }

    private fun everyState(): List<State> = bases + Wallets.values().flatMap { w ->
        val fromNothing = variedNames.flatMap { n -> listOf(Held.ON_DISK, Held.MEMORY_ONLY, Held.EMPTY_FILE).map { State(w, Held.ABSENT, n to it) } }
        val fromEverything = variedNames.flatMap { n -> listOf(Held.ABSENT, Held.MEMORY_ONLY, Held.EMPTY_FILE).map { State(w, Held.ON_DISK, n to it) } }
        val registry = listOf(Held.ABSENT, Held.ON_DISK).map { State(w, it, WalletRegistry.PREFS to (if (w.two) Held.MEMORY_ONLY else Held.EMPTY_FILE)) }
        fromNothing + fromEverything + registry
    }

    /**
     * Every starting state this test can build, wiped with nothing refused: each wipe verifies,
     * makes no file, and opens no store that the states here do not hold in every way.
     */
    @Test fun `no starting state has a verified wipe leave a file it made`() = runTest {
        val problems = ArrayList<String>()
        val states = everyState()
        for (state in states) {
            val d = Device()
            val (b, running) = d.arrange(state)
            val before = d.listing()

            val report = d.walletManagerWithHub(running).wipeThenReleasePin(d.pin)

            val made = d.listing() - before - allowed(state)
            if (!report.verified) problems += "$state: not verified ($report)"
            if (made.isNotEmpty()) problems += "$state: verified=${report.verified}, made $made"
            val unknown = d.opened - knownNames(b).toSet()
            if (unknown.isNotEmpty()) problems += "$state: opens $unknown, which no state here holds"
            d.dispose()
        }
        assertEquals("of ${states.size} states", "", summary(problems))
    }

    /**
     * A delete refused for each store in turn: the wipe either verifies with no file of its own on
     * the device, or it does not verify and the PIN stays.
     */
    @Test fun `a delete refused for any one store never has a wipe verify with a file it made`() = runTest {
        val problems = ArrayList<String>()
        var unverified = 0
        var runs = 0
        for (state in bases) {
            val count = Device().let { probe -> knownNames(probe.arrange(state).first).size.also { probe.dispose() } }
            for (i in 0 until count) {
                val d = Device()
                val (b, running) = d.arrange(state)
                val name = knownNames(b)[i]
                d.deleteRefused += name
                val before = d.listing()

                val report = d.walletManagerWithHub(running).wipeThenReleasePin(d.pin)

                runs++
                val made = d.listing() - before - allowed(state)
                val what = "$state, delete of ${name.replace(b?.walletId ?: "\u0000", "<b>")} refused"
                if (report.verified && made.isNotEmpty()) problems += "$what: verified with $made"
                if (report.verified == d.pin.hasPin()) problems += "$what: verified=${report.verified} with PIN kept=${d.pin.hasPin()}"
                if (!report.verified) unverified++
                d.dispose()
            }
        }
        assertEquals("of $runs wipes", "", summary(problems))
        assertTrue("no refused delete ever kept a wipe unverified: the refusals reach nothing", unverified > 0)
    }

    /**
     * A write refused for each store in turn, then allowed again: neither that wipe nor the next one
     * (in the same process, and after a restart) verifies with a file on the device that a wipe made.
     */
    @Test fun `a write refused for any one store never has that wipe or the next verify with a file a wipe made`() = runTest {
        val problems = ArrayList<String>()
        var unverified = 0
        var retriesVerified = 0
        for (state in bases) {
            val count = Device().let { probe -> knownNames(probe.arrange(state).first).size.also { probe.dispose() } }
            for (i in 0 until count) for (restart in listOf(false, true)) {
                val d = Device()
                val (b, running) = d.arrange(state)
                val name = knownNames(b)[i]
                d.prefs.prefsFor(name).refuseWrites = true
                val before = d.listing()
                val what = "$state, write to ${name.replace(b?.walletId ?: "\u0000", "<b>")} refused"

                val first = d.walletManagerWithHub(running).wipeThenReleasePin(d.pin)

                val madeFirst = d.listing() - before - allowed(state)
                if (first.verified && madeFirst.isNotEmpty()) problems += "$what: verified with $madeFirst"
                if (!first.verified) unverified++

                if (restart) d.restart() else { d.prefs.prefsFor(name).refuseWrites = false; ActiveWallet.resetForTest() }
                val second = d.walletManagerWithHub(running).wipeThenReleasePin(d.pin)

                val madeSecond = d.listing() - before - allowed(state)
                val how = if (restart) "after a restart" else "in the same process"
                if (second.verified && madeSecond.isNotEmpty()) problems += "$what: the next wipe $how verified with $madeSecond"
                if (second.verified) retriesVerified++
                d.dispose()
            }
        }
        assertEquals("", summary(problems))
        assertTrue("no refused write ever kept a wipe unverified: the refusals reach nothing", unverified > 0)
        assertTrue("no retry ever verified: the second wipes show nothing", retriesVerified > 0)
    }
}
