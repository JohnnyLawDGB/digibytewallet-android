package app.aroundtheblock.wallet.core.db

import android.database.sqlite.SQLiteException
import android.util.Log
import app.aroundtheblock.wallet.core.DB_ALIAS
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefs
import app.aroundtheblock.wallet.core.KeystoreHiccup
import app.aroundtheblock.wallet.core.LEGACY_ALIAS
import app.aroundtheblock.wallet.core.RecordingPrefs
import app.aroundtheblock.wallet.core.hex
import app.aroundtheblock.wallet.core.reopened
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
import java.io.File

/**
 * How a start opens the wallet database (PER-001, AND-003), decided in `core` by
 * [WalletDatabaseOpener] over seams: the Keystore ([FakeVault]), the key record (in memory), the
 * files (real files in a scratch directory) and the cipher ([FakeCipher], which knows which password
 * each file is keyed with). Invariants:
 *  - a database that cannot be opened is renamed aside, never deleted;
 *  - a missing key, a temporary Keystore error, a wrong password and a corrupt file are told apart;
 *  - a legacy-password database is re-keyed, and its wrapped password stored only after a verified
 *    reopen.
 */
class DatabaseOpenPlanTest {

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

    private val legacy = LEGACY_DB_PASSWORD.toByteArray()

    /** Knows which password the file is keyed with; answers probes and re-keys like the cipher would. */
    private class FakeCipher(private val file: File, private val log: MutableList<String>) : CipherFiles {
        var keyedWith: ByteArray? = null
        var corrupt = false
        var undecided = false
        var onRekey: ((from: ByteArray, to: ByteArray) -> RekeyAnswer)? = null

        override fun probe(password: ByteArray): OpenAnswer {
            log += "probe:${if (password.contentEquals(LEGACY_DB_PASSWORD.toByteArray())) "legacy" else "other"}"
            if (!file.exists() || undecided) return OpenAnswer.UNDECIDED
            if (keyedWith?.contentEquals(password) == true) return if (corrupt) OpenAnswer.CORRUPT else OpenAnswer.OPENS
            return OpenAnswer.NOT_A_DATABASE
        }

        override fun rekey(from: ByteArray, to: ByteArray): RekeyAnswer {
            log += "rekey"
            onRekey?.let { return it(from, to) }
            if (keyedWith?.contentEquals(from) != true) return RekeyAnswer.UNCHANGED
            keyedWith = to.copyOf()
            log += "reopened"
            return RekeyAnswer.REKEYED
        }
    }

    private inner class Fixture(records: InMemoryPrefs = InMemoryPrefs(), val vault: FakeVault = FakeVault()) {
        val log = vault.events
        val dir: File = tmp.newFolder()
        val name = "wallet.db"
        val records = RecordingPrefs(records, log)
        val keys = DatabaseKeyProvisioner(this.records, vault)
        private var tick = 1_000L
        val files = DatabaseFiles(dir, name, clock = { tick++ })
        val cipher = FakeCipher(File(dir, name), log)

        fun opener() = WalletDatabaseOpener(keys, files, cipher, pause = { })

        /** A database file (and its write-ahead log) keyed with [password]. */
        fun database(password: ByteArray?, content: String = "rows") {
            File(dir, name).writeText(content)
            File(dir, "$name-wal").writeText("wal-$content")
            cipher.keyedWith = password?.copyOf()
        }

        fun storedUnder(alias: String, password: ByteArray, named: Boolean = true) {
            vault.createUnboundKey(alias)
            val wrapped = vault.encrypt(alias, password)
            records.inner.seed(DatabaseKeyProvisioner.WRAPPED, "${hex(wrapped.ciphertext)}:${hex(wrapped.iv)}")
            if (named) records.inner.seed(DatabaseKeyProvisioner.ALIAS, alias)
        }

        fun asides(): List<File> = dir.listFiles()!!.filter { it.name.startsWith("$name.aside-") && !it.name.endsWith("-wal") }
        fun live(): File = File(dir, name)
    }

    private fun assertSetAside(f: Fixture, reason: AsideReason, case: String) {
        val asides = f.asides()
        assertFalse("$case: the database was deleted, not set aside", asides.isEmpty() && !f.live().exists())
        assertEquals("$case: the database was not set aside", 1, asides.size)
        val aside = asides.single()
        assertEquals("$case: what was set aside is not the database", "rows", aside.readText())
        assertTrue("$case: the aside name does not say why (${aside.name})", aside.name.contains(reason.tag))
        assertEquals("$case: the database's write-ahead log was not kept with it", "wal-rows", File(f.dir, aside.name + "-wal").readText())
        assertFalse("$case: something is still at the live name", f.live().exists())
    }

