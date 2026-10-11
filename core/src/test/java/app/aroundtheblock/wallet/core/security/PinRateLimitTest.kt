package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Pure-JVM unit tests for the persisted PIN rate-limit. The store is faked
 * in-memory ([FakePinStore]) so no Robolectric / AndroidX Security is needed;
 * setPin/verifyPin exercise the real PBKDF2 path (the Argon2 native lib is
 * unavailable off-device, so [PinManager] transparently falls back — the
 * rate-limit logic under test is identical either way).
 *
 * A fresh [PinManager] constructed over the SAME [FakePinStore] simulates a
 * force-stop: the persisted lockout must survive. The clocks are faked too ([FakePinClock]):
 * time passing moves both of them, the owner setting the date moves the wall clock alone, and
 * a reboot starts the monotonic clock again from zero.
 */
class PinRateLimitTest {

    /** In-memory [PinStore] shared across [PinManager] instances to model persistence. */
    private class FakePinStore : PinStore {
        val map = HashMap<String, Any?>()
        override fun getInt(key: String, def: Int): Int = (map[key] as? Int) ?: def
        override fun getLong(key: String, def: Long): Long = (map[key] as? Long) ?: def
        override fun getString(key: String): String? = map[key] as? String
        override fun getBoolean(key: String, def: Boolean): Boolean = (map[key] as? Boolean) ?: def
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun putString(key: String, value: String) { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    /**
     * The device's clocks, set by the test. [pass] is time passing: both clocks move together.
     * Setting [wall] alone is the owner changing the date in Settings: no time passes. [reboot]
     * starts the monotonic clock again in a new boot. It starts a week into a boot.
     */
    private class FakePinClock(var wall: Long, var sinceBoot: Long = 7L * DAY, var boot: Int = 41) : PinClock {
        override fun sinceBootMs(): Long = sinceBoot
        override fun bootCount(): Int = boot
        override fun wallMs(): Long = wall

        fun pass(ms: Long) { sinceBoot += ms; wall += ms }

        /** The device restarts: it is off for [offMs], and when the app next looks the new boot has
         *  run for [upMs]. The wall clock moves on by both; the monotonic clock reads [upMs]. Where
         *  the boot count cannot be read it stays [PinClock.UNKNOWN_BOOT]. */
        fun reboot(offMs: Long = 40_000L, upMs: Long = 25_000L) {
            wall += offMs + upMs
            sinceBoot = upMs
            if (boot != PinClock.UNKNOWN_BOOT) boot += 1
        }
    }

    private companion object {
        const val DAY = 86_400_000L
        const val YEAR = 365L * DAY
    }

    private val correct = "123456"
    private val wrong = "000000"
    private val t0 = 1_000_000_000_000L // the wall clock when each test starts
    private val clock = FakePinClock(wall = t0)

    private fun freshManagerWithPin(store: FakePinStore = FakePinStore()): Pair<PinManager, FakePinStore> {
        val mgr = PinManager(store, clock = clock)
        mgr.setPin(correct)
        return mgr to store
    }

    /** As [freshManagerWithPin], but the PIN record is made through [StandInArgon2id], which answers at once,
     *  instead of the 600 000-round PBKDF2. For the cases about the clock: the cooldown does not depend on
     *  the hash, and a stand-in record counts exactly like a PBKDF2 one (see the stand-in section below). */
    private fun quickManagerWithPin(store: FakePinStore = FakePinStore(), c: FakePinClock = clock): Pair<PinManager, FakePinStore> {
        val mgr = PinManager(store, StandInArgon2id(), c)
        mgr.setPin(correct)
        return mgr to store
    }

    /** A new process over [store] (a force-stop or a reboot), built as [quickManagerWithPin] builds its manager. */
    private fun restartedOver(store: PinStore, c: FakePinClock = clock): PinManager = PinManager(store, StandInArgon2id(), c)

    /** The time the running cooldown has left as the screens see it — when it ends, less the wall clock now — or 0. */
    private fun PinManager.timeLeft(c: FakePinClock = clock): Long = currentLockout().let { if (it == 0L) 0L else it - c.wall }

    // (a) cooldown schedule
    @Test fun cooldownSchedule() {
        assertEquals(0L, PinManager.cooldownMsForFailCount(1))
        assertEquals(0L, PinManager.cooldownMsForFailCount(2))
        assertEquals(0L, PinManager.cooldownMsForFailCount(3))
        assertEquals(60_000L, PinManager.cooldownMsForFailCount(4))
        assertEquals(300_000L, PinManager.cooldownMsForFailCount(5))
        assertEquals(1_800_000L, PinManager.cooldownMsForFailCount(6))
        assertEquals(3_600_000L, PinManager.cooldownMsForFailCount(7))
        assertEquals(3_600_000L, PinManager.cooldownMsForFailCount(25))
    }

    // (b) 3 wrong then Success resets the count
    @Test fun threeWrongThenSuccessResets() {
        val (mgr, store) = freshManagerWithPin()
        repeat(3) {
            val r = mgr.verifyPin(wrong)
            assertTrue(r is PinVerifyResult.Wrong)
            assertNull((r as PinVerifyResult.Wrong).lockedUntil) // free attempts, no cooldown
            clock.pass(1L)
        }
        assertEquals(3, store.getInt("pin_fail_count", -1))

        val ok = mgr.verifyPin(correct)
        assertTrue(ok is PinVerifyResult.Success)
        assertEquals(0, store.getInt("pin_fail_count", -1))
        assertEquals(0L, mgr.currentLockout())

        // Next wrong is treated as the FIRST failure again.
        clock.pass(10L)
        val r = mgr.verifyPin(wrong)
        assertEquals(1, (r as PinVerifyResult.Wrong).failCount)
    }

    // (c) 4th wrong -> Wrong with lockedUntil ~ now + 60_000
    @Test fun fourthWrongStartsOneMinuteLockout() {
        val (mgr, _) = freshManagerWithPin()
        repeat(3) { mgr.verifyPin(wrong) }
        val r = mgr.verifyPin(wrong)
        assertTrue(r is PinVerifyResult.Wrong)
        r as PinVerifyResult.Wrong
        assertEquals(4, r.failCount)
        assertEquals(t0 + 60_000L, r.lockedUntil)
    }

    // (d) a fresh PinManager instance still sees the persisted lockout (force-stop)
    @Test fun lockoutSurvivesForceStop() {
        val (mgr, store) = freshManagerWithPin()
        repeat(3) { mgr.verifyPin(wrong) }
        mgr.verifyPin(wrong) // 4th -> 60s lockout

        // Simulate force-stop: a brand-new manager over the SAME persisted store, in the same boot.
        clock.pass(30_000L)
        val mgr2 = PinManager(store, clock = clock)
        val r = mgr2.verifyPin(correct) // still within lockout window
        assertTrue(r is PinVerifyResult.LockedOut)
        assertEquals(t0 + 60_000L, (r as PinVerifyResult.LockedOut).until)
    }

    // (e) attempt during lockout returns LockedOut and does NOT increment
    @Test fun attemptDuringLockoutDoesNotIncrement() {
        val (mgr, store) = freshManagerWithPin()
        repeat(3) { mgr.verifyPin(wrong) }
        mgr.verifyPin(wrong) // 4th -> lockout, count == 4
        assertEquals(4, store.getInt("pin_fail_count", -1))

        clock.pass(10_000L)
        val during = mgr.verifyPin(wrong) // inside lockout
        assertTrue(during is PinVerifyResult.LockedOut)
        assertEquals(4, store.getInt("pin_fail_count", -1)) // unchanged

        // After the lockout expires, a wrong attempt is the 5th and escalates to 5 min.
        clock.pass(50_000L)
        val after = mgr.verifyPin(wrong)
        after as PinVerifyResult.Wrong
        assertEquals(5, after.failCount)
        assertEquals(t0 + 60_000L + 300_000L, after.lockedUntil)
    }

    // Correct PIN during a lockout must ALSO be rejected — proves the compare
    // never runs while locked (constant-time compare gated behind the lockout check).
    @Test fun correctPinDuringLockoutIsRejected() {
        val (mgr, _) = freshManagerWithPin()
        repeat(4) { mgr.verifyPin(wrong) }
        clock.pass(1_000L)
        val r = mgr.verifyPin(correct)
        assertTrue(r is PinVerifyResult.LockedOut)
    }

    // (g) wipe-toggle on + count >= threshold -> ShouldWipe
    @Test fun wipeThresholdReturnsShouldWipe() {
        val (mgr, store) = freshManagerWithPin()
        mgr.setWipeAfterN(true)
        // Let each cooldown run out so every attempt actually reaches the compare and
        // increments (an attempt inside a lockout would not).
        var last: PinVerifyResult = PinVerifyResult.Success
        repeat(PinManager.WIPE_THRESHOLD) {
            last = mgr.verifyPin(wrong)
            clock.pass(PinManager.MAX_COOLDOWN_MS + 1)
        }
        assertTrue(last is PinVerifyResult.ShouldWipe)
        assertTrue(mgr.isWipePending())
        assertEquals(PinManager.WIPE_THRESHOLD, store.getInt("pin_fail_count", -1))
    }

    // Wipe toggle OFF: reaching the threshold keeps escalating cooldowns, never wipes.
    @Test fun wipeDisabledNeverWipes() {
        val (mgr, _) = freshManagerWithPin()
        var last: PinVerifyResult = PinVerifyResult.Success
        repeat(PinManager.WIPE_THRESHOLD + 2) {
            last = mgr.verifyPin(wrong)
            clock.pass(PinManager.MAX_COOLDOWN_MS + 1)
        }
        assertFalse(last is PinVerifyResult.ShouldWipe)
        assertTrue(last is PinVerifyResult.Wrong)
        assertFalse(mgr.isWipePending())
    }

    // (h) correct-PIN path unaffected
    @Test fun correctPinSucceedsWithNoPriorFailures() {
        val (mgr, _) = freshManagerWithPin()
        assertTrue(mgr.verifyPin(correct) is PinVerifyResult.Success)
    }

    // Biometric success (onUnlockSuccess) clears a stale lockout.
    @Test fun onUnlockSuccessClearsLockout() {
        val (mgr, store) = freshManagerWithPin()
        repeat(4) { mgr.verifyPin(wrong) } // lockout set
        assertTrue(mgr.currentLockout() > 0L)

        mgr.onUnlockSuccess() // e.g. a fingerprint unlock
        assertEquals(0L, mgr.currentLockout())
        assertEquals(0, store.getInt("pin_fail_count", -1))

        // A subsequent PIN attempt is not locked out and is counted from scratch.
        clock.pass(1_000L)
        val r = mgr.verifyPin(wrong)
        assertEquals(1, (r as PinVerifyResult.Wrong).failCount)
    }

    // clearPin wipes counters + hash atomically (used by wallet wipe).
    @Test fun clearPinResetsEverything() {
        val (mgr, store) = freshManagerWithPin()
        mgr.setWipeAfterN(true)
        repeat(4) { mgr.verifyPin(wrong) }
        mgr.clearPin()
        assertFalse(mgr.hasPin())
        assertEquals(0, store.getInt("pin_fail_count", 0))
        assertEquals(0L, mgr.currentLockout())
        assertFalse(mgr.isWipeAfterNEnabled())
    }

    // setPin starts from a clean rate-limit slate (a change-PIN clears a stale lockout).
    @Test fun setPinResetsRateLimit() {
        val (mgr, _) = freshManagerWithPin()
        repeat(4) { mgr.verifyPin(wrong) }
        assertTrue(mgr.currentLockout() > 0L)
        mgr.setPin("654321")
        assertEquals(0L, mgr.currentLockout())
        clock.pass(1L)
        assertTrue(mgr.verifyPin("654321") is PinVerifyResult.Success)
    }

    // ── The cooldown runs on a clock the owner cannot set ────────────────────────────────────
    //
    // Setting [FakePinClock.wall] alone is the owner changing the date in Settings: no time passes.
    // A cooldown must neither end nor lengthen, and none may start. (The first four were red against
    // the manager that kept the cooldown on the wall clock.)

    @Test fun `moving the wall clock forward does not end a cooldown`() {
        val (mgr, store) = freshManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // the 4th starts 1 min
        for (jump in listOf(60_001L, PinManager.MAX_COOLDOWN_MS, YEAR)) {
            clock.wall += jump
            val r = mgr.verifyPin(correct)
            assertTrue("wall clock +$jump ms, no time passed: expected the cooldown, got $r", r is PinVerifyResult.LockedOut)
            assertEquals("the time left moved with the wall clock", 60_000L, (r as PinVerifyResult.LockedOut).until - clock.wall)
        }
        assertEquals(PinManager.FREE_ATTEMPTS + 1, store.getInt("pin_fail_count", -1))
        // Only the minute passing ends it.
        clock.pass(59_999L)
        assertTrue(mgr.verifyPin(correct) is PinVerifyResult.LockedOut)
        clock.pass(1L)
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct))
    }

