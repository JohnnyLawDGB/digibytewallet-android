package io.digibyte.core

import android.util.Log
import io.digibyte.core.digiscope.HubTokenStore
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
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
 * What a wipe leaves running in this process, and what the next wallet made in it finds.
 *
 * Established here, on the real [WalletManager] and the real [AndroidWalletDataEraser] over
 * in-memory preferences whose `commit()` behaves as the platform's does (the in-memory view takes
 * the edit first; a write that does not land returns false and leaves the file as it was):
 *  - a wipe stops every session this process runs for the wallet, then quiesces the native
 *    session, both before it erases a single store;
 *  - after a wipe that removed the seed, PIN setup creates the wallet from the words onboarding
 *    holds, and a wallet that is stored on this device is never created a second time (a verified
 *    wipe starts a fresh process first; one that removed the seed without verifying runs on here);
 *  - a store whose write did not land keeps reading, in this process, exactly what its file still
 *    holds — so a seed that is still on disk still loads, and the PIN still opens it.
 *
 * Pure JVM: the native quiesce is a fake handed to the real wipe; nothing here loads the native
 * library.
 */
class WalletSessionAfterWipeTest {

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

    private class FakeKeystore(vararg present: String) : KeystoreAliases {
        val aliases = present.toMutableSet()
        override fun contains(alias: String) = alias in aliases
        override fun delete(alias: String) { aliases.remove(alias) }
    }

