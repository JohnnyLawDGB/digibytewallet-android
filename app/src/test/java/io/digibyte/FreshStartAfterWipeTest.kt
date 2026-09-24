package io.digibyte

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.digibyte.core.WipeReport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * What a wipe's report does to the process that ran it, and what the next launch does about it.
 *
 *  - SEED GONE: nothing of the wiped wallet runs on. The process is ended and a fresh one started on
 *    onboarding — whether or not every other store confirmed — so the next wallet created or
 *    restored is made in a process that never loaded the wiped one.
 *  - Seed gone, the rest NOT confirmed: before the process ends the erasure is marked owed, and the
 *    relaunch carries the one "did not complete" message. The fresh launch runs the wipe again before
 *    anything else and shows that message once — unless its own wipe completes the erasure.
 *  - Seed NOT gone: the process runs on as it is — the PIN, its counters and the owed wipe are kept by
 *    the wipe itself, the entry point shows its one message, and the wallet still opens with its PIN.
 *  - The launch a fresh start made never restarts again, whatever its own wipe finds, so a store
 *    that will not take its write costs at most one restart per launch, never a loop. A launch
 *    brought back from Recents carries the intent the restart left on its task, and is not that
 *    launch. An owed erasure is never run over a wallet stored since.
 *  - An entry point that runs its wipe in a screen's own scope (the unlock screen) reaches the
 *    restart even when that screen goes away while the wipe runs.
 *
 * The restart and the owed-erasure mark are observed through the seams the helper offers; nothing
 * here ends this JVM or writes a file.
 */
class FreshStartAfterWipeTest {

    /** One restart request: where from, on which thread, and whether it said the wipe did not complete. */
    private class Request(val context: Context, val thread: Thread, val incomplete: Boolean)

    private val requested = mutableListOf<Request>()
    /** Every step the seams saw, in order: "mark", "clear", "restart". */
    private val steps = mutableListOf<String>()
    private var marked = false
    private lateinit var shipped: ProcessRestart
    private lateinit var shippedMark: OwedErasure