    @Test fun `a cooldown survives a process restart when the wall clock has moved forward`() {
        val (mgr, store) = freshManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) }
        clock.wall += 60_001L
        val restarted = PinManager(store, clock = clock)
        val r = restarted.verifyPin(correct)
        assertTrue("expected the cooldown after the restart, got $r", r is PinVerifyResult.LockedOut)
        // In the same boot a restart loses nothing: the cooldown runs on from where it was.
        clock.pass(30_000L)
        assertEquals(30_000L, PinManager(store, clock = clock).timeLeft())
        clock.pass(30_000L)
        assertSame(PinVerifyResult.Success, PinManager(store, clock = clock).verifyPin(correct))
    }

    @Test fun `moving the wall clock back does not lengthen a cooldown`() {
        val (mgr, _) = freshManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min
        clock.wall -= DAY
        val r = mgr.verifyPin(correct)
        assertTrue("expected the cooldown, got $r", r is PinVerifyResult.LockedOut)
        assertEquals("the time left is still the one minute", 60_000L, (r as PinVerifyResult.LockedOut).until - clock.wall)
        clock.pass(60_000L)
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct))
    }

    @Test fun `moving the wall clock back does not start a cooldown`() {
        val (mgr, _) = freshManagerWithPin()
        mgr.verifyPin(wrong) // one free attempt used, no cooldown
        clock.wall -= 5_000L
        assertSame("a 5 s correction of the wall clock started a cooldown", PinVerifyResult.Success, mgr.verifyPin(correct))
    }

    @Test fun `the time left counts down only as time passes, whatever the wall clock does`() {
        val (mgr, _) = quickManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min
        var left = 60_000L
        for (move in listOf(DAY, -2 * DAY, 3_600_000L, -1L, YEAR)) {
            clock.pass(10_000L); left -= 10_000L
            clock.wall += move
            assertEquals("after the wall clock moved $move ms", left, mgr.timeLeft())
        }
        clock.pass(left - 1)
        assertTrue(mgr.verifyPin(correct) is PinVerifyResult.LockedOut)
        clock.pass(1L)
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct))
    }

    // ── A reboot: the monotonic clock starts again from zero ─────────────────────────────────
    //
    // In a new boot the monotonic clock reads the time the device has run since it started: real time
    // no one can move, and it counts. The time from the last reading through the shutdown cannot be
    // known without the wall clock and does not count. So a reboot never takes more off a cooldown than
    // the time since boot. Each case below was red against the rule that counted nothing across a
    // reboot, except the one marked GUARD, which holds under both.

    @Test fun `a reboot counts the time the new boot has run and nothing from before it`() {
        val (mgr, store) = quickManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min
        clock.pass(20_000L)
        assertEquals(40_000L, mgr.timeLeft()) // read, as the unlock screen does on entry

        clock.reboot(offMs = 30_000L, upMs = 25_000L)
        val afterReboot = restartedOver(store)
        assertEquals("the time left after the reboot: 40 s less the 25 s the new boot has run", 15_000L, afterReboot.timeLeft())
        val r = afterReboot.verifyPin(correct)
        assertTrue("expected the cooldown after the reboot, got $r", r is PinVerifyResult.LockedOut)
        assertEquals(PinManager.FREE_ATTEMPTS + 1, store.getInt("pin_fail_count", -1))

        clock.pass(14_999L)
        assertTrue(afterReboot.verifyPin(correct) is PinVerifyResult.LockedOut)
        clock.pass(1L)
        assertSame(PinVerifyResult.Success, afterReboot.verifyPin(correct))
        assertEquals("the right PIN clears the count", 0, store.getInt("pin_fail_count", -1))
    }

    @Test fun `a reboot takes off exactly the time the new boot has run however the clocks read after it`() {
        // The cooldown is measured 2 min into a boot, so the new boot's clock can read less than,
        // the same as, or more than it did then.
        val measuredAt = 120_000L
        for (upMs in listOf(0L, 1L, 25_000L, 39_999L, 40_000L, measuredAt - 1, measuredAt, measuredAt + 1, 600_000L, 30L * DAY)) {
            for (move in listOf(0L, YEAR, -YEAR)) {
                val c = FakePinClock(wall = t0, sinceBoot = measuredAt - 20_000L)
                val (mgr, store) = quickManagerWithPin(c = c)
                repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min
                c.pass(20_000L)
                assertEquals(40_000L, mgr.timeLeft(c))

                c.reboot(upMs = upMs)
                c.wall += move
                val r = restartedOver(store, c).verifyPin(correct)
                val case = "new boot up $upMs ms, wall clock moved $move ms"
                val left = (40_000L - upMs).coerceAtLeast(0L)
                if (left > 0L) {
                    assertTrue("$case: expected the cooldown, got $r", r is PinVerifyResult.LockedOut)
                    assertEquals("$case: the time left", left, (r as PinVerifyResult.LockedOut).until - c.wall)
                } else {
                    assertSame("$case: the cooldown ran out in the new boot", PinVerifyResult.Success, r)
                }
            }
        }
    }

    @Test fun `where the boot count cannot be read a reboot never takes off more than the time since boot`() {
        // Without the boot count a reboot is recognised only by the monotonic clock reading less than it
        // did; then the new boot's whole uptime counts. A new boot whose clock already reads past the last
        // measurement is taken for the same boot, and only the time past that reading counts: less than
        // the time since boot, never more.
        val measuredAt = 120_000L
        for (upMs in listOf(1L, 25_000L, 59_999L, 60_000L, measuredAt - 1, measuredAt, measuredAt + 1, 600_000L, 30L * DAY)) {
            val c = FakePinClock(wall = t0, sinceBoot = measuredAt, boot = PinClock.UNKNOWN_BOOT)
            val (mgr, store) = quickManagerWithPin(c = c)
            repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min, measured at 2 min up
            c.reboot(upMs = upMs)
            val left = restartedOver(store, c).timeLeft(c)
            val counted = if (upMs < measuredAt) upMs else upMs - measuredAt
            assertEquals("new boot up $upMs ms", (60_000L - counted).coerceAtLeast(0L), left)
            assertTrue("new boot up $upMs ms: more taken off than the time since boot", 60_000L - left <= upMs)
        }
    }

    @Test fun `repeated reboots take off only the time each new boot has run`() {
        val (mgr, store) = quickManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min
        var left = 60_000L
        repeat(10) { i ->
            clock.reboot(offMs = 3_600_000L, upMs = 1_000L + i)
            clock.wall += if (i % 2 == 0) YEAR else -YEAR
            left -= 1_000L + i
            val r = restartedOver(store).verifyPin(correct)
            assertTrue("reboot ${i + 1}: expected the cooldown, got $r", r is PinVerifyResult.LockedOut)
            assertEquals("reboot ${i + 1}: the time left", left, (r as PinVerifyResult.LockedOut).until - clock.wall)
        }
        clock.pass(left - 1)
        assertTrue(restartedOver(store).verifyPin(correct) is PinVerifyResult.LockedOut)
        clock.pass(1L)
        assertSame(PinVerifyResult.Success, restartedOver(store).verifyPin(correct))
    }

    @Test fun `GUARD a cooldown read as run out stays ended after a reboot`() {
        val (mgr, store) = quickManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) }
        clock.pass(60_000L)
        assertEquals(0L, mgr.currentLockout())
        clock.reboot()
        assertEquals(0L, restartedOver(store).currentLockout())
        assertSame(PinVerifyResult.Success, restartedOver(store).verifyPin(correct))
    }

    // What a reboot still costs, stated: time that passed after the last reading and before the reboot is
    // not known, so a cooldown that ran out while nothing read it comes back with the time it had when last
    // read, less the time the new boot has run.
    @Test fun `a cooldown not read again before a reboot carries over its last reading less the new boot's uptime`() {
        val (mgr, store) = quickManagerWithPin()
        repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // last read as it started
        clock.pass(60_000L) // it runs out, but nothing reads it
        clock.reboot(upMs = 25_000L)
        assertEquals(35_000L, restartedOver(store).timeLeft())
    }

    @Test fun `failures count the same across reboots and wall-clock moves and wipe-after-N trips at the threshold`() {
        val (mgr, store) = quickManagerWithPin()
        mgr.setWipeAfterN(true)
        var last: PinVerifyResult = PinVerifyResult.Success
        for (n in 1..PinManager.WIPE_THRESHOLD) {
            last = restartedOver(store).verifyPin(wrong)
            if (n == PinManager.WIPE_THRESHOLD) break
            assertEquals("failure $n", n, (last as PinVerifyResult.Wrong).failCount)
            clock.reboot(upMs = 25_000L)
            clock.wall -= DAY
            // The new boot's 25 s count; the rest runs out on the monotonic clock in the new boot.
            val left = (PinManager.cooldownMsForFailCount(n) - 25_000L).coerceAtLeast(0L)
            assertEquals("failure $n", left, restartedOver(store).timeLeft())
            clock.pass(left)
        }
        assertSame(PinVerifyResult.ShouldWipe, last)
        assertTrue(mgr.isWipePending())
        assertEquals(PinManager.WIPE_THRESHOLD, store.getInt("pin_fail_count", -1))
    }

    // ── A process stopped part-way through a write ───────────────────────────────────────────

    /** A [PinStore] over [inner] that stops, as a killed process does, at write number [stopAt] (from 0):
     *  that write and every later one do not land. */
    private class StoppingStore(val inner: FakePinStore, val stopAt: Int) : PinStore by inner {
        class Stopped : RuntimeException("stand-in: the process stopped here")
        private var writes = 0
        private fun write() { if (writes++ == stopAt) throw Stopped() }
        override fun putInt(key: String, value: Int) { write(); inner.putInt(key, value) }
        override fun putLong(key: String, value: Long) { write(); inner.putLong(key, value) }
        override fun putString(key: String, value: String) { write(); inner.putString(key, value) }
        override fun putBoolean(key: String, value: Boolean) { write(); inner.putBoolean(key, value) }
        override fun remove(key: String) { write(); inner.remove(key) }
        override fun clear() { write(); inner.clear() }
        override fun commit(edit: PinStoreEdit) { write(); inner.commit(edit) } // one write: all of it lands, or none
    }

    /** Runs [entry] over a store that stops at write [stopAt]; true if it stopped there. */
    private fun stoppedAt(stopAt: Int, store: FakePinStore, entry: (PinStore) -> Unit): Boolean = try {
        entry(StoppingStore(store, stopAt))
        false
    } catch (e: StoppingStore.Stopped) {
        true // the process is gone; the next one reads what was written
    }

    @Test fun `a failure that is counted always has its cooldown`() {
        // The 4th wrong PIN counts a failure and starts the 1-minute cooldown. Wherever the process stops
        // while that is stored, the failure is either not counted at all, or counted with its cooldown.
        for (stopAt in 0..3) {
            val c = FakePinClock(wall = t0)
            val (mgr, store) = quickManagerWithPin(c = c)
            repeat(PinManager.FREE_ATTEMPTS) { mgr.verifyPin(wrong) }
            stoppedAt(stopAt, store) { restartedOver(it, c).verifyPin(wrong) }
            val count = store.getInt("pin_fail_count", -1)
            val left = restartedOver(store, c).timeLeft(c)
            assertTrue(
                "stopped at write $stopAt: $count failures counted with $left ms of cooldown",
                (count == PinManager.FREE_ATTEMPTS && left == 0L) || (count == PinManager.FREE_ATTEMPTS + 1 && left == 60_000L),
            )
        }
    }

    @Test fun `a failure that reaches wipe-after-N is counted with the wipe it owes`() {
        for (stopAt in 0..3) {
            val c = FakePinClock(wall = t0)
            val (mgr, store) = quickManagerWithPin(c = c)
            mgr.setWipeAfterN(true)
            repeat(PinManager.WIPE_THRESHOLD - 1) {
                mgr.verifyPin(wrong)
                c.pass(PinManager.MAX_COOLDOWN_MS)
                assertEquals(0L, mgr.currentLockout()) // read as run out, so the next entry starts from nothing
            }
            stoppedAt(stopAt, store) { restartedOver(it, c).verifyPin(wrong) }
            val count = store.getInt("pin_fail_count", -1)
            val owed = restartedOver(store, c).isWipePending()
            assertTrue(
                "stopped at write $stopAt: $count failures counted, wipe owed $owed",
                (count == PinManager.WIPE_THRESHOLD - 1 && !owed) || (count == PinManager.WIPE_THRESHOLD && owed),
            )
        }
    }

    // A reading records its measurement in one write: a process stopped there leaves the measurement
    // before it, and the next reading loses nothing either way.
    @Test fun `a process stopped while recording a measurement loses nothing`() {
        for (rebootFirst in listOf(false, true)) {
            for (stopAt in 0..1) {
                val case = "reboot first $rebootFirst, stopped at write $stopAt"
                val c = FakePinClock(wall = t0)
                val (mgr, store) = quickManagerWithPin(c = c)
                repeat(PinManager.FREE_ATTEMPTS + 1) { mgr.verifyPin(wrong) } // 1 min
                c.pass(20_000L)
                if (rebootFirst) c.reboot(upMs = 25_000L)
                val expected = if (rebootFirst) 35_000L else 40_000L // read 25 s into the new boot
                val stopped = stoppedAt(stopAt, store) { restartedOver(it, c).currentLockout() }
                assertEquals("$case: a reading is one write", stopAt == 0, stopped)
                c.pass(5_000L)
                assertEquals(case, expected - 5_000L, restartedOver(store, c).timeLeft(c))
            }
        }
    }

    // ── A cooldown kept on the wall clock by 4.0.89 and earlier ──────────────────────────────

    @Test fun `a running cooldown kept on the wall clock by an earlier version is carried over once`() {
        val (mgr, store) = quickManagerWithPin()
        store.putInt("pin_fail_count", 4)
        store.putLong("pin_lockout_until", clock.wall + 45_000L)
        store.putLong("pin_last_fail_at", clock.wall - 15_000L)

        assertEquals(45_000L, mgr.timeLeft())
        assertFalse("pin_lockout_until" in store.map)
        assertFalse("pin_last_fail_at" in store.map)
        // From here on it runs on the monotonic clock.
        clock.wall += YEAR
        assertEquals(45_000L, mgr.timeLeft())
        clock.pass(44_999L)
        assertTrue(mgr.verifyPin(correct) is PinVerifyResult.LockedOut)
        clock.pass(1L)
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct))
    }

    @Test fun `a wall-clock cooldown is carried over with at most the longest cooldown and not at all once run out`() {
        for ((endsIn, carried) in listOf(-DAY to 0L, -1L to 0L, 0L to 0L, 1L to 1L, 10L * DAY to PinManager.MAX_COOLDOWN_MS)) {
            val c = FakePinClock(wall = t0)
            val (mgr, store) = quickManagerWithPin(c = c)
            store.putInt("pin_fail_count", 7)
            store.putLong("pin_lockout_until", c.wall + endsIn)
            store.putLong("pin_last_fail_at", c.wall - 1L)
            assertEquals("ends in $endsIn ms", carried, mgr.timeLeft(c))
            assertFalse("ends in $endsIn ms: the wall-clock deadline was kept", "pin_lockout_until" in store.map)
            assertEquals("ends in $endsIn ms: the count", 7, store.getInt("pin_fail_count", -1))
        }
    }

    // ── A PIN the hashing library could not check is not a wrong PIN ──────────
    //
    // The record below is the one a device writes for the PIN [correct] when its Argon2 library
    // works: argon2id v1.3, t=3, m=65536 KiB, p=4, 32-byte output, over the salt 00 01 .. 1f. The
    // hash was produced with a second, independent argon2id implementation (Bouncy Castle
    // Argon2BytesGenerator, which gives the reference vector for "password"/"somesalt", t=2,
    // m=65536, p=1: 09316115...3cf7). Off-device the Argon2 native library cannot load, so checking
    // any entry against this record runs the real path on which the hashing library fails, with
    // nothing faked: the entry is the right PIN, and it cannot be checked.

    private val argon2SaltHex = (0 until 32).joinToString("") { "%02x".format(it) }
    private val argon2HashOfCorrectHex = "67bb8151856fb9a188bc5b66d1e0fac51da69417b7860a7dfbbb644b769dfe32"

    /** A manager over a store holding the argon2id record of [correct], with [failCount] earlier failures.
     *  The store also holds the zeroed wall-clock keys a store kept by 4.0.89 has. */
    private fun managerWithArgon2Record(failCount: Int = 0, lastFailAt: Long = 0L, wipeAfterN: Boolean = false): Pair<PinManager, FakePinStore> {
        val store = FakePinStore()
        store.putString("pin_hash", argon2HashOfCorrectHex)
        store.putString("pin_salt", argon2SaltHex)
        store.putString("pin_method", "argon2id")
        store.putInt("pin_fail_count", failCount)
        store.putLong("pin_lockout_until", 0L)
        store.putLong("pin_last_fail_at", lastFailAt)
        if (wipeAfterN) store.putBoolean("pin_wipe_after_n", true)
        return PinManager(store, clock = clock) to store
    }

    @Test fun `the right PIN that cannot be checked is not a wrong PIN and nothing is counted`() {
        val (mgr, store) = managerWithArgon2Record()
        val before = HashMap(store.map)
        val r = mgr.verifyPin(correct)
        assertSame(PinVerifyResult.Unavailable, r)
        assertFalse("a PIN that could not be checked was reported as wrong: $r", r is PinVerifyResult.Wrong)
        assertFalse("a PIN that could not be checked started a wipe: $r", r is PinVerifyResult.ShouldWipe)
        assertEquals("the PIN store changed", before, store.map)
        assertEquals("the failure counter moved", 0, store.getInt("pin_fail_count", -1))
        assertEquals("a cooldown was armed", 0L, mgr.currentLockout())
        assertTrue("the PIN record itself must stay", mgr.hasPin())
    }

    @Test fun `a PIN that cannot be checked never arms a cooldown after the free attempts are used`() {
        // Three earlier genuine failures: the next COUNTED failure would arm the first cooldown.
        val (mgr, store) = managerWithArgon2Record(failCount = PinManager.FREE_ATTEMPTS, lastFailAt = t0 - 1)
        val before = HashMap(store.map)
        repeat(5) { i ->
            val r = mgr.verifyPin(correct)
            assertFalse("entry $i was reported as wrong: $r", r is PinVerifyResult.Wrong)
            assertFalse("entry $i was reported as locked out: $r", r is PinVerifyResult.LockedOut)
            assertSame(PinVerifyResult.Unavailable, r)
            clock.pass(1L)
        }
        assertEquals("the PIN store changed", before, store.map)
        assertEquals("the failure counter moved", PinManager.FREE_ATTEMPTS, store.getInt("pin_fail_count", -1))
        assertEquals("a cooldown was armed", 0L, mgr.currentLockout())
    }

    @Test fun `a PIN that cannot be checked never advances wipe-after-N`() {
        // Wipe-after-N on, one counted failure short of the threshold.
        val (mgr, store) = managerWithArgon2Record(
            failCount = PinManager.WIPE_THRESHOLD - 1, lastFailAt = t0 - 1, wipeAfterN = true,
        )
        val before = HashMap(store.map)
        repeat(PinManager.WIPE_THRESHOLD + 2) { i ->
            val r = mgr.verifyPin(correct)
            assertFalse("entry $i asked for a wipe: $r", r is PinVerifyResult.ShouldWipe)
            assertFalse("entry $i was reported as wrong: $r", r is PinVerifyResult.Wrong)
            assertSame(PinVerifyResult.Unavailable, r)
            clock.pass(1L)
        }
        assertEquals("the PIN store changed", before, store.map)
        assertFalse("a wipe is owed", mgr.isWipePending())
        assertEquals("the failure counter moved", PinManager.WIPE_THRESHOLD - 1, store.getInt("pin_fail_count", -1))
        assertTrue("the PIN record itself must stay", mgr.hasPin())
    }

    // GUARD (passes before and after): the lockout check still runs before the PIN is looked at,
    // so a record the library cannot check does not shorten or skip a cooldown already running.
    @Test fun `GUARD a running cooldown still answers first for a record that cannot be checked`() {
        val (mgr, store) = managerWithArgon2Record(failCount = 4, lastFailAt = t0)
        store.putLong("pin_cooldown_measured_at", clock.sinceBoot)
        store.putInt("pin_cooldown_boot", clock.boot)
        store.putLong("pin_cooldown_left_ms", 60_000L)
        clock.pass(1_000L)
        val r = mgr.verifyPin(correct)
        assertTrue("expected the running cooldown, got $r", r is PinVerifyResult.LockedOut)
        assertEquals(t0 + 60_000L, (r as PinVerifyResult.LockedOut).until)
        assertEquals(4, store.getInt("pin_fail_count", -1))
    }

    // ── An argon2id record through a stand-in for the hash call ────────────────
    //
    // The Argon2 library cannot run off-device, so the tests above only ever see it fail. These
    // put a stand-in in place of the one Argon2id call (the seam [PinManager] is built with) to
    // show the other half: an argon2id record whose hash DOES run counts a wrong PIN exactly like a
    // PBKDF2 record does, and the same record answers Unavailable, counting nothing, only when the
    // hash itself throws. The stand-in is not Argon2id; it only has to be a deterministic function
    // of the PIN and the salt, which is all [PinManager] relies on.

    /** Stands in for the Argon2id call. While [runs] is false it throws [failure], as the library does. */
    private class StandInArgon2id : Argon2idHash {
        var runs = true
        var failure: Throwable = OutOfMemoryError("stand-in: the hash could not get its memory")
        var calls = 0
        override fun hash(pin: ByteArray, salt: ByteArray): ByteArray {
            calls++
            if (!runs) throw failure
            return MessageDigest.getInstance("SHA-256").run { update(salt); update(pin); digest() }
        }
    }

    /** A manager whose PIN [correct] was set through [hash], so the record is an argon2id one. */
    private fun managerWithStandIn(hash: StandInArgon2id = StandInArgon2id()): Triple<PinManager, FakePinStore, StandInArgon2id> {
        val store = FakePinStore()
        val mgr = PinManager(store, hash, clock)
        mgr.setPin(correct)
        assertEquals("the record under test is an argon2id record", "argon2id", store.getString("pin_method"))
        return Triple(mgr, store, hash)
    }

    @Test fun `a wrong PIN against an argon2id record is counted like any wrong PIN`() {
        val (mgr, store, hash) = managerWithStandIn()
        repeat(PinManager.FREE_ATTEMPTS) { i ->
            val r = mgr.verifyPin(wrong)
            assertEquals("entry $i", PinVerifyResult.Wrong(i + 1, null), r)
            clock.pass(1L)
        }
        val fourth = mgr.verifyPin(wrong)
        assertEquals(PinVerifyResult.Wrong(PinManager.FREE_ATTEMPTS + 1, clock.wall + 60_000L), fourth)
        assertEquals(PinManager.FREE_ATTEMPTS + 1, store.getInt("pin_fail_count", -1))
        assertEquals(clock.wall + 60_000L, mgr.currentLockout())
        assertEquals("every entry went through the hash", 1 + PinManager.FREE_ATTEMPTS + 1, hash.calls)

        // After the cooldown the right PIN opens the record and clears the count.
        clock.pass(60_000L)
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct))
        assertEquals(0, store.getInt("pin_fail_count", -1))
    }

    @Test fun `wrong PINs against an argon2id record still reach wipe-after-N`() {
        val (mgr, store, _) = managerWithStandIn()
        mgr.setWipeAfterN(true)
        var last: PinVerifyResult = PinVerifyResult.Success
        repeat(PinManager.WIPE_THRESHOLD) {
            last = mgr.verifyPin(wrong)
            clock.pass(PinManager.MAX_COOLDOWN_MS + 1)
        }
        assertSame(PinVerifyResult.ShouldWipe, last)
        assertTrue(mgr.isWipePending())
        assertEquals(PinManager.WIPE_THRESHOLD, store.getInt("pin_fail_count", -1))
    }

    @Test fun `an argon2id record whose hash throws answers unavailable for any entry and counts nothing`() {
        val failures = listOf(
            OutOfMemoryError("stand-in"),
            UnsatisfiedLinkError("stand-in"),
            ExceptionInInitializerError("stand-in"),
            IllegalStateException("stand-in"),
        )
        for (failure in failures) {
            val (mgr, store, hash) = managerWithStandIn()
            // Two genuine, counted failures first.
            mgr.verifyPin(wrong)
            clock.pass(1L)
            mgr.verifyPin(wrong)
            assertEquals(2, store.getInt("pin_fail_count", -1))

            hash.runs = false
            hash.failure = failure
            val before = HashMap(store.map)
            for ((i, entry) in listOf(correct, wrong, correct, wrong, wrong).withIndex()) {
                clock.pass(1L)
                val r = mgr.verifyPin(entry)
                assertSame("${failure.javaClass.simpleName}, entry $i", PinVerifyResult.Unavailable, r)
            }
            assertEquals("${failure.javaClass.simpleName}: the PIN store changed", before, store.map)
            assertEquals("${failure.javaClass.simpleName}: the failure counter moved", 2, store.getInt("pin_fail_count", -1))
            assertEquals("${failure.javaClass.simpleName}: a cooldown was armed", 0L, mgr.currentLockout())
            assertFalse("${failure.javaClass.simpleName}: a wipe is owed", mgr.isWipePending())
            assertEquals("${failure.javaClass.simpleName}: the record changed", "argon2id", store.getString("pin_method"))

            // Once the hash runs again the count carries on from where the counted entries left it.
            hash.runs = true
            clock.pass(100L)
            assertEquals(PinVerifyResult.Wrong(3, null), mgr.verifyPin(wrong))
        }
    }

    // GUARD (passes before and after): a hash that cannot run when the PIN is SET still leaves a
    // record the PIN opens — the PBKDF2 one, as before.
    @Test fun `GUARD a hash that cannot run at setup writes a PBKDF2 record the PIN opens`() {
        val hash = StandInArgon2id().apply { runs = false }
        val store = FakePinStore()
        val mgr = PinManager(store, hash, clock)
        mgr.setPin(correct)
        assertEquals("pbkdf2", store.getString("pin_method"))
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct))
        clock.pass(1L)
        assertEquals(PinVerifyResult.Wrong(1, null), mgr.verifyPin(wrong))
    }
}
