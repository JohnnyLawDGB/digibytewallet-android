package io.digibyte.core

import android.util.Log
import io.digibyte.core.digiscope.HubTokenStore
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.FilterHeaderStore
import io.digibyte.core.sync.SavedBlockStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
 * Verifies the complete destructive routine [WalletManager.wipeWallet] runs — the
 * single path shared by the manual Settings wipe and the PIN wipe-after-N backstop —
 * and the one sequence every entry point runs around it, [WalletManager.wipeThenReleasePin].
 *
 * What is established here:
 *  - a wipe reports success only when the seed is gone by READ-BACK (no saved wallet, no
 *    seed key alias) and every store reported cleared;
 *  - the PIN store is cleared only after that;
 *  - one wipe covers both networks' stores, the leftover per-wallet state, the Hub token
 *    and the identity sessions.
 *
 * Pure JVM: the native quiesce is injected (so NativeBridge's `System.loadLibrary` never
 * runs); the data-erasure side effects are either captured by a fake [WalletDataEraser]
 * whose invocation order is asserted (seed FIRST) or driven through the real
 * [AndroidWalletDataEraser] over in-memory preferences and a scratch directory.
 */
class WalletWipeTest {

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

    /** Records each step; a step named in [refuses] reports false, one in [throws] throws. */
    private class RecordingEraser(
        private val refuses: Set<String> = emptySet(),
        private val throws: Set<String> = emptySet(),
        private val seedKeyStillThere: Boolean = false,
    ) : WalletDataEraser {
        val calls = mutableListOf<String>()
        private fun step(name: String): Boolean {
            calls += name
            if (name in throws) throw IllegalStateException("store unavailable")
            return name !in refuses
        }
        override fun eraseSeedCiphertext() = step("seed")
        override fun eraseSyncData() = step("sync")
        override fun eraseBloomPeerCache() = step("bloom")
        override fun eraseWatchedAddresses() = step("watched")
        override fun eraseOutgoingTx() = step("outgoing")
        override fun eraseCfSyncState() = step("cfsync")
        override fun eraseDatabase() = step("db")
        override fun eraseLeftoverState() = step("leftover")
        override fun eraseHubSession() = step("hub")
        override fun seedKeyAbsent() = !seedKeyStillThere
    }

    private val allSteps = listOf("seed", "sync", "bloom", "watched", "outgoing", "cfsync", "db", "leftover", "hub")

    private fun walletManager(
        ctx: android.content.Context = mockk(relaxed = true),
        eraser: WalletDataEraser,
        ksm: KeyStoreManager = mockk(relaxed = true),
        um: UtxoManager = mockk(relaxed = true),
        quiesce: () -> Unit = { /* no-op: keep the native lib out of this JVM test */ },
        identitySessions: IdentitySessionWipe = IdentitySessionWipe { true },
    ) = WalletManager(
        context = ctx,
        keyStoreManager = ksm,
        utxoManager = um,
        dataEraser = eraser,
        quiesceNative = quiesce,
        identitySessions = identitySessions,
    )

    /** A PIN store as it stands after the tenth wrong PIN with wipe-after-N on. */
    private fun trippedPinStore() = RecordingPinStore().apply {
        map["pin_hash"] = "00"
        map["pin_salt"] = "00"
        map["pin_method"] = "pbkdf2"
        map["pin_fail_count"] = PinManager.WIPE_THRESHOLD
        map["pin_wipe_after_n"] = true
        map["pin_wipe_pending"] = true
    }

    // ── the routine itself ───────────────────────────────────────────────────

    @Test fun wipeClearsSeedDbWatchedOutgoingFilterHeadersSeedFirst() = runTest {
        val eraser = RecordingEraser()
        val ksm = mockk<KeyStoreManager>(relaxed = true)
        val um = mockk<UtxoManager>(relaxed = true)
        // Relaxed context: WalletManager's init/field setup touches getSharedPreferences
        // only; no native. AndroidWalletDataEraser is NOT constructed (we inject the fake).
        val wm = walletManager(eraser = eraser, ksm = ksm, um = um)

        val report = wm.wipeWallet()

        // Seed ciphertext cleared FIRST (crash-safety invariant).
        assertEquals("seed", eraser.calls.first())
        // Every privacy-sensitive + regenerable store is destroyed.
        assertTrue("missing erase steps: ${eraser.calls}", eraser.calls.containsAll(allSteps))
        // UTXO cache + seed Keystore key destroyed.
        coVerify(exactly = 1) { um.clearAll() }
        verify(exactly = 1) { ksm.deleteKey() }
        // State reset to no-wallet.
        assertTrue(wm.walletState.value is WalletState.NoWallet)
        assertTrue("a clean wipe must report itself verified: $report", report.verified)
    }

