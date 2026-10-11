package app.aroundtheblock.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Covers [BootGuard]'s restore-crash bracket: a SEPARATE flag/counter
 * (`restore_pending` / `restore_crash_count`) from the pre-unlock
 * `pending_boots` guard, added to detect a native crash inside the
 * POST-UNLOCK wallet-restore risky window — a crash that happens AFTER
 * [BootGuard.HEALTHY_AFTER_MS] has already reset `pending_boots`, so the
 * original guard alone can't see it.
 *
 * These tests exercise the REAL [StaleDataWiper] (not mocked) against a
 * stateful in-memory fake [SharedPreferences]/[Context] so the seed/PIN-safety
 * assertion (case e) is verified against production wipe code, not a stub.
 *
 * THE IDLE-SAFETY INVARIANT (case d) is the single most important assertion
 * in this file: a user who opens the app and never unlocks (so [BootGuard
 * .beginRestore] is never called) must NEVER be wiped.
 */
class BootGuardRestoreBracketTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ---------- (a) begin -> healthy = clean, no recovery next launch ----------

    @Test
    fun `begin then healthy leaves no restore-crash recovery on next launch`() {
        val harness = Harness()

        BootGuard.beginRestore(harness.context)
        BootGuard.markRestoreHealthy(harness.context)

        // "Next launch" = a fresh call against the same persisted (fake) prefs.
        val recovery = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)

        assertEquals(BootGuard.RestoreRecovery.None, recovery)
        // Nothing was wiped: no sync/db prefs were ever touched.
        assertFalse(harness.factory.requestedNames.contains("dgb_sync_data"))
        assertFalse(harness.factory.requestedNames.contains("dgb_bloom_peers"))
    }

    // ---------- (b) begin without healthy (simulated crash) -> sync wipe ----------

    @Test
    fun `begin without healthy simulates a crash and next-launch recovery wipes sync and clears the flag`() {
        val harness = Harness()

        BootGuard.beginRestore(harness.context)
        // (crash — markRestoreHealthy never runs)

        val recovery = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)

        assertEquals(BootGuard.RestoreRecovery.Sync, recovery)
        // The corrupt sync blobs were actually wiped (real StaleDataWiper ran).
        assertTrue(harness.factory.requestedNames.contains("dgb_sync_data"))
        assertTrue(harness.factory.requestedNames.contains("dgb_bloom_peers"))
        // The DB was NOT touched on a single crash.
        assertFalse(harness.factory.requestedNames.contains("dgb_db_key"))
        assertEquals("the database was moved on a single crash", "rows", harness.database.readText())

        // The flag is cleared — a subsequent call this same launch is a no-op
        // (recoverFromCrashedRestoreIfNeeded only fires once per crashed restore).
        val again = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)
        assertEquals(BootGuard.RestoreRecovery.None, again)
    }

    // ---------- (c) begin-crash TWICE -> escalates to syncAndDb ----------

    @Test
    fun `begin-crash twice escalates to syncAndDb`() {
        val harness = Harness()
        val record = "c1c2:a1a2"
        harness.factory.prefsFor("dgb_db_key").edit().putString("encrypted_key", record).commit()

        BootGuard.beginRestore(harness.context)
        val first = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)
        assertEquals(BootGuard.RestoreRecovery.Sync, first)

        // User unlocks again this launch; the restore crashes again.
        BootGuard.beginRestore(harness.context)
        val second = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)

        assertEquals(BootGuard.RestoreRecovery.SyncAndDb, second)
        // The escalated recovery sets the database aside, never deletes it, and keeps the record
        // and key that read it: the aside copy stays readable and the next database uses the same key.
        assertFalse("the escalated recovery left the database at its live name", harness.database.exists())
        val aside = harness.asides()
        assertEquals("the escalated recovery deleted the database instead of setting it aside", listOf("rows"), aside.map { it.readText() })
        assertEquals("the escalated recovery discarded the database's key record",
            record, harness.factory.prefsFor("dgb_db_key").getString("encrypted_key", null))
    }

    // ---------- (c2) a re-key in progress is left to finish ----------

    /**
     * While a database's re-key is pending (its new password kept, `pending_key`, before the file is
     * re-keyed), the next start resumes the re-key on its own. The crash-loop breaker must not set that
     * database aside — a start killed while re-keying is exactly what it would otherwise count.
     */
    @Test
    fun `the crash-loop breaker leaves a database alone while a re-key is pending`() {
        val harness = Harness()
        harness.factory.prefsFor("dgb_db_key").edit().putString("pending_key", "c1c2:a1a2").commit()

        BootGuard.beginRestore(harness.context)
        BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)
        BootGuard.beginRestore(harness.context)
        assertEquals(BootGuard.RestoreRecovery.SyncAndDb, BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context))

        assertEquals("the crash-loop breaker set aside a database whose re-key is pending", "rows", harness.database.readText())
        assertTrue("a database whose re-key is pending was set aside", harness.asides().isEmpty())
    }

    @Test
    fun `the boot counter's recovery leaves a database alone while a re-key is pending`() {
        val harness = Harness()
        harness.factory.prefsFor("dgb_db_key").edit().putString("pending_key", "c1c2:a1a2").commit()
        harness.factory.prefsFor("dgb_boot_guard").edit().putInt("pending_boots", BootGuard.THRESHOLD_DB).commit()

        BootGuard.beginBoot(harness.context)

        assertEquals("the boot counter's recovery set aside a database whose re-key is pending", "rows", harness.database.readText())
        assertTrue(harness.asides().isEmpty())
    }

    // ---------- (d) NEVER-begin (idle user, never unlocked) -> none. THE CRITICAL CASE ----------

    @Test
    fun `idle user who never unlocks is NEVER wiped (idle-safety invariant)`() {
        val harness = Harness()

        // beginRestore is NEVER called — the user opened the app and sat on the
        // lock screen (or force-closed) without ever unlocking.
        val recovery = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)

        assertEquals(BootGuard.RestoreRecovery.None, recovery)
        // No wipe of any kind — sync blobs, bloom cache, or DB — was triggered.
        assertFalse(harness.factory.requestedNames.contains("dgb_sync_data"))
        assertFalse(harness.factory.requestedNames.contains("dgb_bloom_peers"))
        assertFalse(harness.factory.requestedNames.contains("dgb_db_key"))
        assertEquals("rows", harness.database.readText())

        // Repeating across many idle "launches" must stay idle-safe too.
        repeat(5) {
            assertEquals(BootGuard.RestoreRecovery.None, BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context))
        }
    }

    // ---------- (e) seed/PIN keys are never in the wipe set ----------

    @Test
    fun `wipe path never touches seed or PIN store keys, even escalated`() {
        val harness = Harness()

        // Drive it all the way to the escalated syncAndDb wipe — the widest
        // blast radius this bracket can trigger.
        BootGuard.beginRestore(harness.context)
        BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)
        BootGuard.beginRestore(harness.context)
        val recovery = BootGuard.recoverFromCrashedRestoreIfNeeded(harness.context)
        assertEquals(BootGuard.RestoreRecovery.SyncAndDb, recovery)

        // The seed store and PIN store are NEVER opened by the wipe path — this
        // runs the REAL StaleDataWiper against our fake Context, so this is a
        // behavioral assertion, not a stub check.
        assertFalse(harness.factory.requestedNames.contains("dgb_wallet_seed"))
        assertFalse(harness.factory.requestedNames.contains("dgb_pin_store"))

        // Belt-and-suspenders: the seed's Keystore alias ("dgb_wallet_master",
        // KeyStoreManager.KEY_ALIAS) can't be observed at runtime here — on a
        // host JVM (no Robolectric shadow keystore) `KeyStore.getInstance
        // ("AndroidKeyStore")` throws before any deleteEntry call, so the real
        // code path never reaches ks.deleteEntry in this test regardless of
        // what alias it would pass. Guard the invariant structurally instead:
        // StaleDataWiper's source must delete ONLY the DB-dedicated alias and
        // must never reference the seed's alias or its prefs key.
        val relPath = "src/main/java/app/aroundtheblock/wallet/StaleDataWiper.kt"
        val candidates = listOf(
            File(relPath),                 // CWD = app/ (Gradle module test working dir)
            File("app/$relPath"),           // CWD = repo root
            File("../$relPath"),            // CWD = some other module dir
        )
        val staleDataWiperSource = candidates.firstOrNull { it.exists() }
        assertTrue(
            "expected to find StaleDataWiper.kt to scan (tried: ${candidates.map { it.absolutePath }})",
            staleDataWiperSource != null
        )
        val src = staleDataWiperSource!!.readText()
        // Extract the literal string arguments actually passed to Keystore
        // deleteEntry(...)/containsAlias(...) calls (as opposed to just
        // grepping the whole file, which would false-positive on the doc
        // comment above that explains — in prose — why "dgb_wallet_master"
        // must NEVER be deleted here).
        val keystoreAliasArgs = Regex("""(?:deleteEntry|containsAlias)\("([^"]+)"\)""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        // It touches none at all now: the database is set aside together with the key that reads it.
        assertEquals(
            "StaleDataWiper must touch no Keystore alias — not the seed's, and not the database's, " +
                "whose key keeps the aside copy readable",
            emptySet<String>(),
            keystoreAliasArgs
        )
        assertTrue("scanner is blind: StaleDataWiper does not set the database aside",
            src.contains("setAside("))
        assertFalse("StaleDataWiper must never reference the raw seed prefs key",
            src.contains("\"dgb_wallet_seed\""))
    }

    // ==================== test harness ====================

    /**
     * Stateful in-memory fake so the SAME (name -> backing map) SharedPreferences
     * is returned across repeated `context.getSharedPreferences(name, mode)`
     * calls — required to model BootGuard's persisted state machine across
     * "launches" (each launch = one more call against the same fake Context).
     */
    private class InMemoryPrefsFactory {
        private val stores = mutableMapOf<String, MutableMap<String, Any?>>()
        val requestedNames = mutableListOf<String>()

        fun prefsFor(name: String): SharedPreferences {
            requestedNames += name
            val backing = stores.getOrPut(name) { mutableMapOf() }
            return fakeSharedPreferences(backing)
        }

        private fun fakeSharedPreferences(backing: MutableMap<String, Any?>): SharedPreferences {
            val prefs: SharedPreferences = mockk(relaxed = true)
            val editor: SharedPreferences.Editor = mockk(relaxed = true)

            every { prefs.getBoolean(any(), any()) } answers {
                (backing[firstArg<String>()] as? Boolean) ?: secondArg()
            }
            every { prefs.getInt(any(), any()) } answers {
                (backing[firstArg<String>()] as? Int) ?: secondArg()
            }
            every { prefs.getLong(any(), any()) } answers {
                (backing[firstArg<String>()] as? Long) ?: secondArg()
            }
            every { prefs.getString(any(), any()) } answers {
                (backing[firstArg<String>()] as? String) ?: secondArg()
            }
            every { prefs.contains(any()) } answers { backing.containsKey(firstArg<String>()) }
            every { prefs.edit() } returns editor

            every { editor.putBoolean(any(), any()) } answers {
                backing[firstArg<String>()] = secondArg<Boolean>(); editor
            }
            every { editor.putInt(any(), any()) } answers {
                backing[firstArg<String>()] = secondArg<Int>(); editor
            }
            every { editor.putLong(any(), any()) } answers {
                backing[firstArg<String>()] = secondArg<Long>(); editor
            }
            every { editor.putString(any(), any()) } answers {
                backing[firstArg<String>()] = secondArg<String?>(); editor
            }
            every { editor.remove(any()) } answers { backing.remove(firstArg<String>()); editor }
            every { editor.clear() } answers { backing.clear(); editor }
            every { editor.commit() } returns true
            every { editor.apply() } returns Unit

            return prefs
        }
    }

    private class Harness {
        val factory = InMemoryPrefsFactory()
        private val tempDir = createTempDirForTest()
        /** The wallet database at its live name, as the recovery finds it. */
        val database: File = File(tempDir, "wallet.db").apply { writeText("rows") }
        fun asides(): List<File> = tempDir.listFiles()!!.filter { it.name.startsWith("wallet.db.aside-") && !it.name.endsWith("-wal") }
        val context: Context = mockk(relaxed = true)

        init {
            every { context.getSharedPreferences(any(), any()) } answers {
                factory.prefsFor(firstArg())
            }
            // StaleDataWiper.wipeDatabase() finds the DB files via getDatabasePath();
            // point it at a scratch temp dir holding a stand-in database file.
            every { context.getDatabasePath(any()) } answers {
                File(tempDir, firstArg<String>())
            }
        }

        private fun createTempDirForTest(): File =
            File.createTempFile("boot-guard-restore-test", "").apply {
                delete(); mkdirs()
            }
    }
}
