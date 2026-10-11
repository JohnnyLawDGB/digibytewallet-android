package app.aroundtheblock.wallet.core.db

import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefs
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Setting a database aside moves several files — the database and its companions — one rename at a
 * time. A process that ends between two of those renames must not leave the database apart from its
 * write-ahead log: committed rows still in the log would be lost to it. Invariant: a set-aside the
 * process did not live to finish is finished before anything opens the database, so the database and
 * every companion end up together under one aside name, and nothing is left at the live name.
 */
class SetAsideInterruptedTest {

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

    /** The process ends: nothing after this point of the set-aside runs. */
    private class ProcessEnded : Error("process ended")

    private val name = "wallet.db"
    private val companions = listOf("-wal", "-shm", "-journal")

    private fun database(dir: File) {
        File(dir, name).writeText("rows")
        companions.forEach { File(dir, name + it).writeText("rows$it") }
    }

    /** A set-aside of [dir]'s database that ends the process on the move of [fatal]. */
    private fun interrupt(dir: File, fatal: (File) -> Boolean) {
        var tick = 5_000L
        val files = DatabaseFiles(dir, name, clock = { tick++ }, move = { from, to ->
            if (fatal(from)) throw ProcessEnded() else from.renameTo(to)
        })
        try {
            files.setAside(AsideReason.CORRUPT)
            fail("precondition: the set-aside was interrupted")
        } catch (e: ProcessEnded) {
            // the process ended mid-way
        }
    }

    /** The aside files, grouped by the aside name their database file carries. */
    private fun assertOneCompleteAside(dir: File, case: String) {
        assertFalse("$case: the database is still at its live name, apart from its write-ahead log", File(dir, name).exists())
        companions.forEach { assertFalse("$case: $name$it is still at its live name", File(dir, name + it).exists()) }
        val mains = dir.listFiles()!!.filter { it.name.startsWith("$name.aside-") && it.readText() == "rows" }
        assertEquals("$case: the database was not set aside exactly once: ${dir.list()!!.sorted()}", 1, mains.size)
        val stem = mains.single().name
        companions.forEach { suffix ->
            assertEquals("$case: the database is apart from its $suffix", "rows$suffix", File(dir, stem + suffix).takeIf { it.exists() }?.readText())
        }
    }

    @Test fun `a set-aside interrupted between the write-ahead log and the database is finished before the next open`() {
        val dir = tmp.newFolder()
        database(dir)
        interrupt(dir) { it.name == name } // the companions moved; the database did not

        val next = DatabaseFiles(dir, name)
        assertTrue("an interrupted set-aside was not finished", next.finishInterrupted())

        assertOneCompleteAside(dir, "interrupted before the database moved")
    }

    @Test fun `a set-aside interrupted at any move is finished before the next open`() {
        for (fatalAt in listOf("-wal", "-shm", "-journal", "")) {
            val dir = tmp.newFolder()
            database(dir)
            interrupt(dir) { it.name == name + fatalAt }

            assertTrue("interrupted at '$name$fatalAt': not finished", DatabaseFiles(dir, name).finishInterrupted())
            assertOneCompleteAside(dir, "interrupted at '$name$fatalAt'")
        }
    }

    @Test fun `the opener finishes an interrupted set-aside before it decides anything`() {
        val dir = tmp.newFolder()
        database(dir)
        interrupt(dir) { it.name == name }

        val plan = WalletDatabaseOpener(
            DatabaseKeyProvisioner(InMemoryPrefs(), FakeVault()),
            DatabaseFiles(dir, name),
            object : CipherFiles {
                override fun probe(password: ByteArray) = OpenAnswer.UNDECIDED
                override fun rekey(from: ByteArray, to: ByteArray) = RekeyAnswer.UNKNOWN
            },
            pause = { },
        ).plan()

        assertOneCompleteAside(dir, "through the opener")
        assertTrue("with nothing left at the live name a new database opens ($plan)", plan is DatabaseOpenPlan.Open)
    }

    // GUARD: a set-aside that is not interrupted leaves nothing to finish, and finishing moves nothing.
    @Test fun `a completed set-aside leaves nothing to finish`() {
        val dir = tmp.newFolder()
        database(dir)
        assertTrue(DatabaseFiles(dir, name).setAside(AsideReason.CORRUPT))
        val before = dir.list()!!.sorted()

        assertTrue(DatabaseFiles(dir, name).finishInterrupted())
        File(dir, name).writeText("new")
        assertTrue(DatabaseFiles(dir, name).finishInterrupted())

        assertEquals("finishing touched a database that was not being set aside", "new", File(dir, name).readText())
        assertEquals((before + name).sorted(), dir.list()!!.sorted())
    }
}
