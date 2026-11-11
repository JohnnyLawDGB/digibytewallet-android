package app.aroundtheblock.wallet.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A single-thread stand-in for Main, for tests that run a real ViewModel asynchronously, as on a
 * device. It also records whatever reaches the default uncaught-exception handler while the test
 * runs: on a device that is the event that ends the process.
 *
 * A ViewModel's work must not outlive its test. A coroutine still running when Main is reset
 * resumes onto no Main at all, and the resulting "Dispatchers.Main was accessed …" exception lands
 * in whichever test's handler is installed at that moment — a later test, which then fails for
 * something it never did. So every ViewModel a test runs is [track]ed, and when the test ends its
 * scope is cancelled and its coroutines are waited for while this Main is still installed; only
 * then is Main reset and the handler put back.
 *
 * Use as a `@get:Rule`: a rule wraps `@Before` and `@After`, so Main is installed before any of
 * them and reset after all of them.
 */
class MainThreadStandIn : ExternalResource() {

    /** Exceptions that reached the default uncaught-exception handler during the test. */
    val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

    lateinit var dispatcher: ExecutorCoroutineDispatcher
        private set

    private val scopes: MutableList<Job> = Collections.synchronizedList(mutableListOf())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    override fun before() {
        dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(dispatcher)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped += t }
    }

    /** Registers [vm] so its scope is wound down, and waited for, before Main is reset. */
    fun <VM : ViewModel> track(vm: VM): VM = vm.also { scopes += it.viewModelScope.coroutineContext.job }

    override fun after() {
        try {
            val jobs = synchronized(scopes) { scopes.toList() }
            jobs.forEach { it.cancel() }
            runBlocking { withTimeout(DRAIN_TIMEOUT_MS) { jobs.forEach { it.join() } } }
        } finally {
            Dispatchers.resetMain()
            dispatcher.close()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 5_000L
    }
}
