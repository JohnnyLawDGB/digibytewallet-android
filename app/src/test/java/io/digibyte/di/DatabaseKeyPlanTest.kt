package io.digibyte.di

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import io.digibyte.ui.KotlinSourceGate
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which key the wallet database is opened with at start, and what happens to a database file that
 * no key on this device can open.
 *
 * A completed wipe removes the database key. A database file can still be there at the next start —
 * a session that was still running wrote it back after the key had gone — and no key on the device
 * opens it. Such a file is discarded and a new database made, so that start opens; the file held
 * only what the chain and the wallet rebuild. Only the file's own answer that it is not a database
 * under the legacy passphrase discards it: a file that answers anything else is kept and opened
 * with the legacy passphrase, as before. A file the legacy passphrase has opened once is not asked
 * again at later starts, until a new key is made.
 */
class DatabaseKeyPlanTest {

    private fun plan(
        hasStoredKey: Boolean,
        databaseExists: Boolean,
        legacy: AppModule.LegacyProbe,
        legacyConfirmed: Boolean = false,
    ): AppModule.DatabaseKey {
        var asked = 0
        val answer = AppModule.databaseKeyPlan(hasStoredKey, databaseExists, legacyConfirmed) { asked++; legacy }
        if (hasStoredKey || !databaseExists || legacyConfirmed) {
            assertEquals("the legacy passphrase is tried only on an unconfirmed file with no stored key", 0, asked)
        }
        return answer
    }

    /** An SQLite failure as the cipher library raises it: its class and its message. */
    private inline fun <reified T : SQLiteException> failure(message: String): T = mockk {
        every { this@mockk.message } returns message
        every { cause } returns null
    }

    @Test fun `a database file that no key on this device opens is discarded for a new one`() {
        assertEquals(
            AppModule.DatabaseKey.NEW_AFTER_DISCARD,
            plan(hasStoredKey = false, databaseExists = true, legacy = AppModule.LegacyProbe.NOT_A_DATABASE),
        )
    }

    @Test fun `only the file's own not-a-database answer counts as not opening`() {
        // As the cipher library reported it on a device: a plain SQLiteException, code 26.
        val notADatabase = failure<SQLiteException>(
            "file is not a database (code 26): , while compiling: SELECT COUNT(*) FROM sqlite_schema;",
        )
        assertEquals(AppModule.LegacyProbe.NOT_A_DATABASE, AppModule.legacyProbeAnswer(notADatabase))

        // A failure that says nothing about the file's key keeps the file.
        val undecided = listOf(
            failure<SQLiteDatabaseLockedException>("database is locked (code 5 SQLITE_BUSY)"),
            failure<SQLiteDiskIOException>("disk I/O error (code 10)"),
            failure<SQLiteFullException>("database or disk is full (code 13)"),
            failure<SQLiteCantOpenDatabaseException>("unable to open database file (code 14)"),
            failure<SQLiteException>("code 260 not the same as 26"),
        )
        for (f in undecided) {
            assertEquals("'${f.message}' discarded the file", AppModule.LegacyProbe.UNDECIDED, AppModule.legacyProbeAnswer(f))
        }
        assertEquals(AppModule.LegacyProbe.OPENS, AppModule.legacyProbeAnswer(null))

        // Wrapped by whoever asked, the file's answer is still its answer.
        val wrapped = IllegalStateException("open failed", notADatabase)
        assertEquals(AppModule.LegacyProbe.NOT_A_DATABASE, AppModule.legacyProbeAnswer(wrapped))
    }

    @Test fun `a file whose probe says nothing about its key is kept and opened with the legacy passphrase`() {
        assertEquals(
            AppModule.DatabaseKey.LEGACY_UNCONFIRMED,
            plan(hasStoredKey = false, databaseExists = true, legacy = AppModule.LegacyProbe.UNDECIDED),
        )
    }

    @Test fun `a file the legacy passphrase has opened is not asked again at later starts`() {
        assertEquals(
            AppModule.DatabaseKey.LEGACY,
            plan(hasStoredKey = false, databaseExists = true, legacy = AppModule.LegacyProbe.NOT_A_DATABASE, legacyConfirmed = true),
        )
    }

    // GUARD (these held before and must keep holding).
    @Test fun `a stored key opens the database whatever else is on disk`() {
        for (exists in listOf(true, false)) {
            for (confirmed in listOf(true, false)) {
                assertEquals(
                    AppModule.DatabaseKey.STORED,
                    plan(hasStoredKey = true, databaseExists = exists, legacy = AppModule.LegacyProbe.NOT_A_DATABASE, legacyConfirmed = confirmed),
                )
            }
        }
    }