    @Test fun `a step that reports failure makes the wipe report failure`() = runTest {
        for (step in allSteps) {
            val report = walletManager(eraser = RecordingEraser(refuses = setOf(step))).wipeWallet()
            assertFalse("step '$step' reported failure but the wipe reported itself verified", report.verified)
        }
    }

    @Test fun `a step that throws does not stop the steps after it`() = runTest {
        val eraser = RecordingEraser(throws = setOf("sync"))
        val ksm = mockk<KeyStoreManager>(relaxed = true)

        val report = walletManager(eraser = eraser, ksm = ksm).wipeWallet()

        assertTrue("steps after the throwing one were skipped: ${eraser.calls}", eraser.calls.containsAll(allSteps))
        verify(exactly = 1) { ksm.deleteKey() }
        assertFalse(report.verified)
    }

    @Test fun `a throwing quiesce does not abort the erasure`() = runTest {
        val eraser = RecordingEraser()
        val ksm = mockk<KeyStoreManager>(relaxed = true)
        val wm = walletManager(eraser = eraser, ksm = ksm, quiesce = { throw IllegalStateException("native layer unavailable") })

        val report = wm.wipeWallet()

        assertTrue("the erasure did not run: ${eraser.calls}", eraser.calls.containsAll(allSteps))
        verify(exactly = 1) { ksm.deleteKey() }
        assertFalse("a wipe whose native session could not be stopped is not a verified wipe", report.verified)
    }

    @Test fun `seed gone is a read-back, not the erase step's own result`() = runTest {
        // Every step says "done", but the seed store still holds a record when it is read back.
        val prefs = InMemoryPrefsFactory()
        prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed", "c1")
        val wm = walletManager(ctx = wipeTestContext(prefs, tmp.newFolder()), eraser = RecordingEraser())

        val report = wm.wipeWallet()

        assertFalse("the seed record is still readable, yet the wipe said the seed is gone", report.seedGone)
        assertFalse(report.verified)
        assertTrue("a wallet that is still on disk must not read as no-wallet", wm.walletState.value is WalletState.Locked)
    }

    @Test fun `a seed key alias that is still present means the seed is not gone`() = runTest {
        val report = walletManager(eraser = RecordingEraser(seedKeyStillThere = true)).wipeWallet()

        assertFalse(report.seedGone)
        assertFalse(report.verified)
    }

    @Test fun `the seed key goes only once the record's removal is confirmed`() = runTest {
        // The record holds the seed; the key only unwraps it. Until the record's removal is
        // confirmed, the key stays — a wallet whose record is still on the device must still be
        // the wallet its PIN opened before the wipe ran, so a wipe that could not finish can be
        // run again and the owner is never shut out of a wallet that is still there.
        for (eraser in listOf(RecordingEraser(refuses = setOf("seed")), RecordingEraser(throws = setOf("seed")))) {
            val ksm = mockk<KeyStoreManager>(relaxed = true)
            walletManager(eraser = eraser, ksm = ksm).wipeWallet()
            verify(exactly = 0) { ksm.deleteKey() }
        }

        // The write reported success but the record reads back as still there: same rule.
        val prefs = InMemoryPrefsFactory()
        prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed", "c1")
        val stillThere = mockk<KeyStoreManager>(relaxed = true)
        walletManager(ctx = wipeTestContext(prefs, tmp.newFolder()), eraser = RecordingEraser(), ksm = stillThere)
            .wipeWallet()
        verify(exactly = 0) { stillThere.deleteKey() }

        // Confirmed gone: the key goes too, so nothing that could unwrap a record left by a
        // wipe cut short stays behind.
        val confirmed = mockk<KeyStoreManager>(relaxed = true)
        val report = walletManager(eraser = RecordingEraser(), ksm = confirmed).wipeWallet()
        verify(exactly = 1) { confirmed.deleteKey() }
        assertTrue(report.seedGone)
    }

    @Test fun `the wipe ends the identity sessions and reports their result`() = runTest {
        var calls = 0
        val ok = walletManager(eraser = RecordingEraser(), identitySessions = { calls++; true }).wipeWallet()
        assertEquals("the identity sessions must be ended exactly once per wipe", 1, calls)
        assertTrue(ok.verified)

        val refused = walletManager(eraser = RecordingEraser(), identitySessions = { false }).wipeWallet()
        assertFalse("identity sessions that could not be ended leave the wipe unverified", refused.verified)

        val threw = walletManager(eraser = RecordingEraser(), identitySessions = { throw IllegalStateException() }).wipeWallet()
        assertFalse(threw.verified)
    }