    @Before fun observeRestarts() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        shipped = FreshStartAfterWipe.restart
        shippedMark = FreshStartAfterWipe.owedErasure
        FreshStartAfterWipe.restart = ProcessRestart { app, incomplete ->
            requested += Request(app, Thread.currentThread(), incomplete)
            steps += "restart"
        }
        FreshStartAfterWipe.owedErasure = object : OwedErasure {
            override fun mark(appContext: Context): Boolean { marked = true; steps += "mark"; return true }
            override fun isMarked(appContext: Context): Boolean = marked
            override fun clear(appContext: Context) { marked = false; steps += "clear" }
        }
    }

    @After fun putBack() {
        FreshStartAfterWipe.restart = shipped
        FreshStartAfterWipe.owedErasure = shippedMark
        unmockkStatic(Log::class)
    }

    private val app = mockk<Context>()
    private val screen = mockk<Context> { every { applicationContext } returns app }

    private val verified = WipeReport(seedGone = true, everythingCleared = true)
    private val seedGoneRestOwed = WipeReport(seedGone = true, everythingCleared = false)
    private val seedStillThere = WipeReport(seedGone = false, everythingCleared = false)

    /**
     * A launch intent, marked or not as the one a restart after a wipe made (and carrying or not the
     * message that the wipe did not complete), delivered fresh or as the task's own intent when the
     * task is brought back from Recents.
     */
    private fun launch(startedAfterWipe: Boolean, fromRecents: Boolean = false, incomplete: Boolean = false) = mockk<Intent> {
        every { getBooleanExtra(FreshStartAfterWipe.EXTRA_STARTED_AFTER_WIPE, false) } returns startedAfterWipe
        every { getBooleanExtra(FreshStartAfterWipe.EXTRA_WIPE_INCOMPLETE, false) } returns incomplete
        every { flags } returns if (fromRecents) Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY else 0
    }

    /** What one launch's backstop did: how many wipes it ran, and whether it shows the one message. */
    private class Launch(val wipes: Int, val showsMessage: Boolean)

    private fun atLaunch(
        wipeOwed: Boolean = false,
        walletStored: Boolean = false,
        launch: Intent? = launch(false),
        recreated: Boolean = false,
        wipe: WipeReport = verified,
    ): Launch {
        var wipes = 0
        val shows = FreshStartAfterWipe.atLaunch(
            screen, wipeOwed = wipeOwed, walletStored = walletStored, launch = launch, recreated = recreated,
        ) { wipes++; wipe }
        return Launch(wipes, shows)
    }

    // ---- the verdict of a wipe an entry point ran ----

    @Test fun `a verified wipe asks for a fresh process, once`() {
        assertFalse("a verified wipe left its entry point something to report", FreshStartAfterWipe.afterWipe(screen, verified))
        assertEquals("a verified wipe must end this process and start a fresh one", 1, requested.size)
        assertSame("the fresh process is started from the application, not the screen", app, requested.single().context)
        assertFalse("a verified wipe was reported to the fresh launch as one that did not complete", requested.single().incomplete)
        assertFalse("a verified wipe left an erasure owed", marked)
    }

    @Test fun `once the seed is gone a wipe that did not complete still ends this process`() {
        assertFalse(
            "the process is ending, yet its entry point was left to report the wipe",
            FreshStartAfterWipe.afterWipe(screen, seedGoneRestOwed),
        )
        assertEquals("the seed is gone and the process that held the wallet was kept", 1, requested.size)
        assertSame(app, requested.single().context)
        assertTrue("the fresh launch is not told the wipe did not complete", requested.single().incomplete)
    }

    @Test fun `the erasure a wipe left undone is marked owed before the process ends`() {
        FreshStartAfterWipe.afterWipe(screen, seedGoneRestOwed)
        assertEquals("the owed erasure is not marked, or not before the process ends", listOf("mark", "restart"), steps)
    }

    @Test fun `a wipe that left the seed on the device runs on in this process`() {
        assertTrue("the entry point is not left to report a wipe that did not complete", FreshStartAfterWipe.afterWipe(screen, seedStillThere))
        assertEquals("a wipe that left the seed ended the process that still holds the wallet", 0, requested.size)
        assertFalse("a wipe that left the seed marked an erasure for the next launch", marked)
    }

    // ---- the launch backstop ----

    @Test fun `nothing owed at a launch runs no wipe`() {
        val run = atLaunch(launch = launch(false))
        assertEquals(0, run.wipes)
        assertFalse(run.showsMessage)
        assertEquals(0, requested.size)
    }

    @Test fun `the launch backstop starts a fresh process after a verified wipe`() {
        val run = atLaunch(wipeOwed = true, walletStored = true, launch = launch(false))
        assertEquals(1, run.wipes)
        assertFalse(run.showsMessage)
        assertEquals(1, requested.size)
        assertSame(app, requested.single().context)
    }

    @Test fun `a launch with no intent is an ordinary launch`() {
        atLaunch(wipeOwed = true, walletStored = true, launch = null)
        assertEquals(1, requested.size)
    }

    @Test fun `the process a restart started is not restarted again by its own backstop`() {
        // Only reachable when the owed-wipe flag's release did not reach the disk: the fresh process
        // then wipes again at launch and must run on rather than restart for ever — whether its own
        // wipe verified or only removed the seed again.
        for (report in listOf(verified, seedGoneRestOwed)) {
            atLaunch(wipeOwed = true, launch = launch(true), wipe = report)
        }
        assertEquals("the backstop restarted the process a restart had just started", 0, requested.size)
    }

    @Test fun `an activity recreated later in that process is not the launch the restart made`() {
        atLaunch(wipeOwed = true, launch = launch(true), recreated = true)
        assertEquals("a later verified wipe in the restarted process kept the process running", 1, requested.size)
    }

    @Test fun `a launch brought back from Recents is not the launch the restart made`() {
        // Recents relaunches a task with the intent it was started with, extras and all, so the
        // marker a restart once put there says nothing about this launch.
        atLaunch(wipeOwed = true, launch = launch(true, fromRecents = true))
        assertEquals("a verified wipe in a launch from Recents kept the process running", 1, requested.size)
    }

    @Test fun `a backstop wipe that left the seed on the device never restarts`() {
        for (byARestart in listOf(true, false)) for (recreated in listOf(true, false)) for (fromRecents in listOf(true, false)) {
            val run = atLaunch(wipeOwed = true, walletStored = true, launch = launch(byARestart, fromRecents), recreated = recreated, wipe = seedStillThere)
            assertTrue("a backstop wipe that left the seed shows no message", run.showsMessage)
        }
        assertEquals(0, requested.size)
    }

    @Test fun `at an ordinary launch a backstop wipe that removed the seed ends the process, verified or not`() {
        val run = atLaunch(wipeOwed = true, walletStored = true, launch = launch(false), wipe = seedGoneRestOwed)
        assertEquals("the seed is gone and the process that ran the wipe was kept", 1, requested.size)
        assertTrue(requested.single().incomplete)
        assertTrue("the rest of the erasure is not left owed to the fresh launch", marked)
        assertFalse("the ending process shows a message the fresh launch shows", run.showsMessage)
    }

    // ---- the fresh launch after a wipe that removed the seed and not the rest ----

    @Test fun `the fresh launch finishes the owed erasure and says it did not complete once`() {
        marked = true
        val run = atLaunch(launch = launch(true, incomplete = true), wipe = seedGoneRestOwed)
        assertEquals("the erasure a fresh start left owed was not run again at that launch", 1, run.wipes)
        assertTrue("the fresh launch does not say the wipe did not complete", run.showsMessage)
        assertEquals("the fresh launch restarted again after its own wipe", 0, requested.size)
        assertFalse("the owed erasure is still marked after the launch ran it", marked)
    }

    @Test fun `a fresh launch whose own wipe completes the erasure has nothing left to report`() {
        marked = true
        val run = atLaunch(launch = launch(true, incomplete = true), wipe = verified)
        assertEquals(1, run.wipes)
        assertFalse("a message that the wipe did not complete, after it did", run.showsMessage)
        assertEquals(0, requested.size)
        assertFalse(marked)
    }

    @Test fun `a relaunch that carries the message shows it once, even when no erasure could be marked`() {
        // The mark is a write, and the store that refused the wipe's write may refuse this one too.
        val fresh = atLaunch(launch = launch(true, incomplete = true))
        assertEquals("a carried message ran a wipe: an intent never does", 0, fresh.wipes)
        assertTrue("the carried message is not shown", fresh.showsMessage)
        assertFalse(
            "the message was shown again by an activity recreated in that process",
            atLaunch(launch = launch(true, incomplete = true), recreated = true).showsMessage,
        )
        assertFalse(
            "the message was shown again by a launch brought back from Recents",
            atLaunch(launch = launch(true, fromRecents = true, incomplete = true)).showsMessage,
        )
        assertFalse("a relaunch after a verified wipe shows a message", atLaunch(launch = launch(true)).showsMessage)
    }

    @Test fun `a mark that does not land still ends the process and still carries the message`() {
        for ((label, refusing) in listOf(
            "refused" to object : OwedErasure {
                override fun mark(appContext: Context): Boolean = false
                override fun isMarked(appContext: Context): Boolean = false
                override fun clear(appContext: Context) {}
            },
            "throwing" to object : OwedErasure {
                override fun mark(appContext: Context): Boolean = throw IllegalStateException("store refused")
                override fun isMarked(appContext: Context): Boolean = false
                override fun clear(appContext: Context) {}
            },
        )) {
            requested.clear()
            FreshStartAfterWipe.owedErasure = refusing
            assertFalse(
                "a $label mark left the process with the seed gone to run on",
                FreshStartAfterWipe.afterWipe(screen, seedGoneRestOwed),
            )
            assertEquals("a $label mark kept the process that held the wiped wallet", 1, requested.size)
            assertTrue("a $label mark dropped the message the fresh launch must show", requested.single().incomplete)
        }
    }

    @Test fun `a launch whose extras cannot be read is an ordinary launch`() {
        // A Bundle that cannot be unpacked makes every extra read throw on API 26-32; the launch
        // must still open, and nothing in such an intent may start or report a wipe.
        val unreadable = mockk<Intent> {
            every { getBooleanExtra(any(), any()) } answers { throw android.os.BadParcelableException("unreadable") }
            every { flags } returns 0
        }
        val launched = atLaunch(launch = unreadable)
        assertEquals("an unreadable intent ran a wipe", 0, launched.wipes)
        assertFalse("an unreadable intent showed the wipe message", launched.showsMessage)
    }

    @Test fun `an owed erasure is never run over a wallet stored since`() {
        marked = true
        val run = atLaunch(walletStored = true, launch = launch(false))
        assertEquals("an owed erasure ran over a stored wallet", 0, run.wipes)
        assertFalse("an owed erasure outlived the wallet stored after it", marked)
        assertFalse(run.showsMessage)
        assertFalse(
            "a carried message was shown over a stored wallet",
            atLaunch(walletStored = true, launch = launch(true, incomplete = true)).showsMessage,
        )
        assertEquals(0, requested.size)
    }

    @Test fun `an erasure still owed at an ordinary launch runs there first`() {
        // The relaunch was not taken (the app was in the background): the next launch finishes it.
        marked = true
        val run = atLaunch(launch = launch(false), wipe = verified)
        assertEquals(1, run.wipes)
        assertEquals("the seed is gone and the process that ran the wipe was kept", 1, requested.size)
        assertFalse(marked)
    }

    @Test fun `a store that keeps refusing its write costs one restart and one message, then nothing`() {
        // The process that ran the wipe: the seed is gone, one store refused.
        assertFalse(FreshStartAfterWipe.afterWipe(screen, seedGoneRestOwed))
        val restart = requested.single()
        // The launch that restart made, over the same refusing store.
        val fresh = atLaunch(launch = launch(true, incomplete = restart.incomplete), wipe = seedGoneRestOwed)
        assertEquals(1, fresh.wipes)
        assertTrue(fresh.showsMessage)
        // Every later launch: nothing owed, nothing run, nothing restarted.
        val later = atLaunch(launch = launch(false), wipe = seedGoneRestOwed)
        assertEquals(0, later.wipes)
        assertFalse(later.showsMessage)
        assertEquals("a store that refuses its write restarted the app more than once", 1, requested.size)
    }

    // ---- an entry point that runs its wipe in a screen's own scope ----

    /** What became of one hand-over from a screen. */
    private class ScreenRun(val incomplete: Boolean?, val carriedOn: Boolean, val screenThread: Thread?, val wipeThread: Thread?)

    /**
     * Runs [FreshStartAfterWipe.wipeThenHandOver] as the unlock screen does — in a scope of the
     * screen's own, here on one thread of its own — over a wipe that, like the one routine every
     * entry point runs, goes on to its end once started. With [goesAway] the screen's scope is
     * cancelled while the wipe runs (the screen disposed, the activity destroyed or recreated).
     */
    private fun handOverFromAScreen(report: WipeReport, goesAway: Boolean): ScreenRun = runBlocking {
        var ownThread: Thread? = null
        val screenThread = Executors.newSingleThreadExecutor { task -> Thread(task, "screen").also { ownThread = it } }
        try {
            val screenScope = CoroutineScope(Job() + screenThread.asCoroutineDispatcher())
            val wipeStarted = CompletableDeferred<Unit>()
            val wipeMayEnd = CompletableDeferred<Unit>()
            var wipeThread: Thread? = null
            var incomplete: Boolean? = null
            var carriedOn = false
            val entry = screenScope.launch {
                incomplete = FreshStartAfterWipe.wipeThenHandOver(screen) {
                    withContext(NonCancellable) {
                        wipeThread = Thread.currentThread()
                        wipeStarted.complete(Unit)
                        wipeMayEnd.await()
                        report
                    }
                }
                carriedOn = true
            }
            wipeStarted.await()
            if (goesAway) screenScope.cancel()
            wipeMayEnd.complete(Unit)
            entry.join()
            ScreenRun(incomplete, carriedOn, ownThread, wipeThread)
        } finally {
            screenThread.shutdownNow()
        }
    }

    @Test fun `a verified wipe reaches the restart even when the screen that started it goes away meanwhile`() {
        handOverFromAScreen(verified, goesAway = true)
        assertEquals("the screen went away during a verified wipe and the process running it was kept", 1, requested.size)
        assertSame("the fresh process is started from the application, not the screen", app, requested.single().context)
    }

    @Test fun `a screen's wipe that removed the seed but not the rest ends the process too, whatever becomes of the screen`() {
        handOverFromAScreen(seedGoneRestOwed, goesAway = true)
        assertEquals("the seed is gone and the process that held the wallet was kept", 1, requested.size)
        assertTrue(requested.single().incomplete)
        assertTrue(marked)
    }

    @Test fun `the wipe runs off the screen's thread and its report is handed over back on it`() {
        val run = handOverFromAScreen(verified, goesAway = false)
        assertTrue("the wipe or the screen did not run", run.wipeThread != null && run.screenThread != null)
        assertNotEquals("the wipe ran on the screen's own thread", run.screenThread, run.wipeThread)
        assertEquals("the restart was not asked for from the screen's thread", listOf(run.screenThread), requested.map { it.thread })
        assertEquals(false, run.incomplete)
    }

    @Test fun `a wipe that left the seed comes back to a screen that is still there`() {
        val run = handOverFromAScreen(seedStillThere, goesAway = false)
        assertEquals(true, run.incomplete)
        assertTrue("the screen did not go on to report the wipe", run.carriedOn)
        assertEquals("a wipe that left the seed ended the process that still holds the wallet", 0, requested.size)
    }

    @Test fun `after a wipe that left the seed a screen that went away does nothing more`() {
        val run = handOverFromAScreen(seedStillThere, goesAway = true)
        assertEquals(0, requested.size)
        assertFalse("the screen's own work ran on after the screen was gone", run.carriedOn)
    }

    // ---- the mark itself ----

    /** Preferences that record how each edit was handed to the disk. */
    private class RecordingPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        val writes = mutableListOf<String>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?) = map[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as Boolean? ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = mutableListOf<() -> Unit>()
            override fun putString(key: String?, value: String?) = apply { pending += { map[key!!] = value } }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending += { map[key!!] = values } }
            override fun putInt(key: String?, value: Int) = apply { pending += { map[key!!] = value } }
            override fun putLong(key: String?, value: Long) = apply { pending += { map[key!!] = value } }
            override fun putFloat(key: String?, value: Float) = apply { pending += { map[key!!] = value } }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending += { map[key!!] = value } }
            override fun remove(key: String?) = apply { pending += { map.remove(key) } }
            override fun clear() = apply { pending += { map.clear() } }
            override fun commit(): Boolean { pending.forEach { it() }; writes += "commit"; return true }
            override fun apply() { pending.forEach { it() }; writes += "apply" }
        }
    }

    @Test fun `the owed erasure is marked by a write that has landed before the process ends`() {
        val prefs = RecordingPrefs()
        val files = mutableSetOf<String>()
        val appContext = mockk<Context> {
            every { getSharedPreferences(any(), any()) } answers { files += firstArg<String>(); prefs }
        }
        val mark = shippedMark
        assertFalse(mark.isMarked(appContext))
        assertTrue("the mark reports a write that did not land", mark.mark(appContext))
        assertTrue(mark.isMarked(appContext))
        mark.clear(appContext)
        assertFalse(mark.isMarked(appContext))
        // The process ends straight after the mark, and an apply() still queued then is lost.
        assertEquals("the mark is not written synchronously", listOf("commit", "commit"), prefs.writes)
        assertEquals("the mark is kept in more than one store", 1, files.size)
    }
}
