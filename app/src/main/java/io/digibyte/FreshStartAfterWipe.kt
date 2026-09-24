package io.digibyte

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.digibyte.core.WipeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Once a wipe has removed the seed, nothing of the wiped wallet runs in any process: the process that
 * held it ends, and a fresh one starts on onboarding — whether or not every other store confirmed its
 * erasure. The next wallet created or restored is then made in a process that never loaded the wiped
 * one — no native wallet or peer manager of it, no open handle on its database, no loop that could
 * still write one of its stores.
 *
 * Every wipe entry point — Settings, the unlock screen, the spend dialog, the launch backstop —
 * hands the report of `WalletManager.wipeThenReleasePin` straight here, by a way that runs to its
 * end even when the screen that started the wipe goes away meanwhile. By then the wipe itself
 * has stopped the sync service and quiesced native sync (stop sync, zero the seed) before erasing
 * anything, so nothing writes a wiped store between the erase and the restart.
 *
 * A wipe that removed the seed but not everything else is finished by the fresh launch. Before this
 * process ends the erasure is marked owed, and the relaunch carries the one "did not complete"
 * message. The launch backstop ([atLaunch]) then runs the wipe again before anything else and shows
 * that message — unless its own wipe completes the erasure — and every later launch does the same
 * until the erasure completes, never by restarting again.
 *
 * A wipe that could NOT remove the seed does not restart: the process runs on exactly as before — the
 * PIN, its counters and the owed wipe are kept, the entry point shows its one message, the launch
 * backstop retries at the next start, and the wallet whose seed is still on the device opens with
 * its PIN.
 */
object FreshStartAfterWipe {

    private const val TAG = "FreshStartAfterWipe"

    /** Marks the launch a restart after a wipe made. See [atLaunch]. */
    internal const val EXTRA_STARTED_AFTER_WIPE = "io.digibyte.STARTED_AFTER_WIPE"

    /** On that launch: the wipe that ended the process before it removed the seed, but did not complete. */
    internal const val EXTRA_WIPE_INCOMPLETE = "io.digibyte.WIPE_INCOMPLETE"

    /** How the fresh process is started. JVM tests replace it to observe the request. */
    @Volatile
    internal var restart: ProcessRestart = RelaunchInFreshProcess

    /** Where an erasure left owed waits for the next launch. JVM tests replace it. */
    @Volatile
    internal var owedErasure: OwedErasure = OwedErasureMark

    /** Set by the first [atLaunch] of this process; an owed erasure runs only at that launch. */
    @Volatile
    internal var launchCheckedInThisProcess = false

    /**
     * Take a wipe's report. Once the seed is gone this process ends and a fresh one starts on
     * onboarding (on the main thread this call does not return; off it, the end follows on the main
     * thread at once); an erasure left incomplete is first marked owed, for the fresh launch to
     * finish and report.
     *
     * @return true when this process runs on with a wipe that did not complete — the seed is still on
     *   the device — and the caller shows the one message; false when there is nothing left for the
     *   caller to report: the process is ending, and the fresh launch reports whatever is left.
     */
    fun afterWipe(context: Context, wipe: WipeReport): Boolean {
        if (!wipe.seedGone) return true
        val app = context.applicationContext
        val incomplete = !wipe.verified
        // Durable before the process ends; a mark that did not land still leaves the relaunch to say so.
        if (incomplete && !runCatching { owedErasure.mark(app) }.getOrDefault(false)) {
            Log.w(TAG, "the erasure left owed could not be marked; the fresh launch is still told")
        }
        restart.startFresh(app, incomplete)
        return false
    }

    /**
     * Runs [wipe] off the calling thread and hands its report to [afterWipe] back on it, for an
     * entry point that runs its wipe in a screen's own scope (the unlock screen).
     *
     * That scope can be cancelled while the wipe runs: the activity destroyed in the background, or
     * recreated by a configuration change it does not absorb. The wipe and the hand-over still run
     * to their end, so a wipe that removed the seed always reaches the restart and nothing of the
     * wiped wallet runs on in this process. Only after the hand-over does the caller's cancellation
     * apply again: the screen's own work on a wipe that left the seed runs only while the screen is
     * there. Returns what [afterWipe] returns.
     */
    suspend fun wipeThenHandOver(context: Context, wipe: suspend () -> WipeReport): Boolean {
        val incomplete = withContext(NonCancellable) { afterWipe(context, withContext(Dispatchers.IO) { wipe() }) }
        currentCoroutineContext().ensureActive()
        return incomplete
    }

