package app.aroundtheblock.wallet.core

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * A record whose own identity contradicts a "no passphrase" reading (AND-005).
 *
 * The seed's key migration in an earlier release could drop a passphrase envelope it failed to read,
 * leaving a record — seed, creation time, the identity of the wallet it was made for — that reads as
 * "no passphrase". Opened that way it is a different, empty wallet. Its identity (`seed_fingerprint_v2`,
 * of the seed derived WITH the passphrase) says otherwise. Invariant: when the record's identity
 * contradicts a no-passphrase reading, the record is Unknown — the wallet is not opened, no recovery key
 * is derived, the phrase screen is not told "none" — and nothing is overwritten: not the identity, not
 * the sync state.
 */
class SeedRecordIdentityTest {

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

    private class ReachedNative : RuntimeException("the restore reached its native work")

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val passphrase = Bip39Passphrase.prepare("correct horse")!!

    private fun derive(m: ByteArray, p: ByteArray?): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { update(m); p?.let { update(it) } }.digest()

    private inner class Harness {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val seedStore = prefs.prefsFor("dgb_wallet_seed")
        val syncStore = prefs.prefsFor("dgb_sync_data")
        val root: File = tmp.newFolder()

        /** Seed under the auth-bound key, a creation time, no passphrase envelope, and the identity of [identityOf]. */
        fun record(identityOf: ByteArray?) {
            vault.createUnboundKey(BOUND_ALIAS)
            val enc = vault.encrypt(BOUND_ALIAS, phrase.toByteArray())
            seedStore.seed("encrypted_seed_v2", hex(enc.ciphertext))
            seedStore.seed("encrypted_seed_iv_v2", hex(enc.iv))
            seedStore.seed("wallet_creation_time", 1_767_225_600L)
            seedStore.seed("seed_fingerprint_v2", SeedFingerprint.v2(derive(phrase.toByteArray(), identityOf)))
            syncStore.seed("saved_blocks", "beef")
        }

        fun walletManager() = WalletManager(
            context = keyTestContext(prefs, root),
            keyStoreManager = keyStoreManagerOver(vault),
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            seedNative = { m, p -> derive(m, p) },
            releaseDatabaseKey = { true },
        )
    }

    @Test fun `a record whose identity contradicts a no-passphrase reading is not opened, and nothing is overwritten`() {
        val h = Harness()
        h.record(identityOf = passphrase) // the wallet was made with a passphrase; its envelope is gone
        val identity = h.seedStore.durable["seed_fingerprint_v2"]

        val opened = try {
            h.walletManager().restoreFromDisk(beforeNative = { throw ReachedNative() })
        } catch (e: ReachedNative) {
            fail("the restore went on to rebuild, with no passphrase, a wallet its record's identity says had one")
            return
        }

        assertFalse("a record that contradicts its own identity was opened", opened)
        assertEquals("the record's identity was overwritten", identity, h.seedStore.durable["seed_fingerprint_v2"])
        assertEquals("the sync state was cleared for a record that was not opened", "beef", h.syncStore.durable["saved_blocks"])
    }

    @Test fun `no recovery key is derived from a record whose identity contradicts a no-passphrase reading`() {
        val h = Harness()
        h.record(identityOf = passphrase)

        assertNull("a recovery key was derived with no passphrase for a wallet that has one", h.walletManager().loadBip39Seed())
    }

    @Test fun `the phrase screen is not told there is no passphrase when the record's identity says otherwise`() {
        val h = Harness()
        h.record(identityOf = passphrase)

        try {
            val read = h.walletManager().decryptStoredPassphrase()
            fail("the record was read as 'no passphrase' (${read?.size}) against its own identity")
        } catch (e: IllegalStateException) {
            // an error, never "none"
        }
    }

    // GUARD: a record whose identity matches its no-passphrase reading opens as before.
    @Test fun `a record whose identity matches a no-passphrase reading opens as before`() {
        val h = Harness()
        h.record(identityOf = null)

        assertNotNull(h.walletManager().loadBip39Seed())
        assertNull(h.walletManager().decryptStoredPassphrase())
        try {
            h.walletManager().restoreFromDisk(beforeNative = { throw ReachedNative() })
            fail("a complete record did not reach the restore's native work")
        } catch (e: ReachedNative) {
            assertTrue(true)
        }
    }
}