    // ── the sequence every entry point runs ──────────────────────────────────

    @Test fun `a verified wipe releases the owed-wipe flag and then clears the PIN store`() = runTest {
        val store = trippedPinStore()
        val pin = PinManager(store)

        val verified = walletManager(eraser = RecordingEraser()).wipeThenReleasePin(pin)

        assertTrue(verified)
        assertFalse(pin.hasPin())
        assertFalse(pin.isWipePending())
        assertEquals(
            "the owed-wipe flag is released by its own write, before the store is cleared",
            listOf("remove:pin_wipe_pending", "clear"), store.writes,
        )
    }

    @Test fun `a throwing quiesce leaves the PIN and its counters in place`() = runTest {
        val store = trippedPinStore()
        val pin = PinManager(store)
        val wm = walletManager(eraser = RecordingEraser(), quiesce = { throw IllegalStateException("native layer unavailable") })

        val verified = wm.wipeThenReleasePin(pin)

        assertFalse(verified)
        assertTrue("the PIN was cleared after an unverified wipe", pin.hasPin())
        assertEquals(PinManager.WIPE_THRESHOLD, store.map["pin_fail_count"])
        assertFalse("no write may clear the PIN store", "clear" in store.writes)
    }

    @Test fun `while the seed is not verifiably gone the PIN, the counters and the owed-wipe flag all stay`() = runTest {
        for (eraser in listOf(
            RecordingEraser(refuses = setOf("seed")),
            RecordingEraser(throws = setOf("seed")),
            RecordingEraser(seedKeyStillThere = true),
        )) {
            val store = trippedPinStore()
            val pin = PinManager(store)

            val verified = walletManager(eraser = eraser).wipeThenReleasePin(pin)

            assertFalse(verified)
            assertTrue(pin.hasPin())
            assertTrue("the owed wipe must still be owed at the next launch", pin.isWipePending())
            assertEquals(PinManager.WIPE_THRESHOLD, store.map["pin_fail_count"])
            assertEquals("nothing may be written to the PIN store", emptyList<String>(), store.writes)
        }
    }

    @Test fun `the owed-wipe flag never outlives the seed it was owed for`() = runTest {
        // The seed is verifiably gone but another store refused the write: the wipe is not
        // verified, so the PIN and its counters stay — but a wallet created from here on must
        // not inherit a wipe that was owed to its predecessor.
        val store = trippedPinStore()
        val pin = PinManager(store)

        val verified = walletManager(eraser = RecordingEraser(refuses = setOf("db"))).wipeThenReleasePin(pin)

        assertFalse(verified)
        assertTrue(pin.hasPin())
        assertEquals(PinManager.WIPE_THRESHOLD, store.map["pin_fail_count"])
        assertFalse(pin.isWipePending())
        assertEquals(listOf("remove:pin_wipe_pending"), store.writes)
    }

    // ── the real eraser, over in-memory preferences and a scratch directory ──

    private val networks = listOf("", "_testnet")

    private class FakeKeystore(vararg present: String) : KeystoreAliases {
        val aliases = present.toMutableSet()
        override fun contains(alias: String) = alias in aliases
        override fun delete(alias: String) { aliases.remove(alias) }
    }