    /**
     * The launch backstop, asked at every launch before anything else runs. It runs [wipe] when a
     * wipe is owed at this launch — [wipeOwed] (wipe-after-N tripped and its wipe did not complete),
     * or an erasure a fresh start left owed — and hands the report over as [afterWipe] does.
     *
     * An owed erasure is never run over a wallet stored since ([walletStored], asked before the
     * wipe): that wallet is not the one the erasure was owed for, and the mark is dropped. The
     * relaunch's message is never a reason to wipe: only a mark this app wrote is.
     *
     * The launch a fresh start made ([launch] marked, not an activity [recreated] later in that
     * process, not a task brought back from Recents, which is started again with the intent it was
     * first started with, extras included) runs on whatever its own wipe established: it is never
     * restarted again. An erasure owed at a launch that holds no wallet runs there without a
     * restart, since that process never loaded one — and only at the first launch of a process, never
     * in an activity recreated later or opened beside a live one. Its mark stays until an erasure
     * verifies, so a store that keeps refusing its write is retried, with the message, at every new
     * process — at most one restart per launch, never a loop.
     *
     * @return true when this launch shows the one "did not complete" message: after a wipe of its
     *   own, when that wipe did not complete and this process runs on (so at every launch while an
     *   owed erasure keeps failing); with no wipe to run, when
     *   this is the launch a fresh start made and its relaunch carried the message while no wallet
     *   is stored.
     */
    fun atLaunch(
        context: Context,
        wipeOwed: Boolean,
        walletStored: Boolean,
        launch: Intent?,
        recreated: Boolean,
        wipe: () -> WipeReport,
    ): Boolean {
        val app = context.applicationContext
        val madeByARestart = launchedByARestart(launch, recreated)
        // Only the first launch of a process runs an owed erasure: a recreated activity, or a second
        // one a link opens, may sit beside a wallet being created on another thread.
        val firstInProcess = !launchCheckedInThisProcess
        launchCheckedInThisProcess = true
        val erasureOwed = runCatching { owedErasure.isMarked(app) }.getOrDefault(false)
        val finishesErasure = erasureOwed && !walletStored && firstInProcess
        if (erasureOwed && walletStored) clearOwedErasure(app)
        if (!wipeOwed && !finishesErasure) {
            return madeByARestart && !walletStored && launch.flag(EXTRA_WIPE_INCOMPLETE)
        }
        Log.w(TAG, "a wipe is owed at this launch; running it before anything else")
        val report = wipe()
        // The mark stays until an erasure verifies, so every launch retries while a store refuses.
        if (finishesErasure && report.verified) clearOwedErasure(app)
        if (madeByARestart) {
            if (report.seedGone) Log.w(TAG, "wipe ran again in the process a fresh start began; running on instead of restarting again")
            return !report.verified
        }
        // Finishing an owed erasure at a launch with no wallet stored: this process never loaded one,
        // so there is nothing to restart away from.
        if (!wipeOwed) return !report.verified
        return afterWipe(context, report)
    }

    private fun launchedByARestart(launch: Intent?, recreated: Boolean): Boolean {
        if (launch == null || recreated) return false
        if ((launch.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return false
        return launch.flag(EXTRA_STARTED_AFTER_WIPE)
    }

    /**
     * One of this helper's own launch markers, read so that a launch intent whose extras cannot be
     * unpacked is an ordinary launch: it reads false, never ends the launch, and never starts or
     * reports a wipe.
     */
    private fun Intent?.flag(name: String): Boolean =
        this != null && runCatching { getBooleanExtra(name, false) }.getOrDefault(false)

    private fun clearOwedErasure(app: Context) {
        runCatching { owedErasure.clear(app) }.onFailure {
            Log.w(TAG, "the owed erasure's mark was not removed (${it.javaClass.simpleName})")
        }
    }
}

/** Starts the app again in a fresh process. */
fun interface ProcessRestart {
    /** [incomplete]: the wipe removed the seed but did not complete, and the fresh launch says so. */
    fun startFresh(appContext: Context, incomplete: Boolean)
}

/**
 * Where a fresh start leaves an erasure a wipe could not finish, for the next launch to finish. It
 * holds that one fact and nothing of the wallet.
 */
internal interface OwedErasure {
    /** Durably, before the process ends. False when the write did not land. */
    fun mark(appContext: Context): Boolean
    fun isMarked(appContext: Context): Boolean
    fun clear(appContext: Context)
}

internal object OwedErasureMark : OwedErasure {
    private const val STORE = "dgb_erasure_owed"
    private const val OWED = "owed"

    private fun store(appContext: Context) = appContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    // commit(), not apply(): the process ends straight after the mark, and an apply() still queued
    // then is lost with it.
    override fun mark(appContext: Context): Boolean = store(appContext).edit().putBoolean(OWED, true).commit()

    override fun isMarked(appContext: Context): Boolean = store(appContext).getBoolean(OWED, false)

    override fun clear(appContext: Context) {
        store(appContext).edit().remove(OWED).commit()
    }
}

/**
 * Starts the launcher activity as a new task in its base state ([Intent.makeRestartActivityTask]:
 * NEW_TASK | CLEAR_TASK, so every activity of the old task is finished), telling it whether the
 * wipe completed, then ends this process at once with [android.os.Process.killProcess] — no exit
 * handlers or native teardown run while the process's other threads are still at work.
 *
 * Both steps run on the main thread, back to back: the old activity cannot pause in between, so the
 * system brings the new task up in a new process rather than in this one. A caller off the main
 * thread hands them to it and returns.
 *
 * Entry points call it while the app is normally in the foreground — the user has just confirmed a
 * wipe or entered a PIN, or the launcher activity is being created — where the start is allowed on
 * every supported release. The unlock screen's hand-over also runs to its end after that screen went
 * away, which can be in the background, where the system refuses the start. Nothing runs in any
 * other process of the app meanwhile. Either way this process ends; if the start was not taken, the
 * next launch opens on onboarding just the same, and finishes any erasure left owed.
 */
internal object RelaunchInFreshProcess : ProcessRestart {
    override fun startFresh(appContext: Context, incomplete: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            relaunch(appContext, incomplete)
        } else {
            Handler(Looper.getMainLooper()).post { relaunch(appContext, incomplete) }
        }
    }

    private fun relaunch(appContext: Context, incomplete: Boolean) {
        val launch = Intent.makeRestartActivityTask(ComponentName(appContext, MainActivity::class.java))
            .putExtra(FreshStartAfterWipe.EXTRA_STARTED_AFTER_WIPE, true)
            .putExtra(FreshStartAfterWipe.EXTRA_WIPE_INCOMPLETE, incomplete)
        try {
            appContext.startActivity(launch)
        } catch (t: Throwable) {
            Log.w("FreshStartAfterWipe", "relaunch not taken (${t.javaClass.simpleName}); ending the process anyway")
        }
        Log.i("FreshStartAfterWipe", "seed gone: ending this process, starting a fresh one (complete=${!incomplete})")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