    @Test fun `a database that cannot be opened is renamed aside, never deleted`() {
        val cases = listOf<Triple<String, AsideReason, (Fixture) -> Unit>>(
            Triple("missing key", AsideReason.KEY_MISSING) { f ->
                f.storedUnder(DB_ALIAS, ByteArray(32) { 1 })
                f.vault.keys.remove(DB_ALIAS)
                f.database(ByteArray(32) { 1 })
            },
            Triple("unreadable record", AsideReason.RECORD_UNREADABLE) { f ->
                f.storedUnder(DB_ALIAS, ByteArray(32) { 2 })
                f.vault.keys.remove(DB_ALIAS)
                f.vault.createUnboundKey(DB_ALIAS) // a different key under the same alias: the tag fails
                f.database(ByteArray(32) { 2 })
            },
            Triple("wrong password", AsideReason.WRONG_PASSWORD) { f -> f.database(ByteArray(32) { 3 }) },
            Triple("corrupt file", AsideReason.CORRUPT) { f -> f.database(LEGACY_DB_PASSWORD.toByteArray()); f.cipher.corrupt = true },
        )
        for ((case, reason, arrange) in cases) {
            val f = Fixture()
            arrange(f)
            val plan = f.opener().plan()
            assertSetAside(f, reason, case)
            assertTrue("$case: the start does not open a database under a stored password ($plan)",
                plan is DatabaseOpenPlan.Open && plan.recoverOnFirstOpen && !plan.password.contentEquals(legacy))
            val state = DatabaseKeyProvisioner(reopened(f.records.inner), f.vault).resolve()
            assertTrue("$case: the new database's password is not stored under its own key ($state)",
                state is DatabaseKeyState.Stored && state.dedicated && state.password.contentEquals((plan as DatabaseOpenPlan.Open).password))
        }
    }

    @Test fun `a missing key, a temporary Keystore error, a wrong password and a corrupt file are told apart`() {
        fun resolved(arrange: (Fixture) -> Unit): DatabaseKeyState = Fixture().also(arrange).keys.resolve()

        assertEquals("a missing key", DatabaseKeyState.KeyMissing(DB_ALIAS),
            resolved { f -> f.storedUnder(DB_ALIAS, ByteArray(32)); f.vault.keys.remove(DB_ALIAS) })
        assertTrue("a Keystore that cannot be asked is not a temporary error",
            resolved { f -> f.storedUnder(DB_ALIAS, ByteArray(32)); f.vault.failure = { op, _, _ -> if (op == "contains") KeystoreHiccup() else null } }
                is DatabaseKeyState.KeystoreUnavailable)
        assertTrue("a decrypt the Keystore could not finish is not a temporary error",
            resolved { f -> f.storedUnder(DB_ALIAS, ByteArray(32)); f.vault.failure = { op, _, _ -> if (op == "decrypt") KeystoreHiccup() else null } }
                is DatabaseKeyState.KeystoreUnavailable)
        assertEquals("a record its key rejects", DatabaseKeyState.RecordUnreadable(DB_ALIAS),
            resolved { f -> f.storedUnder(DB_ALIAS, ByteArray(32)); f.vault.keys.remove(DB_ALIAS); f.vault.createUnboundKey(DB_ALIAS) })
        assertEquals("a record that is not a record", DatabaseKeyState.RecordUnreadable(DB_ALIAS),
            resolved { f -> f.records.inner.seed(DatabaseKeyProvisioner.WRAPPED, "not-a-record"); f.records.inner.seed(DatabaseKeyProvisioner.ALIAS, DB_ALIAS); f.vault.createUnboundKey(DB_ALIAS) })

        // A temporary Keystore error acts on nothing: no file moved, no record or key changed.
        val f = Fixture()
        f.storedUnder(DB_ALIAS, ByteArray(32) { 4 })
        f.database(ByteArray(32) { 4 })
        val recordBefore = LinkedHashMap(f.records.inner.durable)
        f.vault.failure = { op, _, _ -> if (op == "decrypt") KeystoreHiccup() else null }
        val plan = f.opener().plan()
        assertTrue("a temporary Keystore error did not leave the database alone for this run ($plan)", plan is DatabaseOpenPlan.Temporary)
        assertEquals("a temporary Keystore error moved the database", "rows", f.live().readText())
        assertTrue("a temporary Keystore error set the database aside", f.asides().isEmpty())
        assertEquals("a temporary Keystore error changed the key record", recordBefore, f.records.inner.durable)
        assertEquals("a temporary Keystore error changed the keys", setOf(DB_ALIAS), f.vault.keys.keys)
    }

