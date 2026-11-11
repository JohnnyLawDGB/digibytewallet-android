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
 * Each test drives what the record-writing step does: ask [WalletManager.prepareSeedKeyForNewRecord];
 * on NEEDS_DEVICE_CREDENTIAL show the one prompt (here: [Harness.deviceCredentialPrompt], which opens
 * the fake auth window); then create or recover.
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

        init {
            vault.failure = { op, alias, _ ->
                if (alias == BOUND_ALIAS && (op == "encrypt" || op == "decrypt") && !authWindowOpen) {
                    KeystoreUserAuthRequiredException(RuntimeException("window lapsed"))
                } else null
            }
        }

        /** The device-credential prompt succeeding: a device auth event opens the window. */
        fun deviceCredentialPrompt() { prompts++; authWindowOpen = true }

        /** The record-writing step's preamble, as PIN setup / the restore's start run it. */
        fun prepareAsTheStepDoes(wm: WalletManager): SeedKeyReadiness =
            wm.prepareSeedKeyForNewRecord().also { if (it == SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL) deviceCredentialPrompt() }

        val walletManager = WalletManager(
            context = keyTestContext(prefs, root, seedStore),
            keyStoreManager = ksm,
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            createNative = { _, _ -> true },
            recoverNative = { _, _, _ -> true },
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

    @Test fun `KS-BIND no wallet stored - status is NO_WALLET`() {
        assertEquals(SeedKeyStatus.NO_WALLET, Harness().walletManager.seedKeyStatus())
    }
}
