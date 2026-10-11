package app.aroundtheblock.wallet.core

import android.util.Log
import app.aroundtheblock.wallet.core.db.DatabaseKeyProvisioner
import app.aroundtheblock.wallet.core.db.DatabaseKeyState
import app.aroundtheblock.wallet.core.security.EncryptedData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The seed's key migration and the database password (PER-001, NEW-B03).
 *
 * The seed moves from the legacy Keystore key onto an auth-bound one, and the legacy key is then
 * deleted. The database password's record was wrapped under that same legacy key, so the
 * invariants are:
 *  - the database password is re-wrapped under its own key, read back and verified, before any key
 *    is deleted; an injected failure at any step leaves the old key in place;
 *  - a record that already carries the dedicated key (the beta cohort) is left alone;
 *  - a new install's database password is wrapped under its own key, with its alias, checked.
 *
 * Pure JVM: the Keystore is [FakeVault] (real AES-GCM, so a deleted key really loses what it
 * wrapped), the stores are in memory, and the migration runs through the real [WalletManager] over
 * the real [DatabaseKeyProvisioner].
 */
class DatabaseKeyMigrationTest {

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

    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .toByteArray(Charsets.UTF_8)
    private val dbPassword = ByteArray(32) { (it * 7 + 3).toByte() }

    /** An install from before this change: seed and database password both under the legacy key. */
    private inner class LegacyInstall(passphrase: ByteArray? = null) {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val seedStore = prefs.prefsFor("dgb_wallet_seed")
        val dbRecords = prefs.prefsFor(DatabaseKeyProvisioner.RECORDS)
        val provisioner = DatabaseKeyProvisioner(dbRecords, vault)
        val ksm = keyStoreManagerOver(vault)
        val dbRecordCiphertext: ByteArray
        val passCiphertext: ByteArray?

        init {
            vault.createUnboundKey(LEGACY_ALIAS)
            val seed = vault.encrypt(LEGACY_ALIAS, mnemonic)
            seedStore.seed("encrypted_seed", hex(seed.ciphertext))
            seedStore.seed("encrypted_seed_iv", hex(seed.iv))
            seedStore.seed("wallet_creation_time", 1_767_225_600L)
            passCiphertext = passphrase?.let { p ->
                val enc = vault.encrypt(LEGACY_ALIAS, p)
                seedStore.seed("encrypted_pass", hex(enc.ciphertext))
                seedStore.seed("encrypted_pass_iv", hex(enc.iv))
                enc.ciphertext
            }
            val wrapped = vault.encrypt(LEGACY_ALIAS, dbPassword)
            dbRecordCiphertext = wrapped.ciphertext
            dbRecords.seed("encrypted_key", "${hex(wrapped.ciphertext)}:${hex(wrapped.iv)}")
            vault.events.clear()
        }

        fun walletManager() = WalletManager(
            context = keyTestContext(prefs, tmp.newFolder()),
            keyStoreManager = ksm,
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            releaseDatabaseKey = { provisioner.releaseLegacyWrap() },
        )

        /** The seed's key migration runs on the legacy decrypt path, as at an unlock. */
        fun migrateSeed() {
            val decrypted = walletManager().decryptStoredMnemonic()
            assertNotNull("precondition: the stored seed decrypts", decrypted)
        }

        /** What the next process reads: the record on disk, through a fresh provisioner. */
        fun databasePasswordOnNextStart(): DatabaseKeyState =
            DatabaseKeyProvisioner(reopened(dbRecords), vault).resolve()
    }

    @Test fun `the database password is re-wrapped under its own key, read back and verified, before any key is deleted`() {
        val install = LegacyInstall()
        install.migrateSeed()

        assertTrue("precondition: the seed moved onto the auth-bound key", install.seedStore.contains("encrypted_seed_v2"))
        val state = install.databasePasswordOnNextStart()
        assertTrue(
            "the database password can no longer be read once the seed's key migration has run " +
                "(next start sees $state): the key that wrapped it was deleted with the seed's legacy key",
            state is DatabaseKeyState.Stored && state.password.contentEquals(dbPassword),
        )
        assertEquals("the record does not name its own key", DB_ALIAS, install.dbRecords.durable["db_key_alias"])

        val events = install.vault.events
        val deletedAt = events.indexOf("delete:$LEGACY_ALIAS")
        assertTrue("precondition: the seed migration completed and deleted the legacy key", deletedAt >= 0)
        val rewrappedAt = events.indexOf("encrypt:$DB_ALIAS")
        // The last read of the record under its own key before the delete (the test's own reads come later).
        val verifiedAt = events.subList(0, deletedAt).lastIndexOf("decrypt:$DB_ALIAS")
        assertTrue("the database password was not re-wrapped under its own key before the legacy key was deleted",
            rewrappedAt in 0 until deletedAt)
        assertTrue("the re-wrapped record was not read back and verified before the legacy key was deleted",
            verifiedAt in (rewrappedAt + 1) until deletedAt)
    }

    /** One step of the re-wrap, and how a fault there is injected. */
    private class Step(val name: String, val inject: (LegacyInstall) -> Unit)

