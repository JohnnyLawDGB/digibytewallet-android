package app.aroundtheblock.wallet.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The unlock screen's countdown arithmetic ([LockCountdown]). GUARD: no red can exist, the screen had no
 * such step before; the red is UnlockCountdownClockGateTest's, against the screen that ticked on the wall
 * clock. The screen turns the wall-clock end PinManager reports into an end on the monotonic clock once,
 * then reads only the monotonic clock, so the date can be set any way while a cooldown runs.
 */
class LockCountdownTest {

    private val day = 86_400_000L
    private val year = 365L * day
    private val wall0 = 1_000_000_000_000L // the wall clock when the cooldown is reported
    private val up0 = 7L * day // the monotonic clock then: a week into a boot

    @Test fun `a reported end becomes the same time left on the monotonic clock`() {
        val end = LockCountdown.endOnMonotonicClock(wall0 + 60_000L, wall0, up0)
        assertEquals(up0 + 60_000L, end)
        assertEquals(60_000L, LockCountdown.leftMs(end, up0))
    }

    @Test fun `no end, or one already past, is no countdown`() {
        for (until in listOf(0L, wall0 - 1, wall0)) {
            val end = LockCountdown.endOnMonotonicClock(until, wall0, up0)
            assertEquals("reported end $until", 0L, end)
            assertEquals("reported end $until", 0L, LockCountdown.leftMs(end, up0))
        }
    }

    @Test fun `the countdown moves only with the time that passes, so a date change neither ends it early nor holds it`() {
        // After the one conversion the countdown takes no wall-clock reading at all: however the date is
        // set while it runs, only the monotonic clock moves it, by exactly the time that passes.
        val end = LockCountdown.endOnMonotonicClock(wall0 + 60_000L, wall0, up0)
        for (passed in listOf(1L, 10_000L, 30_000L, 59_999L)) {
            assertEquals("after $passed ms the keypad is still locked", 60_000L - passed, LockCountdown.leftMs(end, up0 + passed))
        }
        assertEquals("at its end the keypad comes back", 0L, LockCountdown.leftMs(end, up0 + 60_000L))
        assertEquals(0L, LockCountdown.leftMs(end, up0 + 60_001L))
    }

    @Test fun `reopened after a date change, the screen counts down the time actually left`() {
        // PinManager reports the end as the wall clock now plus the time left, whatever the date says.
        for (wall in listOf(wall0, wall0 + year, wall0 - year)) {
            assertEquals("wall clock $wall", up0 + 45_000L, LockCountdown.endOnMonotonicClock(wall + 45_000L, wall, up0))
        }
    }
}
