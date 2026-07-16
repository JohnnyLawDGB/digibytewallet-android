package io.digibyte.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the persisted PIN rate-limit. The store is faked
 * in-memory ([FakePinStore]) so no Robolectric / AndroidX Security is needed;
 * setPin/verifyPin exercise the real PBKDF2 path (the Argon2 native lib is
 * unavailable off-device, so [PinManager] transparently falls back — the
 * rate-limit logic under test is identical either way).
 *
 * A fresh [PinManager] constructed over the SAME [FakePinStore] simulates a
 * force-stop: the persisted lockout must survive.
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

    private val correct = "123456"
    private val wrong = "000000"
    private val t0 = 1_000_000_000_000L // fixed base clock

    private fun freshManagerWithPin(store: FakePinStore = FakePinStore()): Pair<PinManager, FakePinStore> {
        val mgr = PinManager(store)
        mgr.setPin(correct)
        return mgr to store
    }

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
        repeat(3) { i ->
            val r = mgr.verifyPin(wrong, nowMs = t0 + i)
            assertTrue(r is PinVerifyResult.Wrong)
            assertNull((r as PinVerifyResult.Wrong).lockedUntil) // free attempts, no cooldown
        }
        assertEquals(3, store.getInt("pin_fail_count", -1))

        val ok = mgr.verifyPin(correct, nowMs = t0 + 10)
        assertTrue(ok is PinVerifyResult.Success)
        assertEquals(0, store.getInt("pin_fail_count", -1))
        assertEquals(0L, store.getLong("pin_lockout_until", -1))

        // Next wrong is treated as the FIRST failure again.
        val r = mgr.verifyPin(wrong, nowMs = t0 + 20)
        assertEquals(1, (r as PinVerifyResult.Wrong).failCount)
    }

    // (c) 4th wrong -> Wrong with lockedUntil ~ now + 60_000
    @Test fun fourthWrongStartsOneMinuteLockout() {
        val (mgr, _) = freshManagerWithPin()
        repeat(3) { mgr.verifyPin(wrong, nowMs = t0) }
        val r = mgr.verifyPin(wrong, nowMs = t0)
        assertTrue(r is PinVerifyResult.Wrong)
        r as PinVerifyResult.Wrong
        assertEquals(4, r.failCount)
        assertEquals(t0 + 60_000L, r.lockedUntil)
    }

    // (d) a fresh PinManager instance still sees the persisted lockout (force-stop)
    @Test fun lockoutSurvivesForceStop() {
        val (mgr, store) = freshManagerWithPin()
        repeat(3) { mgr.verifyPin(wrong, nowMs = t0) }
        mgr.verifyPin(wrong, nowMs = t0) // 4th -> 60s lockout

        // Simulate force-stop: a brand-new manager over the SAME persisted store.
        val mgr2 = PinManager(store)
        val r = mgr2.verifyPin(correct, nowMs = t0 + 30_000L) // still within lockout window
        assertTrue(r is PinVerifyResult.LockedOut)
        assertEquals(t0 + 60_000L, (r as PinVerifyResult.LockedOut).until)
    }

    // (e) attempt during lockout returns LockedOut and does NOT increment
    @Test fun attemptDuringLockoutDoesNotIncrement() {
        val (mgr, store) = freshManagerWithPin()
        repeat(3) { mgr.verifyPin(wrong, nowMs = t0) }
        mgr.verifyPin(wrong, nowMs = t0) // 4th -> lockout, count == 4
        assertEquals(4, store.getInt("pin_fail_count", -1))

        val during = mgr.verifyPin(wrong, nowMs = t0 + 10_000L) // inside lockout
        assertTrue(during is PinVerifyResult.LockedOut)
        assertEquals(4, store.getInt("pin_fail_count", -1)) // unchanged

        // After the lockout expires, a wrong attempt is the 5th and escalates to 5 min.
        val after = mgr.verifyPin(wrong, nowMs = t0 + 60_000L)
        after as PinVerifyResult.Wrong
        assertEquals(5, after.failCount)
        assertEquals(t0 + 60_000L + 300_000L, after.lockedUntil)
    }

    // Correct PIN during a lockout must ALSO be rejected — proves the compare
    // never runs while locked (constant-time compare gated behind the lockout check).
    @Test fun correctPinDuringLockoutIsRejected() {
        val (mgr, _) = freshManagerWithPin()
        repeat(4) { mgr.verifyPin(wrong, nowMs = t0) }
        val r = mgr.verifyPin(correct, nowMs = t0 + 1_000L)
        assertTrue(r is PinVerifyResult.LockedOut)
    }

    // (f) backward nowMs -> LockedOut (clock guard)
    @Test fun backwardClockJumpForcesLockout() {
        val (mgr, _) = freshManagerWithPin()
        mgr.verifyPin(wrong, nowMs = t0) // records last_fail = t0
        val r = mgr.verifyPin(correct, nowMs = t0 - 5_000L) // clock moved backward
        assertTrue(r is PinVerifyResult.LockedOut)
        assertEquals((t0 - 5_000L) + PinManager.MAX_COOLDOWN_MS, (r as PinVerifyResult.LockedOut).until)
    }

    // (g) wipe-toggle on + count >= threshold -> ShouldWipe
    @Test fun wipeThresholdReturnsShouldWipe() {
        val (mgr, store) = freshManagerWithPin()
        mgr.setWipeAfterN(true)
        // Advance the clock past each cooldown so every attempt actually reaches
        // the compare and increments (an attempt inside a lockout would not).
        var now = t0
        var last: PinVerifyResult = PinVerifyResult.Success
        repeat(PinManager.WIPE_THRESHOLD) {
            last = mgr.verifyPin(wrong, nowMs = now)
            now += PinManager.MAX_COOLDOWN_MS + 1
        }
        assertTrue(last is PinVerifyResult.ShouldWipe)
        assertTrue(mgr.isWipePending())
        assertEquals(PinManager.WIPE_THRESHOLD, store.getInt("pin_fail_count", -1))
    }

    // Wipe toggle OFF: reaching the threshold keeps escalating cooldowns, never wipes.
    @Test fun wipeDisabledNeverWipes() {
        val (mgr, _) = freshManagerWithPin()
        var now = t0
        var last: PinVerifyResult = PinVerifyResult.Success
        repeat(PinManager.WIPE_THRESHOLD + 2) {
            last = mgr.verifyPin(wrong, nowMs = now)
            now += PinManager.MAX_COOLDOWN_MS + 1
        }
        assertFalse(last is PinVerifyResult.ShouldWipe)
        assertTrue(last is PinVerifyResult.Wrong)
        assertFalse(mgr.isWipePending())
    }

    // (h) correct-PIN path unaffected
    @Test fun correctPinSucceedsWithNoPriorFailures() {
        val (mgr, _) = freshManagerWithPin()
        assertTrue(mgr.verifyPin(correct, nowMs = t0) is PinVerifyResult.Success)
    }

    // Biometric success (onUnlockSuccess) clears a stale lockout.
    @Test fun onUnlockSuccessClearsLockout() {
        val (mgr, store) = freshManagerWithPin()
        repeat(4) { mgr.verifyPin(wrong, nowMs = t0) } // lockout set
        assertTrue(mgr.currentLockout() > 0L)

        mgr.onUnlockSuccess() // e.g. a fingerprint unlock
        assertEquals(0L, mgr.currentLockout())
        assertEquals(0, store.getInt("pin_fail_count", -1))

        // A subsequent PIN attempt is not locked out and is counted from scratch.
        val r = mgr.verifyPin(wrong, nowMs = t0 + 1_000L)
        assertEquals(1, (r as PinVerifyResult.Wrong).failCount)
    }

    // clearPin wipes counters + hash atomically (used by wallet wipe).
    @Test fun clearPinResetsEverything() {
        val (mgr, store) = freshManagerWithPin()
        mgr.setWipeAfterN(true)
        repeat(4) { mgr.verifyPin(wrong, nowMs = t0) }
        mgr.clearPin()
        assertFalse(mgr.hasPin())
        assertEquals(0, store.getInt("pin_fail_count", 0))
        assertEquals(0L, mgr.currentLockout())
        assertFalse(mgr.isWipeAfterNEnabled())
    }

    // setPin starts from a clean rate-limit slate (a change-PIN clears a stale lockout).
    @Test fun setPinResetsRateLimit() {
        val (mgr, _) = freshManagerWithPin()
        repeat(4) { mgr.verifyPin(wrong, nowMs = t0) }
        assertTrue(mgr.currentLockout() > 0L)
        mgr.setPin("654321")
        assertEquals(0L, mgr.currentLockout())
        assertTrue(mgr.verifyPin("654321", nowMs = t0 + 1) is PinVerifyResult.Success)
    }
}
