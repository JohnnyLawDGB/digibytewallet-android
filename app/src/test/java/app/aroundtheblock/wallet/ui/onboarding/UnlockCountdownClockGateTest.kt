package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate: the unlock screen counts a PIN cooldown down on the monotonic clock.
 *
 * PinManager reports when a cooldown ends as a wall-clock epoch-ms. The screen turns that into an end
 * on `SystemClock.elapsedRealtime()` once, as it arrives, through `LockCountdown.endOnMonotonicClock`,
 * and from then on reads only the monotonic clock. So changing the date can neither bring the keypad
 * back before the cooldown is over nor keep it disabled after. The arithmetic is covered by
 * `LockCountdownTest`; this pins that the screen goes through it, because the screen is a composable
 * with no test harness here. It reads code, not text: a clock read in a comment or a literal is none.
 */
class UnlockCountdownClockGateTest {

    private val path = "src/main/java/app/aroundtheblock/wallet/ui/onboarding/UnlockScreen.kt"
    private val screen = KotlinSourceGate.of(File(path).readText())

    private fun lineOf(at: Int): Int = screen.code.substring(0, at).count { it == '\n' } + 1

    /** Each `name = value` in the code (not `==`, not a named argument's `=` inside a call), as line to value. */
    private fun assignments(name: String): List<Pair<Int, String>> =
        Regex("""(?<![\w.])${Regex.escape(name)}\s*=(?!=)""").findAll(screen.code)
            .filterNot { screen.code.substring(0, it.range.first).trimEnd().let { head -> head.endsWith("(") || head.endsWith(",") } }
            .map { found ->
                val end = screen.code.indexOf('\n', found.range.last).let { if (it < 0) screen.code.length else it }
                lineOf(found.range.first) to screen.code.substring(found.range.last + 1, end).trim().replace(Regex("""\s+"""), " ")
            }.toList()

    @Test fun `the wall clock is read only to turn a reported end into one on the monotonic clock`() {
        val conversions = screen.calls("LockCountdown.endOnMonotonicClock")
        val outside = screen.calls("System.currentTimeMillis").filter { read -> conversions.none { read.range.first in it.range } }
        assertTrue(
            "$path reads the wall clock outside LockCountdown.endOnMonotonicClock at lines " +
                outside.map { lineOf(it.range.first) },
            outside.isEmpty(),
        )
    }

    @Test fun `the countdown ticks on SystemClock elapsedRealtime and on no other clock`() {
        val ticks = assignments("nowTick")
        assertTrue("$path never sets the countdown tick", ticks.isNotEmpty())
        val offClock = ticks.filter { (_, value) -> value != "SystemClock.elapsedRealtime()" }
        assertTrue("$path sets the countdown tick from another clock: $offClock", offClock.isEmpty())
        for (other in listOf("SystemClock.uptimeMillis", "System.nanoTime", "SystemClock.currentThreadTimeMillis")) {
            assertTrue("$path reads $other", screen.calls(other).isEmpty())
        }
    }

    @Test fun `the lock end is only ever a converted end or none`() {
        val ends = assignments("lockEndsAt")
        assertTrue(
            "$path keeps no lock end on the monotonic clock (none is set from LockCountdown.endOnMonotonicClock)",
            ends.any { (_, value) -> value.startsWith("LockCountdown.endOnMonotonicClock(") },
        )
        val other = ends.filterNot { (_, value) -> value == "0L" || value.startsWith("LockCountdown.endOnMonotonicClock(") }
        assertTrue("$path sets the lock end to something else: $other", other.isEmpty())
    }
}
