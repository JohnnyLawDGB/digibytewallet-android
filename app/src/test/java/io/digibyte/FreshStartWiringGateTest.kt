package io.digibyte

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: every wipe entry point hands the report of the one wipe routine straight to
 * [FreshStartAfterWipe], so once a wipe — from Settings, the unlock screen, the spend dialog or the
 * launch backstop — has removed the seed, the process that held the wiped wallet ends and a fresh one
 * starts on onboarding, and the launch backstop, asked at every launch, finishes what a wipe left owed.
 *
 * A scan because the entry points are an Activity, two view models and a composable. What the
 * report does is covered by `FreshStartAfterWipeTest`, and so is the unlock screen's hand-over,
 * which runs to its end whatever happens to the screen's scope; the wipe routine itself, which
 * stops the sync service and quiesces native sync before it erases anything, by `WalletWipeTest`
 * and `WalletSessionAfterWipeTest` in `core`. This pins that each entry point hands over the
 * routine's own answer, spelt as the routine's call and nothing around it — nothing between the
 * report and the hand-over can change it, drop it or run first — that each shows the one message
 * only on the hand-over's answer, and that the shipped restart really starts the launcher task
 * afresh, tells it what the wipe left owed, and then ends the process.
 */
class FreshStartWiringGateTest {

    private val appRoot = File("src/main/java/io/digibyte")
    private fun gate(rel: String) = KotlinSourceGate.of(File(appRoot, rel).readText())

    private val unlockScreen = "ui/onboarding/UnlockScreen.kt"
    private val backstop = "MainActivity.kt"
    private val theWipe = "walletManager.wipeThenReleasePin(pinManager)"
    private val notice = "showWipeIncompleteNotice"

    /**
     * The entry points that hand a report over from a view model: the file, the hand-over it calls,
     * and the one spelling the report takes there. Anything written around the routine's call could
     * answer "the seed is gone" for a wipe that did not remove it, and restart over a wallet still on
     * the device.
     */
    private val handOvers = listOf(
        "ui/settings/SettingsViewModel.kt" to "FreshStartAfterWipe.afterWipe",
        "ui/components/SpendAuth.kt" to "FreshStartAfterWipe.afterWipe",
    )

    /**
     * What runs only when the hand-over answered true: the answer held as `val answer = …call(…)`
     * (a receiver written before the call is allowed), named nowhere else, then `if (answer) …`.
     */
    private fun onTheAnswer(file: KotlinSourceGate, call: KotlinSourceGate.Call): IntRange? {
        val lineStart = file.code.lastIndexOf('\n', call.range.first) + 1
        val held = Regex("""^\s*val\s+(\w+)\s*=\s*[\w.]*$""").find(file.code.substring(lineStart, call.range.first)) ?: return null
        val name = held.groupValues[1]
        if (Regex("""(?<!\w)$name(?!\w)""").findAll(file.code).count() != 2) return null
        return file.branchesOn(name).singleOrNull()
    }

    private fun backstopCall(): KotlinSourceGate.Call {
        val calls = gate(backstop).calls("FreshStartAfterWipe.atLaunch")
        assertEquals("the launch backstop does not hand its wipe over", 1, calls.size)
        return calls.single()
    }