    @Test fun `a legacy-password database is re-keyed, and its wrapped password stored only after a verified reopen`() {
        val f = Fixture()
        f.database(legacy)

        val plan = f.opener().plan()

        assertTrue("a legacy-password database was opened with the published password, not re-keyed ($plan)",
            plan is DatabaseOpenPlan.Open && !plan.password.contentEquals(legacy))
        plan as DatabaseOpenPlan.Open
        assertArrayEquals("the file is not keyed with the new password", plan.password, f.cipher.keyedWith)
        assertTrue("the re-keyed file was set aside", f.asides().isEmpty())
        val state = DatabaseKeyProvisioner(reopened(f.records.inner), f.vault).resolve()
        assertTrue("the new password is not stored under its own key ($state)",
            state is DatabaseKeyState.Stored && state.dedicated && state.password.contentEquals(plan.password))
        assertFalse("the re-key's pending record was left behind", f.records.inner.durable.containsKey(DatabaseKeyProvisioner.PENDING))

        val log = f.log
        val pendingAt = log.indexOfFirst { it.startsWith("write:") && DatabaseKeyProvisioner.PENDING in it && it.endsWith(":true") }
        val rekeyAt = log.indexOf("rekey")
        val reopenedAt = log.indexOf("reopened")
        val liveAt = log.indexOfFirst { it.startsWith("write:") && DatabaseKeyProvisioner.WRAPPED in it }
        assertTrue("the new password was not kept (pending) before the file was re-keyed: $log", pendingAt in 0 until rekeyAt)
        assertTrue("the re-key was not verified by a reopen: $log", reopenedAt > rekeyAt)
        assertTrue("the wrapped password was stored before the verified reopen: $log", liveAt > reopenedAt)
    }

    @Test fun `a re-key that did not take stores no wrapped password`() {
        val f = Fixture()
        f.database(legacy)
        f.cipher.onRekey = { _, _ -> RekeyAnswer.UNCHANGED }

        val plan = f.opener().plan()

        assertTrue("after a re-key that did not take, the start does not stay on the legacy password ($plan)",
            plan is DatabaseOpenPlan.Open && plan.password.contentEquals(legacy) && !plan.recoverOnFirstOpen)
        assertFalse("a wrapped password was stored for a re-key that did not take",
            f.records.inner.durable.containsKey(DatabaseKeyProvisioner.WRAPPED))
        assertArrayEquals("the file's password changed", legacy, f.cipher.keyedWith)
    }

    @Test fun `a re-key whose result is unknown acts on nothing more, and the next start finishes it`() {
        val f = Fixture()
        f.database(legacy)
        f.cipher.onRekey = { _, to -> f.cipher.keyedWith = to.copyOf(); RekeyAnswer.UNKNOWN }

        val first = f.opener().plan()
        assertTrue("a re-key with an unknown result was not refused for this run ($first)", first is DatabaseOpenPlan.Temporary)
        assertFalse("a wrapped password was stored for a re-key that was never verified",
            f.records.inner.durable.containsKey(DatabaseKeyProvisioner.WRAPPED))
        assertTrue("the re-key's new password was not kept for the next start",
            f.records.inner.durable.containsKey(DatabaseKeyProvisioner.PENDING))

        f.cipher.onRekey = null
        val next = Fixture(reopened(f.records.inner), f.vault)
        File(f.dir, f.name).copyTo(next.live())
        next.cipher.keyedWith = f.cipher.keyedWith
        val plan = next.opener().plan()
        assertTrue("the next start did not open the re-keyed file with its new password ($plan)",
            plan is DatabaseOpenPlan.Open && plan.password.contentEquals(f.cipher.keyedWith))
        assertTrue("the next start did not store the new password once the file opened with it",
            next.records.inner.durable.containsKey(DatabaseKeyProvisioner.WRAPPED))
    }

