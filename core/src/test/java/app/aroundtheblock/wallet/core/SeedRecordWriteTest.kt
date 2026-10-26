package app.aroundtheblock.wallet.core

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * The seed record (AND-005): the seed, its passphrase, its creation time and its identity are one
 * record, and the record is one checked write with a version marker.
 *
 *  - Creating or recovering a wallet writes the whole record with ONE checked commit, carrying a
 *    version marker and whether the wallet has a passphrase.
 *  - A failed write reports failure and leaves "wallet exists" false — in this process and on disk.
 *  - A seed with no version marker is an error, not "no passphrase", unless the record itself shows
 *    that its passphrase write landed (records written before this change, read as they always were).
 *
 * Pure JVM: native creation, seed derivation and rescan are injected; the Keystore is [FakeVault].
 */
class SeedRecordWriteTest {

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
    private val passphrase = Bip39Passphrase.prepare("correct horse")!!

    /** Every key that belongs to the seed record. */
    private val recordKeys = setOf(
        "encrypted_seed", "encrypted_seed_iv", "encrypted_seed_v2", "encrypted_seed_iv_v2",
        "encrypted_pass", "encrypted_pass_iv", "encrypted_pass_v2", "encrypted_pass_iv_v2",
        "wallet_creation_time", "seed_fingerprint_v2", "seed_record_version", "seed_has_passphrase",
        "wallet_origin",
    )

    private inner class Harness(authBound: Boolean = true) {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val seedStore = RecordingPrefs(prefs.prefsFor("dgb_wallet_seed"))
        val root: File = tmp.newFolder()
        val ksm = keyStoreManagerOver(vault, authBound)
        /** The passphrase each seed derivation was asked for (null: none). */
        val derivedWith = mutableListOf<ByteArray?>()

        fun walletManager(store: android.content.SharedPreferences = seedStore) = WalletManager(
            context = keyTestContext(prefs, root, store),
            keyStoreManager = ksm,
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            createNative = { _, _ -> true },
            recoverNative = { _, _, _ -> true },
            seedNative = { m, p ->
                derivedWith += p?.copyOf()
                MessageDigest.getInstance("SHA-512").apply { update(m); p?.let { update(it) } }.digest()
            },
            rescanNative = { },
            birthHeightNative = { 21_500_000L },
            releaseDatabaseKey = { true },
        )

        val recordWrites get() = seedStore.writes.filter { w -> w.keys.any { it in recordKeys } || "*clear*" in w.keys }
    }

    @Test fun `the seed record is one checked write with a version marker`() {
        for (authBound in listOf(true, false)) {
            val h = Harness(authBound)
            val created = h.walletManager().createWallet(phrase, passphrase)
            assertTrue("precondition: the wallet was created (authBound=$authBound)", created)

            val writes = h.recordWrites
            assertEquals("the seed record must be written by exactly one write (authBound=$authBound); was ${writes.map { it.keys }}",
                1, writes.size)
            val write = writes.single()
            assertTrue("the seed record was written unchecked (apply) — a write that did not land goes unnoticed", write.checked)
            val held = h.seedStore.inner.durable
            assertTrue("the record carries no version marker", held.containsKey("seed_record_version"))
            assertEquals("the record does not say the wallet has a passphrase", true, held["seed_has_passphrase"])
            assertTrue("the creation time is not part of the one record write", "wallet_creation_time" in write.keys)
            val passKeys = if (authBound) "encrypted_pass_v2" else "encrypted_pass"
            assertTrue("the passphrase is not part of the one record write", passKeys in write.keys)
        }
    }

    @Test fun `a failed write leaves wallet exists false`() {
        val h = Harness()
        h.prefs.refuseWritesTo("dgb_wallet_seed")
        val wm = h.walletManager()

        val created = wm.createWallet(phrase, passphrase)

        assertFalse("a seed record whose write did not land was reported as a created wallet", created)
        assertFalse("'wallet exists' is true over a record that never reached the disk", wm.hasSavedWallet())
        assertEquals("the wallet state says a wallet is open", WalletState.NoWallet, wm.walletState.value)
        assertTrue("part of the seed record is on disk", h.seedStore.inner.durable.keys.none { it in recordKeys })
        assertFalse("the next process finds a wallet", h.walletManager(reopened(h.seedStore.inner)).hasSavedWallet())
    }