    @Test fun `every wipe entry point hands the routine's own report over`() {
        for ((rel, handOver) in handOvers) {
            val file = gate(rel)
            val calls = file.calls(handOver)
            assertEquals("$rel does not hand its wipe's report to $handOver", 1, calls.size)
            assertEquals("$rel hands over something other than the wipe's own answer", theWipe, calls.single().arguments.getOrNull(1))
            val wipes = file.calls("walletManager.wipeThenReleasePin")
            assertEquals("$rel runs a wipe whose report is not handed over", 1, wipes.size)
            assertTrue("$rel runs its wipe outside the hand-over", wipes.single().range.first in calls.single().range)
        }
        // The unlock screen hands over the routine itself, to be run and its answer handed on.
        val screen = gate(unlockScreen)
        val handOver = screen.calls("FreshStartAfterWipe.wipeThenHandOver")
        assertEquals("the unlock screen does not hand its wipe over", 1, handOver.size)
        assertEquals(listOf("context"), handOver.single().arguments)
        val wipe = screen.trailingBlock(handOver.single()) ?: error("the unlock screen hands over no wipe")
        assertEquals("the unlock screen hands over something other than the wipe", "{ $theWipe }", KotlinSourceGate.squeeze(screen.written.substring(wipe)))
        val wipes = screen.calls("walletManager.wipeThenReleasePin")
        assertEquals("the unlock screen runs a wipe whose report is not handed over", 1, wipes.size)
        assertTrue("the unlock screen runs its wipe outside the hand-over", wipes.single().range.first in wipe)
        // So does the launch backstop, which runs it only when a wipe is owed at that launch.
        val main = gate(backstop)
        val call = backstopCall()
        assertEquals(
            "the launch backstop hands over something other than the wipe",
            "{ kotlinx.coroutines.runBlocking { $theWipe } }", call.named["wipe"],
        )
        val backstopWipes = main.calls("walletManager.wipeThenReleasePin")
        assertEquals("the launch backstop runs a wipe whose report is not handed over", 1, backstopWipes.size)
        assertTrue("the launch backstop runs its wipe outside the hand-over", backstopWipes.single().range.first in call.range)
    }

    @Test fun `the hand-over runs to its end whatever happens to the screen`() {
        // The wipe flips the wallet state and navigation pops the screen that started it: a hand-over
        // outside the block that outlives that scope would be cancelled before it restarts anything.
        for ((rel, handOver) in handOvers) {
            val file = gate(rel)
            val opener = Regex("""withContext\s*\([^)]*NonCancellable[^)]*\)\s*\{""").find(file.code)
                ?: error("scanner is blind: no block that runs to its end in $rel")
            val block = file.blockAt(file.code.indexOf('{', opener.range.first)) ?: error("unbalanced block in $rel")
            val calls = file.calls(handOver)
            assertEquals("$rel does not hand its wipe's report over", 1, calls.size)
            assertTrue("$rel hands the report over outside the block that runs to its end", calls.single().range.first in block)
        }
        // The unlock screen runs its wipe in a scope of the screen's own — the activity can be
        // destroyed or recreated while the wipe runs — so it hands over only through the one
        // hand-over that runs to its end whatever happens to that scope (FreshStartAfterWipeTest).
        val screen = gate(unlockScreen)
        assertEquals("the unlock screen does not hand its wipe over", 1, screen.calls("FreshStartAfterWipe.wipeThenHandOver").size)
        assertEquals(
            "the unlock screen hands a report over by a way its own scope can cut short",
            0, screen.calls("FreshStartAfterWipe.afterWipe").size,
        )
    }

    @Test fun `the launch backstop is asked at every launch and knows the launch a restart made`() {
        val main = gate(backstop)
        val handOver = backstopCall()
        assertEquals("the backstop does not pass its launch intent", "intent", handOver.named["launch"])
        assertEquals("the backstop does not say whether the activity was recreated", "savedInstanceState != null", handOver.named["recreated"])
        assertEquals("the backstop does not say whether a wipe is owed", "pinManager.isWipePending()", handOver.named["wipeOwed"])
        assertEquals("the backstop does not say whether a wallet is stored", "walletManager.hasSavedWallet()", handOver.named["walletStored"])
        // Asked at EVERY launch: an erasure a fresh start left owed is not an owed wipe-after-N, so a
        // backstop that runs only while that flag is set would never finish it.
        val declared = Regex("""override\s+fun\s+onCreate\s*\(""").find(main.code) ?: error("scanner is blind: no onCreate")
        val onCreate = main.blockAt(main.code.indexOf('{', declared.range.last)) ?: error("unbalanced onCreate")
        assertTrue("the backstop is not asked from onCreate", handOver.range.first in onCreate)
        assertEquals("the backstop is asked only under a condition of its own", onCreate, main.enclosingBlock(handOver.range.first))
        assertTrue(
            "the backstop runs after something that may show the wallet",
            main.calls("startSyncService", onCreate).all { it.range.first > handOver.range.last },
        )
    }