    @Test fun `a re-key interrupted before its password was stored is finished by the next start`() {
        val f = Fixture()
        f.database(legacy)
        f.cipher.onRekey = { _, to ->
            f.cipher.keyedWith = to.copyOf()
            f.records.inner.refuseWrites = true // the process ends before the live record lands
            RekeyAnswer.REKEYED
        }
        val first = f.opener().plan()
        assertTrue("the re-keyed file is not opened with its new password this run ($first)",
            first is DatabaseOpenPlan.Open && first.password.contentEquals(f.cipher.keyedWith))

        val next = Fixture(reopened(f.records.inner), f.vault)
        File(f.dir, f.name).copyTo(next.live())
        next.cipher.keyedWith = f.cipher.keyedWith
        val plan = next.opener().plan()
        assertTrue("the next start lost the re-keyed file's password ($plan)",
            plan is DatabaseOpenPlan.Open && plan.password.contentEquals(f.cipher.keyedWith))
        assertTrue("the re-keyed file was set aside", next.asides().isEmpty())
    }

    private fun sqliteFailure(message: String): SQLiteException = mockk {
        every { this@mockk.message } returns message
        every { cause } returns null
    }

    @Test fun `a first open that fails is told apart, and nothing is deleted`() {
        val stored = ByteArray(32) { 9 }

        // The stored password does not open a file the legacy password does: re-keyed in place.
        Fixture().let { f ->
            f.database(legacy)
            assertTrue("a legacy-password file under a stored record was not re-keyed to the stored password",
                f.opener().recoverFirstOpen(sqliteFailure("file is not a database (code 26)"), stored))
            assertArrayEquals(stored, f.cipher.keyedWith)
            assertTrue(f.asides().isEmpty())
        }
        // Neither opens it: a wrong password.
        Fixture().let { f ->
            f.database(ByteArray(32) { 5 })
            assertTrue("a file no password here opens was not set aside",
                f.opener().recoverFirstOpen(sqliteFailure("file is not a database (code 26)"), stored))
            assertSetAside(f, AsideReason.WRONG_PASSWORD, "first open, wrong password")
        }
        // The password opens it but the file is damaged.
        Fixture().let { f ->
            f.database(stored)
            assertTrue("a corrupt file was not set aside",
                f.opener().recoverFirstOpen(sqliteFailure("database disk image is malformed (code 11 SQLITE_CORRUPT)"), stored))
            assertSetAside(f, AsideReason.CORRUPT, "first open, corrupt")
        }
        // GUARD: a failure that says nothing about the file acts on nothing.
        Fixture().let { f ->
            f.database(stored)
            assertFalse(f.opener().recoverFirstOpen(sqliteFailure("database is locked (code 5 SQLITE_BUSY)"), stored))
            assertEquals("rows", f.live().readText())
            assertTrue(f.asides().isEmpty())
        }
    }

    @Test fun `a corrupt file is told apart from a wrong password`() {
        assertEquals(OpenAnswer.NOT_A_DATABASE, openFailureAnswer(sqliteFailure("file is not a database (code 26): , while compiling: SELECT COUNT(*) FROM sqlite_schema;")))
        assertEquals(OpenAnswer.CORRUPT, openFailureAnswer(sqliteFailure("database disk image is malformed (code 11 SQLITE_CORRUPT)")))
        assertEquals(OpenAnswer.CORRUPT, openFailureAnswer(sqliteFailure("database disk image is malformed (code 11)")))
        assertEquals(OpenAnswer.CORRUPT, openFailureAnswer(IllegalStateException("open failed", sqliteFailure("database disk image is malformed (code 11)"))))
        for (other in listOf("database is locked (code 5 SQLITE_BUSY)", "disk I/O error (code 10)", "database or disk is full (code 13)",
            "unable to open database file (code 14)", "code 260 not the same as 26", "code 110 not the same as 11")) {
            assertEquals("'$other'", OpenAnswer.UNDECIDED, openFailureAnswer(sqliteFailure(other)))
        }
        assertEquals(OpenAnswer.OPENS, openFailureAnswer(null))
        assertEquals("only the database's own failure counts", OpenAnswer.UNDECIDED, openFailureAnswer(IllegalStateException("file is not a database (code 26)")))
    }

    @Test fun `when the database cannot be set aside it is left where it is and the run uses a temporary database`() {
        val f = Fixture()
        f.database(ByteArray(32) { 6 })
        val refusing = object : DatabaseFileSet {
            override fun exists() = f.files.exists()
            override fun setAside(reason: AsideReason) = false
        }

        val plan = WalletDatabaseOpener(f.keys, refusing, f.cipher, pause = { }).plan()

        assertTrue("an unopenable database was replaced although it could not be set aside ($plan)", plan is DatabaseOpenPlan.Temporary)
        assertEquals("the database was not left where it is", "rows", f.live().readText())
        assertFalse("a new password was stored for a database that is still in place",
            f.records.inner.durable.containsKey(DatabaseKeyProvisioner.WRAPPED))
    }

