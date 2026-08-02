import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

// Mirrors SyncService.launchBoundedRecovery exactly, with TIMEOUT shortened.
private const val TIMEOUT = 1_000L
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val inFlight = AtomicBoolean(false)
private fun log(s: String) = println("[LOG] $s")

fun launchBoundedRecovery(tag: String, block: suspend () -> Unit) {
    if (!inFlight.compareAndSet(false, true)) { log("$tag: already in flight — not stacking"); return }
    val worker = scope.launch {
        try { block() }
        catch (t: Throwable) { log("$tag: recovery ended: ${t.javaClass.simpleName}") }
        finally { inFlight.set(false) }
    }
    scope.launch {
        delay(TIMEOUT)
        if (worker.isActive) {
            worker.cancel()
            inFlight.set(false)
            log("$tag: recovery exceeded ${TIMEOUT}ms and was abandoned")
        }
    }
}

// A leg that IGNORES Thread.interrupt(), exactly like a blocking JNI call.
fun uninterruptibleBlock(ms: Long) {
    val end = System.nanoTime() + ms * 1_000_000
    while (System.nanoTime() < end) { /* spin: no syscall, interrupt has no effect */ }
}

fun main() = runBlocking {
    println("=== A: THE OLD SHAPE (withTimeoutOrNull) vs an uninterruptible leg ===")
    val tA = System.currentTimeMillis()
    val old = scope.launch {
        val done = withTimeoutOrNull(TIMEOUT) { runInterruptible(Dispatchers.IO) { uninterruptibleBlock(4000) } }
        log("OLD: withTimeoutOrNull returned ${done} after ${System.currentTimeMillis()-tA}ms")
    }
    delay(2000)
    println("  at t=2000ms (2x the timeout): old-shape coroutine still active = ${old.isActive}  <-- RED: never abandoned")
    old.join()
    println("  it only returned after ${System.currentTimeMillis()-tA}ms — i.e. when the blocking call finished\n")

    println("=== B: THE NEW SHAPE vs the same uninterruptible leg ===")
    val tB = System.currentTimeMillis()
    launchBoundedRecovery("proof") { runInterruptible(Dispatchers.IO) { uninterruptibleBlock(4000) } }
    delay(2000)
    println("  at t=${System.currentTimeMillis()-tB}ms: inFlight latch = ${inFlight.get()}  <-- must be false")
    require(!inFlight.get()) { "FAIL: latch stayed shut" }

    println("\n=== C: a LATER trigger must still be able to run while the stuck one leaks ===")
    var ran = false
    launchBoundedRecovery("later") { ran = true }
    delay(300)
    println("  later recovery ran = $ran  <-- must be true")
    require(ran) { "FAIL: watchdog could not retry" }

    println("\n=== D: interruptible leg (a socket) IS actually cut short ===")
    val tD = System.currentTimeMillis()
    val done = java.util.concurrent.CountDownLatch(1)
    launchBoundedRecovery("interruptible") {
        runInterruptible(Dispatchers.IO) {
            try { Thread.sleep(10_000) } catch (e: InterruptedException) {
                println("  leg interrupted at ${System.currentTimeMillis()-tD}ms  <-- defence 1 works"); done.countDown(); throw e
            }
        }
    }
    withContext(Dispatchers.IO) { done.await() }
    require(System.currentTimeMillis()-tD < 3000) { "FAIL: interruptible leg not cut short" }

    delay(3000)
    println("\nALL PROOFS PASSED")
    scope.cancel()
}