    private val rewrapSteps = listOf(
        Step("read the record under the old key") { i ->
            i.vault.failure = { op, alias, data ->
                if (op == "decrypt" && alias == LEGACY_ALIAS && data.contentEquals(i.dbRecordCiphertext)) KeystoreHiccup() else null
            }
        },
        Step("make the record's own key") { i ->
            i.vault.failure = { op, alias, _ -> if (op == "create" && alias == DB_ALIAS) KeystoreHiccup() else null }
        },
        Step("wrap the password under its own key") { i ->
            i.vault.failure = { op, alias, _ -> if (op == "encrypt" && alias == DB_ALIAS) KeystoreHiccup() else null }
        },
        Step("write the record") { i -> i.prefs.refuseWritesTo(DatabaseKeyProvisioner.RECORDS) },
        Step("read the record back") { i ->
            i.vault.failure = { op, alias, _ -> if (op == "decrypt" && alias == DB_ALIAS) KeystoreHiccup() else null }
        },
        Step("verify what was read back") { i ->
            i.vault.tamper = { alias, plain -> if (alias == DB_ALIAS) plain.copyOf().also { it[0] = (it[0] + 1).toByte() } else plain }
        },
    )

    @Test fun `an injected failure at any step of the re-wrap leaves the old key in place`() {
        for (step in rewrapSteps) {
            val install = LegacyInstall()
            step.inject(install)
            install.migrateSeed()

            assertTrue("an injected failure at '${step.name}' deleted the old key", LEGACY_ALIAS in install.vault.keys)
            install.vault.failure = { _, _, _ -> null }
            install.vault.tamper = { _, plain -> plain }
            val state = install.databasePasswordOnNextStart()
            assertTrue(
                "after an injected failure at '${step.name}' the database password does not read back ($state)",
                state is DatabaseKeyState.Stored && state.password.contentEquals(dbPassword),
            )
        }
    }

    // GUARD: nothing touched the beta cohort's record before this change either; this keeps it so.
    @Test fun `a record that already carries the dedicated key (the beta cohort) is left alone`() {
        val install = LegacyInstall()
        install.vault.createUnboundKey(DB_ALIAS)
        val wrapped = install.vault.encrypt(DB_ALIAS, dbPassword)
        val record = "${hex(wrapped.ciphertext)}:${hex(wrapped.iv)}"
        install.dbRecords.seed("encrypted_key", record)
        install.dbRecords.seed("db_key_alias", DB_ALIAS)
        val before = LinkedHashMap(install.dbRecords.durable)
        install.vault.events.clear()

        install.migrateSeed()

        assertEquals("the beta cohort's record was rewritten", before, install.dbRecords.durable)
        assertFalse("the beta cohort's key was made again", "create:$DB_ALIAS" in install.vault.events)
        assertFalse("the beta cohort's password was wrapped again", "encrypt:$DB_ALIAS" in install.vault.events)
        assertTrue("precondition: the seed migration completed", "delete:$LEGACY_ALIAS" in install.vault.events)
        val state = install.databasePasswordOnNextStart()
        assertTrue("the beta cohort's database password does not read back ($state)",
            state is DatabaseKeyState.Stored && state.password.contentEquals(dbPassword) && state.dedicated)
    }

    /**
     * The passphrase envelope moves with the seed. One that cannot be read at that moment must stay
     * where it is, with the key that reads it: dropping it turns the wallet into a different one.
     */
    @Test fun `an injected failure reading the passphrase envelope leaves the old key and the envelope in place`() {
        val passphrase = Bip39Passphrase.prepare("correct horse")!!
        val install = LegacyInstall(passphrase)
        install.vault.failure = { op, alias, data ->
            if (op == "decrypt" && alias == LEGACY_ALIAS && data.contentEquals(install.passCiphertext)) KeystoreHiccup() else null
        }
        install.migrateSeed()

        val held = install.seedStore.durable
        assertTrue("the stored passphrase was dropped by the seed's key migration",
            held.containsKey("encrypted_pass") || held.containsKey("encrypted_pass_v2"))
        assertTrue("an unreadable passphrase envelope did not stop the old key being deleted", LEGACY_ALIAS in install.vault.keys)

        install.vault.failure = { _, _, _ -> null }
        val ct = held["encrypted_pass"] as? String
        val iv = held["encrypted_pass_iv"] as? String
        assertNotNull("the legacy passphrase envelope is gone", ct)
        val back = install.vault.decrypt(LEGACY_ALIAS, EncryptedData(unhex(ct!!), unhex(iv!!)))
        assertArrayEquals("the passphrase no longer reads back", passphrase, back)
    }

    @Test fun `a new install's database password is wrapped under its own key, with its alias, checked`() {
        val vault = FakeVault()
        val records = InMemoryPrefs()
        val password = DatabaseKeyProvisioner(records, vault).provisionNew()

        assertNotNull(password)
        assertEquals("a new install's record does not name its own key", DB_ALIAS, records.durable["db_key_alias"])
        assertFalse("a new install's database password was wrapped under the seed's legacy key", LEGACY_ALIAS in vault.keys)
        val state = DatabaseKeyProvisioner(reopened(records), vault).resolve()
        assertTrue("a new install's database password does not read back ($state)",
            state is DatabaseKeyState.Stored && state.dedicated && state.password.contentEquals(password))

        val refused = InMemoryPrefs().apply { refuseWrites = true }
        assertEquals("a new database password whose record did not land was handed out", null,
            DatabaseKeyProvisioner(refused, FakeVault()).provisionNew())
    }

    private fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