    /**
     * The crash-loop breaker sets the database aside on every start while starts keep dying; those
     * asides are bounded. An unopenable database is set aside once and replaced by one that opens,
     * so it cannot recur and is not bounded. Nothing already aside is ever touched.
     */
    @Test fun `recovery asides are bounded, and no aside is ever deleted`() {
        val f = Fixture()
        f.database(ByteArray(32) { 6 })
        repeat(DatabaseFiles.MAX_RECOVERY_ASIDES) { i -> File(f.dir, "${f.name}.aside-recovery-$i").writeText("old-$i") }

        assertFalse("a recovery aside beyond the bound was made", f.files.setAside(AsideReason.RECOVERY))
        assertEquals("the database was moved although no aside was made", "rows", f.live().readText())
        assertTrue("an unopenable database could not be set aside because of recovery asides", f.files.setAside(AsideReason.WRONG_PASSWORD))
        repeat(DatabaseFiles.MAX_RECOVERY_ASIDES) { i ->
            assertEquals("an earlier aside was touched", "old-$i", File(f.dir, "${f.name}.aside-recovery-$i").readText())
        }
    }

    // GUARD (held before; must keep holding).
    @Test fun `a stored key opens the database whatever else is on disk, and the legacy password is never asked`() {
        for (exists in listOf(true, false)) {
            val f = Fixture()
            f.storedUnder(DB_ALIAS, ByteArray(32) { 7 })
            if (exists) f.database(ByteArray(32) { 7 })
            val plan = f.opener().plan()
            assertTrue(plan is DatabaseOpenPlan.Open && plan.password.contentEquals(ByteArray(32) { 7 }))
            assertFalse("the legacy password was tried on a file with a stored key", "probe:legacy" in f.log)
        }
    }

    // GUARD.
    @Test fun `a file whose probe says nothing about its key is kept and opened with the legacy password`() {
        val f = Fixture()
        f.database(legacy)
        f.cipher.undecided = true
        val plan = f.opener().plan()
        assertTrue(plan is DatabaseOpenPlan.Open && plan.password.contentEquals(legacy) && !plan.recoverOnFirstOpen)
        assertEquals("rows", f.live().readText())
        assertTrue(f.asides().isEmpty())
        assertFalse("something was recorded for a file nothing was learned about",
            f.records.inner.durable.containsKey(DatabaseKeyProvisioner.LEGACY_CONFIRMED))
    }

    @Test fun `a record under the seed's legacy key is moved onto its own key when the database opens`() {
        val f = Fixture()
        f.storedUnder(LEGACY_ALIAS, ByteArray(32) { 8 }, named = false)
        f.database(ByteArray(32) { 8 })
        val plan = f.opener().plan()
        assertTrue(plan is DatabaseOpenPlan.Open && plan.password.contentEquals(ByteArray(32) { 8 }))
        assertEquals("the record still depends on the seed's legacy key after the database opened",
            DB_ALIAS, f.records.inner.durable[DatabaseKeyProvisioner.ALIAS])
        assertTrue("the seed's legacy key was deleted by opening the database", LEGACY_ALIAS in f.vault.keys)
    }

    /** No code path that decides about the database deletes a database file or a key. */
    @Test fun `the database key code deletes no database and no key`() {
        val src = listOf("core/src/main/java/app/aroundtheblock/wallet/core/db/DatabaseKeyProvisioner.kt", "../core/src/main/java/app/aroundtheblock/wallet/core/db/DatabaseKeyProvisioner.kt")
            .map(::File).firstOrNull { it.exists() } ?: error("DatabaseKeyProvisioner.kt not found")
        val code = src.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertTrue("scanner is blind: no setAside in the provisioner", code.contains("fun setAside("))
        assertFalse("the database key code deletes a file", Regex("""\.delete\(\)""").containsMatchIn(code))
        assertFalse("the database key code deletes a key", Regex("""vault\.delete\(""").containsMatchIn(code))
        assertFalse("the database key code deletes a file", Regex("""deleteRecursively|Files\.delete""").containsMatchIn(code))
        assertNotNull(code)
    }
}