    @Test fun `a failed write leaves wallet exists false on recovery too`() {
        val h = Harness()
        h.prefs.refuseWritesTo("dgb_wallet_seed")
        val wm = h.walletManager()

        val recovered = wm.recoverWallet(phrase, 1_600_000_000L, passphrase)

        assertFalse("a recovered seed record whose write did not land was reported as a recovered wallet", recovered)
        assertFalse("'wallet exists' is true over a recovered record that never reached the disk", wm.hasSavedWallet())
        assertEquals(WalletState.NoWallet, wm.walletState.value)
        assertTrue("part of the recovered seed record is on disk", h.seedStore.inner.durable.keys.none { it in recordKeys })
    }

    @Test fun `a recovered wallet's record is one checked write with a version marker`() {
        val h = Harness()
        assertTrue(h.walletManager().recoverWallet(phrase, 1_600_000_000L, null))
        val writes = h.recordWrites
        assertEquals("the recovered seed record must be written by exactly one write; was ${writes.map { it.keys }}", 1, writes.size)
        assertTrue("the recovered seed record was written unchecked (apply)", writes.single().checked)
        val held = h.seedStore.inner.durable
        assertTrue("the recovered record carries no version marker", held.containsKey("seed_record_version"))
        assertEquals("the recovered record does not say the wallet has no passphrase", false, held["seed_has_passphrase"])
        assertEquals(1_600_000_000L, held["wallet_creation_time"])
    }

    // 4.1.2 row 16: the record says how the wallet came to be here, in the same one write, so a
    // wallet created on this device is never offered the shared Digi-ID identity.
    @Test fun `a created wallet's record says it was created here and offers no shared identity`() {
        val h = Harness()
        val wm = h.walletManager()
        assertTrue(wm.createWallet(phrase, null))
        assertEquals("created", h.seedStore.inner.durable["wallet_origin"])
        assertTrue("the origin is not part of the one record write", "wallet_origin" in h.recordWrites.single().keys)
        assertFalse(wm.mayHaveDigiIdHistoryElsewhere())
        assertFalse("the next process forgets the origin", h.walletManager(reopened(h.seedStore.inner)).mayHaveDigiIdHistoryElsewhere())
    }

    @Test fun `a recovered wallet's record says it was restored`() {
        val h = Harness()
        val wm = h.walletManager()
        assertTrue(wm.recoverWallet(phrase, 1_600_000_000L, null))
        assertEquals("restored", h.seedStore.inner.durable["wallet_origin"])
        assertTrue(wm.mayHaveDigiIdHistoryElsewhere())
    }

    @Test fun `a record from before the origin field may have history elsewhere`() {
        val h = Harness()
        h.unversionedSeedOnly()
        assertTrue(h.walletManager().mayHaveDigiIdHistoryElsewhere())
    }

    // 4.1.2 row 25: a NUL never reaches the native derivation, whichever caller passes it.
    @Test fun `a passphrase with an embedded NUL creates and recovers nothing`() {
        val h = Harness()
        val nul = "abc\u0000evil".toByteArray(Charsets.UTF_8)
        assertFalse(h.walletManager().createWallet(phrase, nul))
        assertFalse(h.walletManager().recoverWallet(phrase, 1_600_000_000L, nul))
        assertTrue("the seed was derived with a truncated passphrase", h.derivedWith.isEmpty())
        assertFalse(h.walletManager().hasSavedWallet())
    }

    /** A record written before this change by a write that did not finish: the seed, nothing after it. */
    private fun Harness.unversionedSeedOnly() {
        val enc = vault.run { createUnboundKey(LEGACY_ALIAS); encrypt(LEGACY_ALIAS, phrase.toByteArray()) }
        seedStore.inner.seed("encrypted_seed", hex(enc.ciphertext))
        seedStore.inner.seed("encrypted_seed_iv", hex(enc.iv))
    }

    @Test fun `a seed with no version marker is an error, not no passphrase`() {
        val h = Harness(authBound = false)
        h.unversionedSeedOnly()
        val wm = h.walletManager()

        val seed = wm.loadBip39Seed()
        assertNull("a seed record with no version marker and nothing to show its passphrase write landed " +
            "was read as a wallet with no passphrase", seed)
        assertTrue("the seed was derived with no passphrase from a record that does not say", h.derivedWith.none { it == null })
        try {
            val pass = wm.decryptStoredPassphrase()
            fail("a seed record with no version marker reported 'no passphrase' (${pass?.size})")
        } catch (e: IllegalStateException) {
            // the record cannot say whether there is a passphrase: an error, not "none"
        }
    }

