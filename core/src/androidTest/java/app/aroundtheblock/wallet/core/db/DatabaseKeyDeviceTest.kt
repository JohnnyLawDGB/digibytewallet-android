package app.aroundtheblock.wallet.core.db

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aroundtheblock.wallet.core.db.entity.DigiIdHistoryEntity
import app.aroundtheblock.wallet.core.security.AndroidKeyVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * The database password on a real device (PER-001, AND-003): real SQLCipher files, the real Android
 * Keystore, the real provisioner and opener.
 *
 * Runs in the test package's own sandbox, on its own Keystore aliases, records and file names — it
 * never names the wallet's aliases or files, so it cannot touch a wallet on the device.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyDeviceTest {
    private companion object {
        const val TEST_DB_ALIAS = "e2_keys_test_db_key"
        const val TEST_LEGACY_ALIAS = "e2_keys_test_wallet_master"
        const val TEST_RECORDS = "e2_keys_test_db_records"
        const val TEST_DB = "e2-keys-test.db"
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbFile: File get() = context.getDatabasePath(TEST_DB)

    private fun records() = context.getSharedPreferences(TEST_RECORDS, Context.MODE_PRIVATE)

    /** A provisioner as a fresh process builds it: over what the record store holds on disk. */
    private fun keys() = DatabaseKeyProvisioner(records(), AndroidKeyVault, legacyWrapAlias = TEST_LEGACY_ALIAS, dedicatedAlias = TEST_DB_ALIAS)

    private fun scratch(): List<File> = dbFile.parentFile!!.listFiles()!!.filter { it.name.startsWith(TEST_DB) }

    private fun clean() {
        scratch().forEach { it.delete() } // this test's own scratch files only
        records().edit().clear().commit()
        AndroidKeyVault.delete(TEST_DB_ALIAS)
        AndroidKeyVault.delete(TEST_LEGACY_ALIAS)
    }

    @Before fun setUp() = clean()
    @After fun tearDown() = clean()

    private val rows = listOf("site-a.example", "site-b.example", "site-c.example")

    /** A database under [password] holding Digi-ID history — the data that cannot be rebuilt from the chain. */
    private fun databaseWithHistory(password: ByteArray, name: String = TEST_DB) {
        val db = WalletDatabase.create(context, password, name)
        runBlocking {
            rows.forEachIndexed { i, domain ->
                db.digiIdHistoryDao().insert(
                    DigiIdHistoryEntity(domain = domain, callbackUrl = "https://$domain/cb", address = "D$i", timestamp = 1_000L + i, success = true)
                )
            }
        }
        db.close()
    }

    private fun history(db: WalletDatabase): List<String> = runBlocking { db.digiIdHistoryDao().getHistory().first().map { it.domain }.sorted() }

    private fun store(alias: String, password: ByteArray, named: Boolean) {
        AndroidKeyVault.createUnboundKey(alias)
        val edit = records().edit().putString("encrypted_key", DatabaseKeyProvisioner.format(AndroidKeyVault.encrypt(alias, password)))
        if (named) edit.putString("db_key_alias", alias)
        assertTrue(edit.commit())
    }

    private fun random32() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test fun legacyPasswordDatabase_isRekeyed_rowsSurvive_oldPasswordNoLongerOpens_reopensAfterRestart() {
        val legacy = LEGACY_DB_PASSWORD.toByteArray()
        databaseWithHistory(legacy)

        val opened = DatabaseKeyProvisioner.openWalletDatabase(context, TEST_DB, keys())
        assertEquals("rows were lost opening a legacy-password database", rows, history(opened))
        opened.close()

        val cipher = WalletDatabase.cipherFiles(context, dbFile)
        assertEquals("the published legacy password still opens the database", OpenAnswer.NOT_A_DATABASE, cipher.probe(legacy))
        val state = keys().resolve()
        assertTrue("no wrapped password under its own key was stored after the re-key ($state)",
            state is DatabaseKeyState.Stored && state.dedicated)
        assertEquals("the stored password does not open the re-keyed database", OpenAnswer.OPENS,
            cipher.probe((state as DatabaseKeyState.Stored).password))

        val again = DatabaseKeyProvisioner.openWalletDatabase(context, TEST_DB, keys())
        assertEquals("rows were lost reopening the re-keyed database after a restart", rows, history(again))
        again.close()
        assertTrue("the re-keyed database was set aside", scratch().none { it.name.contains(".aside-") })
    }

    @Test fun recordUnderTheOldKey_survivesTheSeedMigration() {
        val password = random32()
        store(TEST_LEGACY_ALIAS, password, named = false)
        databaseWithHistory(password)

        // The seed's key migration, in WalletManager's order: release the record, then delete the key.
        assertTrue("the record could not be moved off the old key", keys().releaseLegacyWrap())
        AndroidKeyVault.delete(TEST_LEGACY_ALIAS)

        val opened = DatabaseKeyProvisioner.openWalletDatabase(context, TEST_DB, keys())
        assertEquals("the database's rows did not survive the seed's key migration", rows, history(opened))
        opened.close()
        assertTrue("the database was set aside", scratch().none { it.name.contains(".aside-") })
    }

    @Test fun databaseTheStoredPasswordDoesNotOpen_isSetAsideNeverDeleted_andANewOneOpens() {
        val original = random32()
        databaseWithHistory(original)
        store(TEST_DB_ALIAS, random32(), named = true)

        val opened = DatabaseKeyProvisioner.openWalletDatabase(context, TEST_DB, keys())
        val now = try {
            history(opened)
        } catch (e: Exception) {
            opened.close()
            fail("the first query failed on a database the stored password does not open: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        assertTrue("the new database is not empty", now.isEmpty())
        opened.close()

        val asides = scratch().filter { it.name.contains(".aside-") && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") && !it.name.endsWith("-journal") }
        assertEquals("the database the stored password does not open was not set aside", 1, asides.size)
        assertTrue("the aside does not say why: ${asides.single().name}", asides.single().name.contains(AsideReason.WRONG_PASSWORD.tag))
        val aside = WalletDatabase.create(context, original, asides.single().name)
        assertEquals("the set-aside database lost rows", rows, history(aside))
        aside.close()
    }

    @Test fun missingKey_setsTheDatabaseAsideNeverDeletes() {
        val password = random32()
        store(TEST_DB_ALIAS, password, named = true)
        databaseWithHistory(password)
        AndroidKeyVault.delete(TEST_DB_ALIAS)

        val opened = DatabaseKeyProvisioner.openWalletDatabase(context, TEST_DB, keys())
        assertTrue("the new database is not empty", history(opened).isEmpty())
        opened.close()

        val asides = scratch().filter { it.name.contains(".aside-") && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") && !it.name.endsWith("-journal") }
        assertEquals("a database whose key is missing was deleted, not set aside", 1, asides.size)
        assertTrue(asides.single().name.contains(AsideReason.KEY_MISSING.tag))
        val aside = WalletDatabase.create(context, password, asides.single().name)
        assertEquals("the set-aside database lost rows", rows, history(aside))
        aside.close()
        assertFalse("a new password was not stored", keys().resolve() !is DatabaseKeyState.Stored)
    }
}
