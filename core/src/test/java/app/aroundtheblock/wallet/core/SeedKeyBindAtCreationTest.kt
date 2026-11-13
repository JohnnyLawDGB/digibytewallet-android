package app.aroundtheblock.wallet.core

import android.util.Log
import app.aroundtheblock.wallet.core.security.KeystoreKeyInvalidatedException
import app.aroundtheblock.wallet.core.security.KeystoreUserAuthRequiredException
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import app.aroundtheblock.wallet.core.security.SeedKeyStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * KS-BIND. Invariant: when the device has a secure lock screen at wallet creation or restore, the
 * seed is wrapped under the auth-bound key FROM CREATION, not only after a later unlock; when it has
 * none, the record says so (status NO_LOCK_SCREEN) and nothing prompts. Existing wallets are
 * unchanged: the readiness probe writes nothing to a stored record.
 *
 * Each test drives what the record-writing step does, through the SAME function the app calls
 * ([prepareSeedKeyThenPrompt]): ask [WalletManager.prepareSeedKeyForNewRecord]; on
 * NEEDS_DEVICE_CREDENTIAL run the one prompt (here: [Harness.deviceCredentialPrompt], which opens the
 * fake auth window); then create or recover.
 *
 * The status line is checked against the KEY, not only the record: a bound record whose key no
 * longer opens is LOCK_REMOVED even after a new lock is set.
 */
class SeedKeyBindAtCreationTest {

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

    private inner class Harness(secure: Boolean = true, windowOpen: Boolean = true) {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val seedStore = RecordingPrefs(prefs.prefsFor("dgb_wallet_seed"))
        val root: File = tmp.newFolder()
        val ksm = keyStoreManagerOver(vault, authBound = secure)
        /** The auth-bound key's window: closed means every bound op throws, as the Keystore does. */
        var authWindowOpen = windowOpen
        var prompts = 0
        /** The auth window lapses while the native create/restore runs (a slow derivation at 299 s). */
        var closeWindowDuringNative = false

        init {
            vault.failure = { op, alias, _ ->
                if (alias == BOUND_ALIAS && (op == "encrypt" || op == "decrypt") && !authWindowOpen) {
                    KeystoreUserAuthRequiredException(RuntimeException("window lapsed"))
                } else null
            }
        }

        /** The device-credential prompt succeeding: a device auth event opens the window. */
        fun deviceCredentialPrompt() { prompts++; authWindowOpen = true }

        /** The record-writing step's preamble: the shipped [prepareSeedKeyThenPrompt], with a fake prompt. */
        fun prepareAsTheStepDoes(wm: WalletManager, withActivity: Boolean = true): SeedKeyReadiness = runBlocking {
            prepareSeedKeyThenPrompt(
                prepare = { wm.prepareSeedKeyForNewRecord() },
                prompt = if (withActivity) ({ deviceCredentialPrompt() }) else null,
            )
        }

        val walletManager = WalletManager(
            context = keyTestContext(prefs, root, seedStore),
            keyStoreManager = ksm,
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            createNative = { _, _ -> if (closeWindowDuringNative) authWindowOpen = false; true },
            recoverNative = { _, _, _ -> if (closeWindowDuringNative) authWindowOpen = false; true },
            seedNative = { m, p -> MessageDigest.getInstance("SHA-512").apply { update(m); p?.let { update(it) } }.digest() },
            rescanNative = { },
            birthHeightNative = { 21_500_000L },
            releaseDatabaseKey = { true },
        )

        val held get() = seedStore.inner.durable
        fun recordIsBound() = "encrypted_seed_v2" in held && "encrypted_seed" !in held
        fun recordIsLegacy() = "encrypted_seed" in held && "encrypted_seed_v2" !in held
    }