    @Test fun `each entry point shows the one message only on the hand-over's answer`() {
        // Settings and the spend dialog: inside the branch on the hand-over's answer.
        for ((rel, handOver) in handOvers) {
            val file = gate(rel)
            val guarded = onTheAnswer(file, file.calls(handOver).single()) ?: error("$rel does not act on the hand-over's answer")
            val shown = file.calls(notice).filterNot { file.code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            assertEquals("$rel shows the one message other than once", 1, shown.size)
            assertTrue("$rel shows the message whatever the hand-over answered", shown.single().range.first in guarded)
        }
        // The launch backstop: once, on its answer, which already counts what the relaunch carried.
        val main = gate(backstop)
        val guarded = onTheAnswer(main, backstopCall()) ?: error("the launch backstop does not act on the hand-over's answer")
        val shown = main.calls("io.digibyte.ui.components.$notice")
        assertEquals("the launch backstop shows the one message other than once", 1, shown.size)
        assertTrue("the launch backstop shows the message whatever the hand-over answered", shown.single().range.first in guarded)
    }

    @Test fun `after a wipe that removed the seed the unlock screen does nothing more`() {
        val screen = gate(unlockScreen)
        val declared = Regex("""suspend\s+fun\s+runOwedWipe\s*\(\s*\)\s*\{""").find(screen.code) ?: error("scanner is blind: no runOwedWipe")
        val body = screen.blockAt(declared.range.last) ?: error("unbalanced runOwedWipe")
        val leave = screen.branchesOn("!incomplete", body)
        assertEquals("a wipe that removed the seed does not leave the routine", 1, leave.size)
        assertEquals("return", screen.code.substring(leave.single()).trim())
        val handOver = screen.calls("FreshStartAfterWipe.wipeThenHandOver", body).singleOrNull()
            ?: error("the unlock screen does not hand its owed wipe over")
        assertTrue("the answer is read before it is handed over", handOver.range.first < leave.single().first)
        val navigations = screen.calls("navController.navigate", body)
        assertTrue("scanner is blind: runOwedWipe no longer navigates", navigations.isNotEmpty())
        val shown = screen.calls("io.digibyte.ui.components.$notice", body)
        assertTrue("scanner is blind: runOwedWipe no longer shows the one message", shown.isNotEmpty())
        assertTrue(
            "the unlock screen navigates or reports before a wipe that removed the seed has left the routine",
            (navigations + shown).all { it.range.first > leave.single().last },
        )
    }

    @Test fun `the shipped restart starts the launcher task afresh, then ends the process`() {
        val helper = gate("FreshStartAfterWipe.kt")
        assertTrue(
            "the restart the app ships is not the one that relaunches",
            Regex("""var\s+restart\s*:\s*ProcessRestart\s*=\s*RelaunchInFreshProcess\b""").containsMatchIn(helper.code),
        )
        val declared = Regex("""object\s+RelaunchInFreshProcess\b[^{]*\{""").find(helper.code) ?: error("scanner is blind: no RelaunchInFreshProcess")
        val body = helper.blockAt(declared.range.last) ?: error("unbalanced RelaunchInFreshProcess")
        val task = helper.calls("Intent.makeRestartActivityTask", body)
        assertEquals("the fresh process is not started as the launcher task in its base state", 1, task.size)
        assertEquals("ComponentName(appContext, MainActivity::class.java)", task.single().arguments.singleOrNull())
        val extras = helper.calls("putExtra", body)
        val marked = extras.filter { it.arguments.firstOrNull()?.endsWith("EXTRA_STARTED_AFTER_WIPE") == true }
        assertEquals("the fresh launch is not marked as the one a restart made", listOf("true"), marked.map { it.arguments.getOrNull(1) })
        val told = extras.filter { it.arguments.firstOrNull()?.endsWith("EXTRA_WIPE_INCOMPLETE") == true }
        assertEquals("the fresh launch is not told whether the wipe completed", listOf("incomplete"), told.map { it.arguments.getOrNull(1) })
        val start = helper.calls("appContext.startActivity", body)
        assertEquals("the relaunch is not started", 1, start.size)
        // Ended with a signal to itself, at once: no exit handlers or native teardown while other
        // threads of the process are still at work.
        val end = helper.calls("android.os.Process.killProcess", body)
        assertEquals("the process is not ended", 1, end.size)
        assertEquals(listOf("android.os.Process.myPid()"), end.single().arguments)
        assertTrue("the process runs exit handlers on its way out", helper.calls("exit", body).isEmpty())
        assertTrue("the process ends before the relaunch is handed to the system", start.single().range.first < end.single().range.first)
        val relaunch = Regex("""private\s+fun\s+relaunch\s*\(""").find(helper.code) ?: error("scanner is blind: no relaunch step")
        val relaunchBody = helper.blockAt(helper.code.indexOf('{', relaunch.range.last)) ?: error("unbalanced relaunch step")
        assertTrue("the relaunch and the end are not one step", start.single().range.first in relaunchBody && end.single().range.first in relaunchBody)
        // A start the system refuses by throwing must not keep the process: the start stands in a
        // try whose catch takes any Throwable, and the end comes after both.
        val attempt = Regex("""(?<!\w)try\s*\{""").findAll(helper.code).filter { it.range.first in relaunchBody }.toList()
        assertEquals("the relaunch is not tried apart from the end", 1, attempt.size)
        val tried = helper.blockAt(attempt.single().range.last) ?: error("unbalanced try")
        assertTrue("the start is not inside the try", start.single().range.first in tried)
        val caught = Regex("""^\s*catch\s*\(\s*\w+\s*:\s*Throwable\s*\)\s*\{""").find(helper.code.substring(tried.last + 1))
            ?: error("a start that throws is not caught whatever it throws")
        val handler = helper.blockAt(tried.last + 1 + caught.range.last) ?: error("unbalanced catch")
        assertTrue("the process does not end after a start that throws", end.single().range.first > handler.last)
    }

    /**
     * The start and the end run on the main thread, back to back: the old activity cannot pause in
     * between, so the new task comes up in a new process rather than in the one about to end.
     */
    @Test fun `the relaunch and the end run on the main thread`() {
        val helper = gate("FreshStartAfterWipe.kt")
        val declared = Regex("""override\s+fun\s+startFresh\s*\(""").find(helper.code) ?: error("scanner is blind: no startFresh")
        val body = helper.blockAt(helper.code.indexOf('{', declared.range.last)) ?: error("unbalanced startFresh")
        val onMain = helper.branchesOn("Looper.myLooper() == Looper.getMainLooper()", body)
        assertEquals("startFresh does not ask whether it already runs on the main thread", 1, onMain.size)
        assertEquals("on the main thread the relaunch does not run at once", 1, helper.calls("relaunch", onMain.single()).size)
        val posted = Regex("""Handler\s*\(\s*Looper\s*\.\s*getMainLooper\s*\(\s*\)\s*\)\s*\.\s*post\s*\{""")
            .findAll(helper.code).filter { it.range.first in body }.toList()
        assertEquals("off the main thread the relaunch is not handed to it", 1, posted.size)
        val handed = helper.blockAt(posted.single().range.last) ?: error("nothing is handed to the main thread")
        assertEquals("the main thread is handed something other than the relaunch", 1, helper.calls("relaunch", handed).size)
        assertEquals("startFresh relaunches other than through those two branches", 2, helper.calls("relaunch", body).size)
    }
}
