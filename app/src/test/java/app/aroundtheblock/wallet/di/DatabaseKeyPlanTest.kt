package app.aroundtheblock.wallet.di

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the wallet database's key is decided (PER-001, AND-003).
 *
 * The decisions moved to `core` (`DatabaseKeyProvisioner`, `WalletDatabaseOpener`), where the JVM
 * tests (`DatabaseOpenPlanTest`, `DatabaseKeyMigrationTest`) and the phone tests can reach them. The
 * cases this file used to check there are ported, two of them inverted: a database file that no key
 * on this device opens is now set aside, never discarded. What stays here is a gate on the app's two
 * database providers:
 *  - they hand the network's file name to core, which opens the database;
 *  - nothing in them wipes, deletes or discards a database or a key, and no failure is turned into
 *    a wipe by a catch — a database that cannot be opened is renamed aside by core, never deleted;
 *  - the key record and the legacy literal password are handled in core only.
 */
class DatabaseKeyPlanTest {

    private val module by lazy { KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/di/AppModule.kt").readText()) }

    /** The two providers: from the @Provides one to the first DAO provider after them. */
    private val providers: IntRange by lazy {
        val r = module.range("fun provideDatabase(", "fun provideTransactionDao(")
        assertNotNull("scanner is blind: the database providers were not found", r)
        r!!
    }

    @Test fun `the database providers hand the decision to core`() {
        assertEquals("the database provider does not open the wallet database through core",
            1, module.calls("openWalletDatabase", providers).size)
    }

    @Test fun `the database providers delete, wipe and discard nothing`() {
        val code = module.code.substring(providers)
        assertFalse("a database provider turns a failure into a wipe (catch)", Regex("""\bcatch\b""").containsMatchIn(code))
        for (wipe in listOf("StaleDataWiper", "wipeAll", "wipeDatabase", "discardUnopenableDatabase")) {
            assertFalse("a database provider reaches $wipe", code.contains(wipe))
        }
        assertFalse("the app's dependency module deletes a file", Regex("""\.delete\(\)""").containsMatchIn(module.code))
        assertFalse("the app's dependency module deletes a Keystore entry", module.code.contains("deleteEntry"))
    }

    @Test fun `the key record and the legacy password are not handled in the app module`() {
        for (literal in listOf("\"digibyte-wallet-db\"", "\"encrypted_key\"", "\"db_key_alias\"", "\"dgb_db_key\"")) {
            assertFalse("the app's dependency module still handles $literal", module.written.contains(literal))
        }
        assertTrue("scanner is blind: the module text is empty", module.written.contains("object AppModule"))
    }
}