    @Test fun `KS-BIND create inside the auth window is bound from creation with no prompt`() {
        val h = Harness(windowOpen = true)
        assertEquals(SeedKeyReadiness.READY, h.prepareAsTheStepDoes(h.walletManager))
        assertTrue(h.walletManager.createWallet(phrase))
        assertEquals("an open window must not prompt", 0, h.prompts)
        assertTrue("the new record is not under the auth-bound key", h.recordIsBound())
        assertEquals(SeedKeyStatus.BOUND, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND create after the auth window lapsed prompts once and is bound from creation`() {
        val h = Harness(windowOpen = false)
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, h.prepareAsTheStepDoes(h.walletManager))
        assertTrue(h.walletManager.createWallet(phrase))
        assertEquals("exactly one device-credential prompt", 1, h.prompts)
        assertTrue("a lapsed window left the new record on the legacy key until a later unlock", h.recordIsBound())
        assertEquals(SeedKeyStatus.BOUND, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND restore after the auth window lapsed prompts once and is bound from creation`() {
        val h = Harness(windowOpen = false)
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, h.prepareAsTheStepDoes(h.walletManager))
        assertTrue(h.walletManager.recoverWallet(phrase, 1_600_000_000L, null))
        assertEquals(1, h.prompts)
        assertTrue("a lapsed window left the restored record on the legacy key until a later unlock", h.recordIsBound())
    }

    @Test fun `KS-BIND a declined prompt never blocks onboarding - the record lands on the legacy key`() {
        val h = Harness(windowOpen = false)
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, h.walletManager.prepareSeedKeyForNewRecord())
        // No prompt success: the window stays shut.
        assertTrue("a declined prompt must not stop the wallet being created", h.walletManager.createWallet(phrase))
        assertTrue(h.recordIsLegacy())
        assertEquals("the later unlock still moves it", SeedKeyStatus.PENDING, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND no secure lock screen - no prompt, no bound key, status says so`() {
        val h = Harness(secure = false)
        assertEquals(SeedKeyReadiness.NO_LOCK_SCREEN, h.prepareAsTheStepDoes(h.walletManager))
        assertFalse("keygen with auth required must not even be attempted (API 33 crash)", BOUND_ALIAS in h.vault.keys)
        assertTrue(h.walletManager.createWallet(phrase))
        assertEquals(0, h.prompts)
        assertTrue(h.recordIsLegacy())
        assertEquals(SeedKeyStatus.NO_LOCK_SCREEN, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND keygen refused on a secure device - no prompt, legacy fallback`() {
        val h = Harness()
        every { h.ksm.createAuthBoundKey() } returns false   // API 35 auth-state inconsistency / vendor quirk
        assertEquals(SeedKeyReadiness.UNAVAILABLE, h.prepareAsTheStepDoes(h.walletManager))
        assertEquals(0, h.prompts)
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue(h.recordIsLegacy())
    }

    @Test fun `KS-BIND an invalidated bound key - no prompt, legacy fallback, nothing deleted`() {
        val h = Harness()
        h.vault.failure = { op, alias, _ ->
            if (alias == BOUND_ALIAS && op == "encrypt") KeystoreKeyInvalidatedException(RuntimeException()) else null
        }
        assertEquals(SeedKeyReadiness.UNAVAILABLE, h.prepareAsTheStepDoes(h.walletManager))
        assertEquals(0, h.prompts)
        assertFalse("the probe deleted a key", h.vault.events.any { it.startsWith("delete:") })
    }

    @Test fun `KS-BIND an existing wallet is unchanged by the probe`() {
        // A wallet created on the legacy key (as every pre-KS-BIND wallet on a declined/insecure path was).
        val h = Harness(windowOpen = false)
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue("precondition: an existing legacy record", h.recordIsLegacy())
        val before = LinkedHashMap(h.held)
        val writesBefore = h.seedStore.writes.size
        h.authWindowOpen = true

        assertEquals(SeedKeyReadiness.READY, h.walletManager.prepareSeedKeyForNewRecord())

        assertEquals("the probe wrote to the wallet record", writesBefore, h.seedStore.writes.size)
        assertEquals("the probe changed a stored record", before, LinkedHashMap(h.held))
        assertFalse("the probe decrypted the stored seed", h.vault.events.any { it == "decrypt:$LEGACY_ALIAS" })
        assertEquals(SeedKeyStatus.PENDING, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND no activity for the prompt - no prompt, legacy fallback`() {
        val h = Harness(windowOpen = false)
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, h.prepareAsTheStepDoes(h.walletManager, withActivity = false))
        assertEquals(0, h.prompts)
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue(h.recordIsLegacy())
    }

    @Test fun `KS-BIND a throwing probe answers UNAVAILABLE and never prompts`() {
        val h = Harness(windowOpen = false)
        val r = runBlocking { prepareSeedKeyThenPrompt(prepare = { error("probe blew up") }, prompt = { h.deviceCredentialPrompt() }) }
        assertEquals(SeedKeyReadiness.UNAVAILABLE, r)
        assertEquals(0, h.prompts)
    }

    @Test fun `KS-BIND bound record with the window lapsed is still BOUND`() {
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase))
        h.authWindowOpen = false
        assertEquals("a lapsed window does not mean the key is gone", SeedKeyStatus.BOUND, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND lock removed then set again - the deleted key reads LOCK_REMOVED, not BOUND`() {
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue(h.recordIsBound())
        // API 31+: removing the device lock deletes the auth-bound key; the new lock is secure again.
        h.vault.keys.remove(BOUND_ALIAS)
        assertEquals(SeedKeyStatus.LOCK_REMOVED, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND lock removed then set again - the invalidated key reads LOCK_REMOVED, not BOUND`() {
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase))
        // API 26-30: the key stays but every init throws permanently-invalidated.
        h.vault.failure = { _, alias, _ ->
            if (alias == BOUND_ALIAS) KeystoreKeyInvalidatedException(RuntimeException("invalidated")) else null
        }
        assertEquals(SeedKeyStatus.LOCK_REMOVED, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND a Keystore that cannot be asked is not reported as a removed lock`() {
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase))
        every { h.ksm.authBoundKeyExists() } returns null
        assertEquals("no evidence either way: neither LOCK_REMOVED nor BOUND", SeedKeyStatus.UNCHECKED, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND a key that throws an unmapped error is not reported BOUND`() {
        // A vendor Keystore raising a generic error for a key that no longer opens (not
        // KeyPermanentlyInvalidated, no matching message): the status must not claim the lock ties it.
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue(h.recordIsBound())
        h.vault.failure = { op, alias, _ ->
            if (alias == BOUND_ALIAS && op == "encrypt") java.security.ProviderException("vendor keystore") else null
        }
        assertEquals(SeedKeyStatus.UNCHECKED, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND a create that outlasts the auth window is still sealed bound`() {
        // The probe answers READY with the window about to lapse, and the native create (seed
        // derivation) runs past its end. The seal must already have happened by then.
        val h = Harness(windowOpen = true)
        h.closeWindowDuringNative = true
        assertEquals(SeedKeyReadiness.READY, h.prepareAsTheStepDoes(h.walletManager))
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue("the window lapsed during the native create and the record fell back to the legacy key", h.recordIsBound())
    }

    @Test fun `KS-BIND a restore that outlasts the auth window is still sealed bound`() {
        val h = Harness(windowOpen = true)
        h.closeWindowDuringNative = true
        assertEquals(SeedKeyReadiness.READY, h.prepareAsTheStepDoes(h.walletManager))
        assertTrue(h.walletManager.recoverWallet(phrase, 1_600_000_000L, null))
        assertTrue("the window lapsed during the native restore and the record fell back to the legacy key", h.recordIsBound())
    }

    @Test fun `KS-BIND legacy record beside an invalidated leftover key is CANNOT_BIND, not PENDING`() {
        val h = Harness(windowOpen = false)
        assertTrue(h.walletManager.createWallet(phrase))   // lapsed window, no prompt: legacy
        assertTrue(h.recordIsLegacy())
        assertTrue("precondition: the bound key exists", BOUND_ALIAS in h.vault.keys)
        h.vault.failure = { _, alias, _ ->
            if (alias == BOUND_ALIAS) KeystoreKeyInvalidatedException(RuntimeException("invalidated")) else null
        }
        assertEquals("PENDING promises a move that can never happen", SeedKeyStatus.CANNOT_BIND, h.walletManager.seedKeyStatus())
    }

    @Test fun `KS-BIND a prompt whose answer never comes back does not hold the write forever`() = kotlinx.coroutines.test.runTest {
        // Its activity recreated while it was up, the prompt's callback may never fire.
        val r = prepareSeedKeyThenPrompt(
            prepare = { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL },
            prompt = { kotlinx.coroutines.awaitCancellation() },
        )
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, r)
        assertTrue("the wait was not bounded", testScheduler.currentTime >= SEED_KEY_PROMPT_TIMEOUT_MS)
    }

    @Test fun `KS-BIND a prompt that throws does not stop the write`() {
        val r = runBlocking {
            prepareSeedKeyThenPrompt(prepare = { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }, prompt = { error("prompt blew up") })
        }
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, r)
    }

    @Test fun `KS-BIND no wallet stored - status is NO_WALLET`() {
        assertEquals(SeedKeyStatus.NO_WALLET, Harness().walletManager.seedKeyStatus())
    }

    // ── KS-BIND: the unlock path after the device lock was removed ───────────────────────────────
    //
    // Invariant: a record wrapped under the auth-bound key whose key is gone or permanently
    // invalidated, by any route, makes the unlock path throw KeystoreKeyInvalidatedException (the UI's
    // "lock removed: restore from your recovery phrase"), never the generic failure, never a loop, and
    // never a deleted record. A key that exists but cannot be used just now stays retryable.

    /** A bound wallet, then the real Keystore's behaviour on decrypt: getKey answers null for a deleted
     *  alias, and Cipher.init(null) throws InvalidKeyException — not the fake's own absent-key error. */
    private fun boundWalletThen(decrypt: (Harness) -> Throwable?): Harness {
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase))
        assertTrue("precondition: a bound record", h.recordIsBound())
        every { h.ksm.decryptAuthBound(any()) } answers {
            decrypt(h)?.let { throw it }
            h.vault.decrypt(BOUND_ALIAS, firstArg())
        }
        return h
    }

    private fun Harness.unlockOutcome(): Any = try {
        walletManager.restoreFromDisk()
    } catch (e: Exception) {
        e
    }

    private fun Harness.assertRecordIntact() {
        assertTrue("the record was deleted", recordIsBound())
        assertFalse("a key was deleted", vault.events.any { it.startsWith("delete:") })
    }

    @Test fun `KS-BIND unlock with the bound alias deleted (API 31+) says lock removed`() {
        val h = boundWalletThen { if (BOUND_ALIAS !in it.vault.keys) java.security.InvalidKeyException("No installed provider supports this key: (null)") else null }
        h.vault.keys.remove(BOUND_ALIAS)
        repeat(2) {
            val r = h.unlockOutcome()
            assertTrue("a deleted bound key gave $r, not the lock-removed outcome", r is KeystoreKeyInvalidatedException)
        }
        h.assertRecordIntact()
    }

    @Test fun `KS-BIND unlock with the bound key permanently invalidated (raw) says lock removed`() {
        val h = boundWalletThen { mockk<android.security.keystore.KeyPermanentlyInvalidatedException>(relaxed = true) }
        val r = h.unlockOutcome()
        assertTrue("a raw KeyPermanentlyInvalidatedException gave $r", r is KeystoreKeyInvalidatedException)
        h.assertRecordIntact()
    }

    @Test fun `KS-BIND unlock with an InvalidKeyException on a present key stays retryable`() {
        // The alias is still there, so this is not evidence the key is gone: a permanently invalidated
        // key raises KeyPermanentlyInvalidatedException (a subclass, mapped above), and a plain
        // InvalidKeyException on a present key may be a vendor hiccup. Sending the user to their
        // recovery phrase on a key that may open next time is the worse error.
        val h = boundWalletThen { java.security.InvalidKeyException("vendor") }
        assertEquals(false, h.unlockOutcome())
        h.assertRecordIntact()
    }

    @Test fun `KS-BIND unlock with a transient Keystore error stays retryable`() {
        val h = boundWalletThen { KeystoreHiccup() }
        every { h.ksm.authBoundKeyExists() } returns null   // the Keystore cannot be asked either
        assertEquals(false, h.unlockOutcome())
        h.assertRecordIntact()
    }
    // ── loadBip39Seed: the passphrase envelope's typed key errors reach the caller ──────────────────
    //
    // Invariant (integration review item 4): a bound passphrase envelope that needs the device lock, or
    // whose key is gone, makes loadBip39Seed throw the typed exception, so Recover Funds prompts or says
    // "restore from your recovery phrase" -- never a silent null read as "unavailable".

    /** A bound passphrase wallet whose seed decrypts and whose passphrase envelope then throws [e]. */
    private fun boundPassphraseWalletThen(e: () -> Throwable): Harness {
        val h = Harness(windowOpen = true)
        h.prepareAsTheStepDoes(h.walletManager)
        assertTrue(h.walletManager.createWallet(phrase, "extra words".toByteArray()))
        assertTrue("precondition: a bound record", h.recordIsBound())
        var calls = 0
        every { h.ksm.decryptAuthBound(any()) } answers {
            if (++calls > 1) throw e()
            h.vault.decrypt(BOUND_ALIAS, firstArg())
        }
        return h
    }

    @Test fun `loadBip39Seed rethrows a passphrase envelope that needs the device lock`() {
        val h = boundPassphraseWalletThen { KeystoreUserAuthRequiredException(RuntimeException("window lapsed")) }
        val r = runCatching { h.walletManager.loadBip39Seed() }
        assertTrue("got ${r.getOrNull() ?: r.exceptionOrNull()}", r.exceptionOrNull() is KeystoreUserAuthRequiredException)
    }

    @Test fun `loadBip39Seed rethrows a passphrase envelope whose key is gone`() {
        val h = boundPassphraseWalletThen { KeystoreKeyInvalidatedException(RuntimeException("gone")) }
        val r = runCatching { h.walletManager.loadBip39Seed() }
        assertTrue("got ${r.getOrNull() ?: r.exceptionOrNull()}", r.exceptionOrNull() is KeystoreKeyInvalidatedException)
    }
}