    /** One device: its stores, its keystore, its native session, and the order things happened in. */
    private inner class Device {
        val journal = mutableListOf<String>()
        val prefs = InMemoryPrefsFactory()
        val ctx = wipeTestContext(prefs, tmp.newFolder())
        val keystore = FakeKeystore(
            KeyStoreManager.KEY_ALIAS, KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX, "dgb_db_passphrase",
        )
        private val real = AndroidWalletDataEraser(ctx, keystore) {
            HubTokenStore(prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME), { prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME) })
        }

        /** The real eraser, with each step written to [journal] as it starts. */
        val eraser = object : WalletDataEraser by real {
            override fun eraseSeedCiphertext(): Boolean { journal += "erase seed"; return real.eraseSeedCiphertext() }
            override fun eraseSyncData(): Boolean { journal += "erase sync"; return real.eraseSyncData() }
            override fun eraseDatabase(): Boolean { journal += "erase database"; return real.eraseDatabase() }
        }
        val ksm = mockk<KeyStoreManager>(relaxed = true).also {
            every { it.deleteKey() } answers {
                keystore.delete(KeyStoreManager.KEY_ALIAS)
                keystore.delete(KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX)
            }
        }

        val seedStore get() = prefs.prefsFor("dgb_wallet_seed")

        /** A wallet as a device holds it: its seed record and the per-wallet stores beside it. */
        fun holdsAWallet() = apply {
            seedStore.seed("encrypted_seed_v2", "c1")
            seedStore.seed("encrypted_seed_iv_v2", "c2")
            seedStore.seed("wallet_creation_time", 1_790_000_000L)
            prefs.prefsFor("dgb_sync_data").seed("saved_peers", "0a0b")
            prefs.prefsFor("dgb_watched_addrs").seed("addrs", mutableSetOf("dgb1q"))
        }

        fun walletManager(quiesce: () -> Unit = { journal += "quiesce" }) = WalletManager(
            context = ctx,
            keyStoreManager = ksm,
            utxoManager = mockk(relaxed = true),
            dataEraser = eraser,
            quiesceNative = quiesce,
        )
    }

    /** A PIN store as it stands after the tenth wrong PIN with wipe-after-N on. */
    private fun trippedPinStore() = RecordingPinStore().apply {
        map["pin_hash"] = "00"
        map["pin_salt"] = "00"
        map["pin_method"] = "pbkdf2"
        map["pin_fail_count"] = PinManager.WIPE_THRESHOLD
        map["pin_wipe_after_n"] = true
        map["pin_wipe_pending"] = true
    }

    // ── the next wallet made in this process ─────────────────────────────────────────────────────

    @Test fun `after a wipe that removed the seed PIN setup creates the wallet from the words it holds`() = runTest {
        // A verified wipe starts a fresh process before PIN setup is reached. A wipe that removed the
        // seed but could not clear another store is not verified and runs on in this process, where
        // the wiped wallet is still the one loaded in memory: the answer must come from what is stored.
        for (refused in listOf(null, "dgb_watched_addrs")) {
            val d = Device().holdsAWallet().apply { refused?.let { prefs.refuseWritesTo(it) } }
            val wm = d.walletManager()
            assertFalse("precondition: a stored wallet is not created again", wm.pinSetupCreatesWallet())

            val report = wm.wipeWallet()
            assertTrue("precondition: the seed is gone (refused=$refused)", report.seedGone)
            assertEquals("precondition: verified only when nothing refused", refused == null, report.verified)

            assertTrue(
                "after a wipe PIN setup skipped creating the wallet whose words the user just wrote down (refused=$refused)",
                wm.pinSetupCreatesWallet(),
            )
        }
    }

    @Test fun `a wallet stored on this device is never created a second time`() {
        // Restore stores the wallet BEFORE PIN setup; a recomposition finds the one PIN setup just
        // made. Either way a stored wallet is not created again, whatever this process has loaded.
        val wm = Device().holdsAWallet().walletManager()
        assertFalse("a stored wallet would be created again", wm.pinSetupCreatesWallet())
        // And with nothing stored, a wallet left loaded in memory is not a wallet on this device.
        val nothingStored = Device().walletManager()
        assertTrue(nothingStored.pinSetupCreatesWallet())
    }

    // ── what a wipe stops, and when ──────────────────────────────────────────────────────────────

    @Test fun `a wipe ends the running session before it erases anything`() = runTest {
        val d = Device().holdsAWallet()
        val wm = d.walletManager()
        wm.addSessionStop { d.journal += "session stopped" }

        wm.wipeWallet()

        val firstErase = d.journal.indexOfFirst { it.startsWith("erase") }
        assertTrue("nothing was erased: ${d.journal}", firstErase >= 0)
        for (step in listOf("session stopped", "quiesce")) {
            val at = d.journal.indexOf(step)
            assertTrue("'$step' never happened: ${d.journal}", at >= 0)
            assertTrue("'$step' came after the first erase: ${d.journal}", at < firstErase)
        }
        assertTrue(
            "the running session must stop before the native session is quiesced: ${d.journal}",
            d.journal.indexOf("session stopped") < d.journal.indexOf("quiesce"),
        )
    }

    @Test fun `a session that has ended is not stopped by a later wipe`() = runTest {
        val d = Device().holdsAWallet()
        val wm = d.walletManager()
        var stops = 0
        val session = WalletSessionStop { stops++ }
        wm.addSessionStop(session)
        wm.wipeWallet()
        assertEquals(1, stops)

        wm.removeSessionStop(session)
        wm.wipeWallet()
        assertEquals("an ended session was stopped again", 1, stops)
    }

    @Test fun `a session that cannot be stopped leaves the wipe unverified and the rest still runs`() = runTest {
        val d = Device().holdsAWallet()
        val wm = d.walletManager()
        var laterStopped = false
        wm.addSessionStop { throw IllegalStateException("service unavailable") }
        wm.addSessionStop { laterStopped = true }

        val report = wm.wipeWallet()

        assertTrue("a session after the one that threw was not stopped", laterStopped)
        assertTrue("the native session was not quiesced", "quiesce" in d.journal)
        assertTrue("the seed must still go", report.seedGone)
        assertFalse("a session that could not be stopped may still write: not a verified wipe", report.verified)
    }

    // ── a store whose write did not land ─────────────────────────────────────────────────────────

    @Test fun `a refused seed clear leaves this process reading the seed that is still on disk`() = runTest {
        val d = Device().holdsAWallet().apply { prefs.refuseWritesTo("dgb_wallet_seed") }
        val wm = d.walletManager()
        val pin = PinManager(trippedPinStore())

        val report = wm.wipeThenReleasePin(pin)
        assertFalse("a seed store that refused its write reported the seed gone", report.seedGone)
        assertFalse(report.verified)

        assertEquals(
            "this process's view of the seed store differs from the file it still has",
            d.seedStore.durable, d.seedStore.all,
        )
        assertTrue("the wallet whose seed is on disk no longer reads as stored", wm.hasSavedWallet())
        assertFalse("a stored wallet would be created over at PIN setup", wm.pinSetupCreatesWallet())
        assertTrue(pin.hasPin())
        assertTrue(pin.isWipePending())
    }

    @Test fun `the way out of an owed wipe finds the wallet that is still on the device`() = runTest {
        // The unlock screen asks this once its bounded hold is spent. A wallet that is still on the
        // device must then still be readable, so the PIN that stands the screen down can open it.
        val d = Device().holdsAWallet().apply { prefs.refuseWritesTo("dgb_wallet_seed") }
        val wm = d.walletManager()
        val pin = PinManager(trippedPinStore())

        val report = wm.wipeThenReleasePin(pin)
        assertFalse("a seed store that refused its write reported the seed gone", report.seedGone)
        assertFalse(report.verified)
        assertFalse(wm.releaseOwedWipeIfNoWalletIsLeft(pin))

        assertEquals(d.seedStore.durable, d.seedStore.all)
        assertTrue("the stood-down screen would find no wallet to open", wm.hasSavedWallet())
        assertTrue(pin.isWipePending())
    }

    @Test fun `a store that refuses its write keeps reading what it still holds`() = runTest {
        for (store in listOf("dgb_sync_data", "dgb_watched_addrs", "dgb_wallet_seed")) {
            val d = Device().holdsAWallet().apply { prefs.refuseWritesTo(store) }

            val report = d.walletManager().wipeWallet()

            assertFalse("'$store' refused its write, yet the wipe verified", report.verified)
            val view = d.prefs.prefsFor(store)
            assertEquals("'$store': this process reads something other than the file", view.durable, view.all)
            assertTrue("'$store': precondition, the store held something", view.durable.isNotEmpty())
        }
    }

    @Test fun `a settings store that refuses the scan-floor removal keeps reading what it holds`() = runTest {
        // The wipe removes ONE key from dgb_settings (the scan floor); the file also holds the
        // language and the network selection. A removal that did not land leaves all of it as it was.
        val d = Device().holdsAWallet().apply {
            val settings = prefs.prefsFor("dgb_settings")
            settings.seed("cf_birth_height", 23_000_000L)
            settings.seed("app_language", "de")
            prefs.refuseWritesTo("dgb_settings")
        }

        val report = d.walletManager().wipeWallet()

        assertFalse("dgb_settings refused its write, yet the wipe verified", report.verified)
        val view = d.prefs.prefsFor("dgb_settings")
        assertEquals("dgb_settings: this process reads something other than the file", view.durable, view.all)
        assertEquals("the scan floor the file still holds no longer reads", 23_000_000L, view.getLong("cf_birth_height", 0L))
    }
}