    private inner class Device {
        val prefs = InMemoryPrefsFactory()
        val root: File = tmp.newFolder()
        val ctx = wipeTestContext(prefs, root)
        val keystore = FakeKeystore(
            KeyStoreManager.KEY_ALIAS, KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX, "dgb_db_passphrase",
        )
        val hubLegacy get() = prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME)
        val hubSecure get() = prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME)
        val eraser = AndroidWalletDataEraser(ctx, keystore) { HubTokenStore(hubLegacy, { hubSecure }) }
        val ksm = mockk<KeyStoreManager>(relaxed = true).also {
            every { it.deleteKey() } answers {
                keystore.delete(KeyStoreManager.KEY_ALIAS)
                keystore.delete(KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX)
            }
        }

        /** Preference stores a wallet leaves behind, every one on both networks where suffixed. */
        val suffixedStores = listOf(
            "dgb_sync_data", "dgb_bloom_peers", "dgb_filter_peers", "dgb_dandelion_peers",
            "dgb_reconcile", "dgb_cf_abandonment",
        )
        val sharedStores = listOf("dgb_watched_addrs", "dgb_outgoing_tx", "dgb_asset_backfill", "dgb_asset_heal", "dgb_db_key")

        fun files(): List<File> = networks.flatMap { net ->
            listOf(
                File(ctx.filesDir, "saved_filter_headers$net.bin"),
                File(ctx.filesDir, "saved_cf_ledger$net.bin"),
                File(ctx.filesDir, "saved_blocks$net.bin"),
            ) + listOf("", "-journal", "-shm", "-wal").map { ctx.getDatabasePath("wallet$net.db$it") }
        }

        fun populate() {
            prefs.prefsFor("dgb_wallet_seed").apply { seed("encrypted_seed_v2", "c1"); seed("iv_v2", "c2") }
            networks.forEach { net -> suffixedStores.forEach { prefs.prefsFor(it + net).seed("k", "v") } }
            sharedStores.forEach { prefs.prefsFor(it).seed("k", "v") }
            prefs.prefsFor("dgb_settings").apply {
                seed("cf_birth_height", 21_000_000L)
                seed("app_language", "de")
                seed("dgb_network_testnet", false)
            }
            hubLegacy.seed(HubTokenStore.KEY_JWT, "plaintext-token")
            hubSecure.seed(HubTokenStore.KEY_JWT, "token")
            files().forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
        }

        fun walletManager() = walletManager(ctx = ctx, eraser = eraser, ksm = ksm)
    }

    @Test fun `one wipe clears both networks' stores, the leftover state and the hub token`() = runTest {
        val d = Device().apply { populate() }
        // Sanity: the selected network is mainnet, so every `_testnet` store is the OTHER network's.
        assertFalse(isTestnet(d.ctx))

        val report = d.walletManager().wipeWallet()

        val survivors = buildList {
            d.files().filter { it.exists() }.forEach { add("file ${it.name}") }
            (networks.flatMap { net -> d.suffixedStores.map { it + net } } + d.sharedStores + "dgb_wallet_seed")
                .filter { d.prefs.prefsFor(it).all.isNotEmpty() }
                .forEach { add("prefs $it") }
            if (d.prefs.prefsFor("dgb_settings").contains("cf_birth_height")) add("the scan floor (cf_birth_height)")
            if (d.hubLegacy.contains(HubTokenStore.KEY_JWT)) add("the plaintext hub token")
            if (d.hubSecure.contains(HubTokenStore.KEY_JWT)) add("the hub token")
            d.keystore.aliases.forEach { add("keystore alias $it") }
        }
        assertEquals("left behind by a wipe:\n" + survivors.joinToString("\n"), emptyList<String>(), survivors)
        assertTrue("a wipe that left nothing behind must report itself verified: $report", report.verified)

        // The settings store is NOT wallet state: the language and the network selection stay.
        assertEquals("de", d.prefs.prefsFor("dgb_settings").getString("app_language", null))
        assertTrue(d.prefs.prefsFor("dgb_settings").contains("dgb_network_testnet"))
    }

    @Test fun `a failed commit makes the wipe report failure`() = runTest {
        val everyStore = Device().run {
            networks.flatMap { net -> suffixedStores.map { it + net } } + sharedStores +
                listOf("dgb_wallet_seed", "dgb_settings", HubTokenStore.LEGACY_PREFS_NAME, HubTokenStore.SECURE_PREFS_NAME)
        }
        for (store in everyStore) {
            val d = Device().apply { populate(); prefs.refuseWritesTo(store) }

            val report = d.walletManager().wipeWallet()

            assertFalse("'$store' refused the write but the wipe reported itself verified", report.verified)
        }
    }

    @Test fun `a seed store that refuses the write is not a seed that is gone`() = runTest {
        // The in-memory view of a preferences file shows an edit even when the write did not land,
        // so the read-back alone would say "gone" about a record a restart brings back.
        val d = Device().apply { populate(); prefs.refuseWritesTo("dgb_wallet_seed") }
        val store = trippedPinStore()
        val pin = PinManager(store)

        val verified = d.walletManager().wipeThenReleasePin(pin)

        assertFalse(verified)
        assertTrue("the durable copy still holds the seed", d.prefs.prefsFor("dgb_wallet_seed").durable.containsKey("encrypted_seed_v2"))
        assertTrue(pin.hasPin())
        assertTrue(pin.isWipePending())
        // The wallet the PIN opened before this run still opens: the key that unwraps the record
        // the wipe could not remove is still there, so the wipe can be run again and succeed.
        assertEquals(
            "a record that is still on the device must still have the key that opens it",
            setOf(KeyStoreManager.KEY_ALIAS, KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX),
            d.keystore.aliases.filter { it.startsWith(KeyStoreManager.KEY_ALIAS) }.toSet(),
        )
    }

    @Test fun `an owed wipe that will not complete still has a way out`() = runTest {
        // A store that permanently refuses its write would otherwise make every retry report the
        // same thing for ever, on a screen that takes no credential while a wipe is owed. The
        // wallet is still on the device here, so the way out is the PIN that has always opened it:
        // the owed wipe stays owed and the screen stands down (it is the unlock screen that counts
        // the attempts, this decides what the bound means).
        val stillHere = Device().apply { populate(); prefs.refuseWritesTo("dgb_wallet_seed") }
        val heldStore = trippedPinStore()
        val held = PinManager(heldStore)

        assertFalse(stillHere.walletManager().wipeThenReleasePin(held))
        assertFalse(
            "a wallet that is still on the device is not released, it is opened",
            stillHere.walletManager().releaseOwedWipeIfNoWalletIsLeft(held),
        )
        assertTrue("the wipe is still owed", held.isWipePending())
        assertTrue(held.hasPin())
        assertTrue(
            "the record is still there, whatever this process's view of the store says",
            stillHere.prefs.prefsFor("dgb_wallet_seed").durable.containsKey("encrypted_seed_v2"),
        )

        // And with no record left there is nothing for the owed wipe to hold a screen for: the flag
        // goes, so onboarding can be offered and a wallet made from here on does not inherit it.
        val nothingLeft = Device()
        val store = trippedPinStore()
        val pin = PinManager(store)

        assertTrue(nothingLeft.walletManager().releaseOwedWipeIfNoWalletIsLeft(pin))
        assertFalse(pin.isWipePending())
        assertTrue("the way out must not clear the PIN store", pin.hasPin())
        assertEquals(listOf("remove:pin_wipe_pending"), store.writes)
    }

    @Test fun `a file that cannot be deleted makes the wipe report failure`() = runTest {
        val d = Device().apply { populate() }
        // A non-empty directory where the file should be: File.delete() refuses it.
        val stubborn = CfScanLedgerStore.file(d.ctx)
        stubborn.delete()
        File(stubborn, "child").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

        val report = d.walletManager().wipeWallet()

        assertFalse(report.verified)
        assertTrue("the rest of the wipe must still have run", report.seedGone)
    }

    /**
     * The order test above proves only that the interface method was CALLED — it would
     * stay green if that method deleted nothing. This drives the real
     * [AndroidWalletDataEraser] against a temp filesDir and asserts the files are gone.
     *
     * The scan ledger matters specifically: it records which heights already had a
     * cfilter evaluated. Surviving a wipe, it tells the NEXT wallet that this wallet's
     * scanned heights are done, so the new wallet's transactions in those blocks are
     * never looked for — a silent, permanent fund-visibility hole. The saved-blocks
     * window (I2 fix — moved to a file alongside the other two) matters the same way:
     * left behind, the next wallet would restore its header chain from a DIFFERENT
     * wallet's chain position. All three files are asserted so none can regress alone.
     */
    @Test fun eraseCfSyncStateDeletesTheHeaderChainScanLedgerAndSavedBlocks() {
        val d = Device()
        val ctx = d.ctx

        val headerChain = FilterHeaderStore.file(ctx)
        val scanLedger = CfScanLedgerStore.file(ctx)
        val savedBlocks = SavedBlockStore.file(ctx)
        headerChain.writeBytes(byteArrayOf(1, 2, 3))
        scanLedger.writeBytes(byteArrayOf(4, 5, 6))
        savedBlocks.writeBytes(byteArrayOf(7, 8, 9))
        d.prefs.prefsFor("dgb_cf_abandonment").apply { seed("band_low", 5L); seed("band_high", 9L) }
        assertTrue("precondition: an abandoned band is recorded", CfAbandonmentStore.band(ctx) != null)
        assertTrue("precondition: header chain exists", headerChain.exists())
        assertTrue("precondition: scan ledger exists", scanLedger.exists())
        assertTrue("precondition: saved blocks exists", savedBlocks.exists())

        assertTrue(d.eraser.eraseCfSyncState())

        assertTrue("filter-header chain survived the wipe", !headerChain.exists())
        assertTrue("CF scan ledger survived the wipe", !scanLedger.exists())
        assertTrue("saved-blocks window survived the wipe", !savedBlocks.exists())
        assertTrue("the abandoned-band record survived the wipe", CfAbandonmentStore.band(ctx) == null)
    }
}
