package app.aroundtheblock.wallet.core.db

import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefs
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
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
 * write-ahead log: committed rows still in the log would be lost to it. Invariant: after a process that
 * ended at any move, and the next start (which finishes what it finds half done before anything opens
 * the database), the database and every companion are together — all still at the live name, or all
 * under one aside name — never apart.
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
    private val everyFile = listOf("") + companions

    private fun database(dir: File) = everyFile.forEach { File(dir, name + it).writeText("rows$it") }

    /** A set-aside of [dir]'s database that ends the process on the move of [fatal]. */
    private fun interrupt(dir: File, fatal: String) {
        var tick = 5_000L
        val files = DatabaseFiles(dir, name, clock = { tick++ }, move = { from, to ->
            if (from.name == fatal) throw ProcessEnded() else from.renameTo(to)
        })
        try {
            files.setAside(AsideReason.CORRUPT)
            fail("precondition: the set-aside was interrupted at $fatal")
        } catch (e: ProcessEnded) {
            // the process ended mid-way
        }
    }

    /** The database and its companions are together: all at the live name, or all under one aside name. */
    private fun assertTogether(dir: File, case: String): Boolean {
        val atLive = everyFile.filter { File(dir, name + it).exists() }
        if (atLive.size == everyFile.size) {
            everyFile.forEach { assertEquals("$case: $name$it changed", "rows$it", File(dir, name + it).readText()) }
            assertTrue("$case: something was set aside beside a database still in place: ${dir.list()!!.sorted()}",
                dir.list()!!.none { it.startsWith("$name.aside-") })
            return false
        }
        assertEquals("$case: the database is apart from its companions — at the live name: $atLive; all: ${dir.list()!!.sorted()}",
            emptyList<String>(), atLive)
        val mains = dir.listFiles()!!.filter { it.name.startsWith("$name.aside-") && it.readText() == "rows" }
        assertEquals("$case: the database is not under exactly one aside name: ${dir.list()!!.sorted()}", 1, mains.size)
        val stem = mains.single().name
        companions.forEach { suffix ->
            assertEquals("$case: the database is apart from its $suffix: ${dir.list()!!.sorted()}",
                "rows$suffix", File(dir, stem + suffix).takeIf { it.exists() }?.readText())
        }
        return true
    }

    @Test fun `after a process that ended at any move, the database and its companions are together`() {
        for (fatal in everyFile.map { name + it }) {
            val dir = tmp.newFolder()
            database(dir)
            interrupt(dir, fatal)

            assertTrue("ended at '$fatal': the next start could not finish what it found", DatabaseFiles(dir, name).finishInterrupted())
            assertTogether(dir, "ended at '$fatal'")
        }
    }

    @Test fun `the opener finishes a half-done set-aside before it decides anything`() {
        val dir = tmp.newFolder()
        database(dir)
        interrupt(dir, "$name-shm") // a point at which some files have moved and some have not

        val plan = WalletDatabaseOpener(
            DatabaseKeyProvisioner(InMemoryPrefs(), FakeVault()),
            DatabaseFiles(dir, name),
            object : CipherFiles {
                override fun probe(password: ByteArray) = OpenAnswer.UNDECIDED
                override fun rekey(from: ByteArray, to: ByteArray) = RekeyAnswer.UNKNOWN
            },
            pause = { },
        ).plan()

        val setAside = assertTogether(dir, "through the opener")
        assertTrue("the half-done set-aside was not finished: the database is back in use", setAside)
        assertTrue("with nothing left at the live name a new database opens ($plan)", plan is DatabaseOpenPlan.Open)
    }

    // GUARD: a set-aside that is not interrupted leaves nothing to finish, and finishing moves nothing.
    @Test fun `a completed set-aside leaves nothing to finish`() {
        val dir = tmp.newFolder()
        database(dir)
        assertTrue(DatabaseFiles(dir, name).setAside(AsideReason.CORRUPT))
        assertTrue(assertTogether(dir, "completed"))
        val before = dir.list()!!.sorted()

        assertTrue(DatabaseFiles(dir, name).finishInterrupted())
        File(dir, name).writeText("new")
        assertTrue(DatabaseFiles(dir, name).finishInterrupted())

        assertEquals("finishing touched a database that was not being set aside", "new", File(dir, name).readText())
        assertEquals((before + name).sorted(), dir.list()!!.sorted())
    }
}