    // GUARD: records written before this change that show their passphrase write landed read as before.
    @Test fun `an unversioned record whose creation time shows its passphrase write landed has no passphrase`() {
        val h = Harness(authBound = false)
        h.unversionedSeedOnly()
        h.seedStore.inner.seed("wallet_creation_time", 1_767_225_600L)

        assertNotNull(h.walletManager().loadBip39Seed())
        assertEquals(listOf<ByteArray?>(null), h.derivedWith)
    }

    // GUARD: an unversioned record with a passphrase envelope reads that envelope.
    @Test fun `an unversioned record with a passphrase envelope reads the passphrase`() {
        val h = Harness(authBound = false)
        h.unversionedSeedOnly()
        val enc = h.vault.encrypt(LEGACY_ALIAS, passphrase)
        h.seedStore.inner.seed("encrypted_pass", hex(enc.ciphertext))
        h.seedStore.inner.seed("encrypted_pass_iv", hex(enc.iv))

        assertNotNull(h.walletManager().loadBip39Seed())
        assertArrayEquals(passphrase, h.derivedWith.single())
    }

    @Test fun `a versioned record that says it has a passphrase but cannot read it is an error`() {
        val h = Harness()
        assertTrue(h.walletManager().createWallet(phrase, passphrase))
        val created = h.derivedWith.size
        // Lose the passphrase envelope from a record that says there is one.
        h.seedStore.inner.durable.remove("encrypted_pass_v2")
        val wm = h.walletManager(reopened(h.seedStore.inner))
        assertNull("a record that says it has a passphrase, without one, opened as a wallet with none", wm.loadBip39Seed())
        assertEquals("a seed was derived from a record missing its passphrase", created, h.derivedWith.size)
    }

    /** A record written by this release, then changed: [edit] applied to what is on disk. */
    private fun Harness.versionedRecord(withPassphrase: Boolean, edit: (InMemoryPrefs) -> Unit): WalletManager {
        assertTrue(walletManager().createWallet(phrase, if (withPassphrase) passphrase else null))
        val onDisk = reopened(seedStore.inner)
        edit(onDisk)
        return walletManager(onDisk)
    }

    private fun assertRecordError(wm: WalletManager, case: String) {
        assertNull("$case: the wallet's recovery key was derived from a record that contradicts itself", wm.loadBip39Seed())
        try {
            wm.decryptStoredPassphrase()
            fail("$case: the phrase screen was given an answer")
        } catch (e: IllegalStateException) {
            // an error, never "none" and never a guess
        }
    }

    // GUARD (test gap): each way a versioned record can contradict itself reads as an error.
    @Test fun `a versioned record that says it has a passphrase and holds none is an error`() {
        val h = Harness()
        val wm = h.versionedRecord(withPassphrase = true) { it.edit().remove("encrypted_pass_v2").remove("encrypted_pass_iv_v2").commit() }
        assertRecordError(wm, "says one, holds none")
    }

    // GUARD (test gap).
    @Test fun `a versioned record that says it has no passphrase and holds one is an error`() {
        val h = Harness()
        val wm = h.versionedRecord(withPassphrase = false) {
            val enc = h.vault.encrypt(BOUND_ALIAS, passphrase)
            it.edit().putString("encrypted_pass_v2", hex(enc.ciphertext)).putString("encrypted_pass_iv_v2", hex(enc.iv)).commit()
        }
        assertRecordError(wm, "says none, holds one")
    }

    // GUARD (test gap).
    @Test fun `a versioned record without its passphrase flag is an error`() {
        val h = Harness()
        val wm = h.versionedRecord(withPassphrase = false) { it.edit().remove("seed_has_passphrase").commit() }
        assertRecordError(wm, "no flag")
    }

    // GUARD (test gap).
    @Test fun `a versioned record that says it has a passphrase and holds an empty one is an error`() {
        val h = Harness()
        val wm = h.versionedRecord(withPassphrase = true) {
            val empty = h.vault.encrypt(BOUND_ALIAS, ByteArray(0))
            it.edit().putString("encrypted_pass_v2", hex(empty.ciphertext)).putString("encrypted_pass_iv_v2", hex(empty.iv)).commit()
        }
        assertRecordError(wm, "says one, holds an empty one")
    }

    @Test fun `the passphrase a wallet was created with is the one its record reads back`() {
        val h = Harness()
        assertTrue(h.walletManager().createWallet(phrase, passphrase))
        h.derivedWith.clear()
        assertNotNull(h.walletManager(reopened(h.seedStore.inner)).loadBip39Seed())
        assertArrayEquals(passphrase, h.derivedWith.single())
    }
}