    // GUARD.
    @Test fun `a file the legacy passphrase opens keeps it`() {
        assertEquals(AppModule.DatabaseKey.LEGACY, plan(hasStoredKey = false, databaseExists = true, legacy = AppModule.LegacyProbe.OPENS))
    }

    // GUARD.
    @Test fun `no file and no key is a new database`() {
        assertEquals(AppModule.DatabaseKey.NEW, plan(hasStoredKey = false, databaseExists = false, legacy = AppModule.LegacyProbe.NOT_A_DATABASE))
    }

    private val module by lazy { KotlinSourceGate.of(File("src/main/java/io/digibyte/di/AppModule.kt").readText()) }

    @Test fun `the provider discards the file before it makes the new key`() {
        val plans = module.calls("databaseKeyPlan")
        assertEquals("the database provider does not decide through databaseKeyPlan", 1, plans.size)
        val discard = module.branchesOn("key == DatabaseKey.NEW_AFTER_DISCARD")
        assertEquals("the provider has no branch for a file no key opens", 1, discard.size)
        val discards = module.calls("discardUnopenableDatabase", discard.single())
        assertEquals("the unopenable file is not discarded in that branch", 1, discards.size)
        val newKey = module.calls("newDatabaseKey")
        assertEquals("scanner is blind: the provider makes no new key", 1, newKey.size)
        assertTrue(
            "the unopenable file must be gone before a new key is made for the database",
            discards.single().range.first < newKey.single().range.first,
        )
    }

    @Test fun `the probe hands every failure it catches to legacyProbeAnswer`() {
        val declared = module.code.indexOf("fun probeLegacyPassphrase")
        assertTrue("scanner is blind: no probeLegacyPassphrase", declared >= 0)
        val body = module.blockAt(module.code.indexOf('{', declared)) ?: error("unbalanced probeLegacyPassphrase")
        assertEquals("the probe does not classify what it caught", 1, module.calls("legacyProbeAnswer", body).size)
        assertTrue(
            "the probe names an answer itself instead of reading the failure",
            !module.code.substring(body).contains("LegacyProbe.NOT_A_DATABASE"),
        )
    }

    /**
     * What the probe hands on is the failure the database itself raised, as it came: only an SQLite
     * failure is caught there, and nothing else is turned into one — so an unrelated failure never
     * reads as the file's own answer, and never gets a file discarded.
     */
    @Test fun `the probe catches only the database's own failure and hands it on as it came`() {
        val declared = module.code.indexOf("fun probeLegacyPassphrase")
        assertTrue("scanner is blind: no probeLegacyPassphrase", declared >= 0)
        val body = module.blockAt(module.code.indexOf('{', declared)) ?: error("unbalanced probeLegacyPassphrase")
        val code = module.code.substring(body)
        val catches = Regex("""catch\s*\(\s*(\w+)\s*:\s*([\w.]+)\s*\)\s*\{""").findAll(code).toList()
        assertEquals("the probe does not catch exactly one kind of failure", 1, catches.size)
        val (name, type) = catches.single().destructured
        assertEquals("the probe catches more than the database's own failure", "android.database.sqlite.SQLiteException", type)
        val handled = module.blockAt(body.first + catches.single().range.last) ?: error("unbalanced catch")
        assertEquals("the probe hands on something other than the failure it caught", "{ $name }", KotlinSourceGate.squeeze(module.code.substring(handled)))
        assertTrue("the probe makes a failure of its own", !Regex("""\bSQLiteException\s*\(""").containsMatchIn(code))
    }

    @Test fun `a legacy file is confirmed once and forgotten when a new key is made`() {
        val written = module.written
        val confirm = Regex("""putBoolean\(\s*LEGACY_KEY_CONFIRMED\s*,\s*true\s*\)""").findAll(module.code).toList()
        assertEquals("the provider does not record that the legacy passphrase opened the file", 1, confirm.size)
        val recordBranch = module.branchesOn("key == DatabaseKey.LEGACY && !legacyConfirmed")
        assertEquals("the record is not made only on a legacy open not yet recorded", 1, recordBranch.size)
        assertTrue("the record is made outside that branch", confirm.single().range.first in recordBranch.single())
        val newKey = module.code.substringAfter("private fun newDatabaseKey", "")
        assertTrue("scanner is blind: no newDatabaseKey", newKey.isNotEmpty())
        assertTrue(
            "a new key leaves the legacy record behind",
            Regex("""\.remove\(\s*LEGACY_KEY_CONFIRMED\s*\)""").containsMatchIn(newKey.substringBefore("\n    private fun ")),
        )
        assertTrue("scanner is blind: the record's name is not a constant", written.contains("LEGACY_KEY_CONFIRMED ="))
    }
}
