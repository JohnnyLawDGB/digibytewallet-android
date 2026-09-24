package io.digibyte.core.security

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

    /** A manager over a store holding the argon2id record of [correct], with [failCount] earlier failures. */
    private fun managerWithArgon2Record(failCount: Int = 0, lastFailAt: Long = 0L, wipeAfterN: Boolean = false): Pair<PinManager, FakePinStore> {
        val store = FakePinStore()
        store.putString("pin_hash", argon2HashOfCorrectHex)
        store.putString("pin_salt", argon2SaltHex)
        store.putString("pin_method", "argon2id")
        store.putInt("pin_fail_count", failCount)
        store.putLong("pin_lockout_until", 0L)
        store.putLong("pin_last_fail_at", lastFailAt)
        if (wipeAfterN) store.putBoolean("pin_wipe_after_n", true)
        return PinManager(store) to store
    }

    @Test fun `the right PIN that cannot be checked is not a wrong PIN and nothing is counted`() {
        val (mgr, store) = managerWithArgon2Record()
        val r = mgr.verifyPin(correct, nowMs = t0)
        assertSame(PinVerifyResult.Unavailable, r)
        assertFalse("a PIN that could not be checked was reported as wrong: $r", r is PinVerifyResult.Wrong)
        assertFalse("a PIN that could not be checked started a wipe: $r", r is PinVerifyResult.ShouldWipe)
        assertEquals("the failure counter moved", 0, store.getInt("pin_fail_count", -1))
        assertEquals("a cooldown was armed", 0L, store.getLong("pin_lockout_until", -1L))
        assertEquals("a failure time was recorded", 0L, store.getLong("pin_last_fail_at", -1L))
        assertTrue("the PIN record itself must stay", mgr.hasPin())
    }

    @Test fun `a PIN that cannot be checked never arms a cooldown after the free attempts are used`() {
        // Three earlier genuine failures: the next COUNTED failure would arm the first cooldown.
        val (mgr, store) = managerWithArgon2Record(failCount = PinManager.FREE_ATTEMPTS, lastFailAt = t0 - 1)
        repeat(5) { i ->
            val r = mgr.verifyPin(correct, nowMs = t0 + i)
            assertFalse("entry $i was reported as wrong: $r", r is PinVerifyResult.Wrong)
            assertFalse("entry $i was reported as locked out: $r", r is PinVerifyResult.LockedOut)
            assertSame(PinVerifyResult.Unavailable, r)
        }
        assertEquals("the failure counter moved", PinManager.FREE_ATTEMPTS, store.getInt("pin_fail_count", -1))
        assertEquals("a cooldown was armed", 0L, mgr.currentLockout())
        assertEquals("the last failure time moved", t0 - 1, store.getLong("pin_last_fail_at", -1L))
    }

    @Test fun `a PIN that cannot be checked never advances wipe-after-N`() {
        // Wipe-after-N on, one counted failure short of the threshold.
        val (mgr, store) = managerWithArgon2Record(
            failCount = PinManager.WIPE_THRESHOLD - 1, lastFailAt = t0 - 1, wipeAfterN = true,
        )
        repeat(PinManager.WIPE_THRESHOLD + 2) { i ->
            val r = mgr.verifyPin(correct, nowMs = t0 + i)
            assertFalse("entry $i asked for a wipe: $r", r is PinVerifyResult.ShouldWipe)
            assertFalse("entry $i was reported as wrong: $r", r is PinVerifyResult.Wrong)
            assertSame(PinVerifyResult.Unavailable, r)
        }
        assertFalse("a wipe is owed", mgr.isWipePending())
        assertEquals("the failure counter moved", PinManager.WIPE_THRESHOLD - 1, store.getInt("pin_fail_count", -1))
        assertTrue("the PIN record itself must stay", mgr.hasPin())
    }

    // GUARD (passes before and after): the lockout check still runs before the PIN is looked at,
    // so a record the library cannot check does not shorten or skip a cooldown already running.
    @Test fun `GUARD a running cooldown still answers first for a record that cannot be checked`() {
        val (mgr, store) = managerWithArgon2Record(failCount = 4, lastFailAt = t0)
        store.putLong("pin_lockout_until", t0 + 60_000L)
        val r = mgr.verifyPin(correct, nowMs = t0 + 1_000L)
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
        val mgr = PinManager(store, hash)
        mgr.setPin(correct)
        assertEquals("the record under test is an argon2id record", "argon2id", store.getString("pin_method"))
        return Triple(mgr, store, hash)
    }

    @Test fun `a wrong PIN against an argon2id record is counted like any wrong PIN`() {
        val (mgr, store, hash) = managerWithStandIn()
        repeat(PinManager.FREE_ATTEMPTS) { i ->
            val r = mgr.verifyPin(wrong, nowMs = t0 + i)
            assertEquals("entry $i", PinVerifyResult.Wrong(i + 1, null), r)
        }
        val fourth = mgr.verifyPin(wrong, nowMs = t0 + 10)
        assertEquals(PinVerifyResult.Wrong(PinManager.FREE_ATTEMPTS + 1, t0 + 10 + 60_000L), fourth)
        assertEquals(PinManager.FREE_ATTEMPTS + 1, store.getInt("pin_fail_count", -1))
        assertEquals(t0 + 10 + 60_000L, mgr.currentLockout())
        assertEquals("every entry went through the hash", 1 + PinManager.FREE_ATTEMPTS + 1, hash.calls)

        // After the cooldown the right PIN opens the record and clears the count.
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct, nowMs = t0 + 10 + 60_000L))
        assertEquals(0, store.getInt("pin_fail_count", -1))
    }

    @Test fun `wrong PINs against an argon2id record still reach wipe-after-N`() {
        val (mgr, store, _) = managerWithStandIn()
        mgr.setWipeAfterN(true)
        var now = t0
        var last: PinVerifyResult = PinVerifyResult.Success
        repeat(PinManager.WIPE_THRESHOLD) {
            last = mgr.verifyPin(wrong, nowMs = now)
            now += PinManager.MAX_COOLDOWN_MS + 1
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
            mgr.verifyPin(wrong, nowMs = t0)
            mgr.verifyPin(wrong, nowMs = t0 + 1)
            assertEquals(2, store.getInt("pin_fail_count", -1))

            hash.runs = false
            hash.failure = failure
            for ((i, entry) in listOf(correct, wrong, correct, wrong, wrong).withIndex()) {
                val r = mgr.verifyPin(entry, nowMs = t0 + 10 + i)
                assertSame("${failure.javaClass.simpleName}, entry $i", PinVerifyResult.Unavailable, r)
            }
            assertEquals("${failure.javaClass.simpleName}: the failure counter moved", 2, store.getInt("pin_fail_count", -1))
            assertEquals("${failure.javaClass.simpleName}: a cooldown was armed", 0L, mgr.currentLockout())
            assertEquals("${failure.javaClass.simpleName}: the last failure time moved", t0 + 1, store.getLong("pin_last_fail_at", -1L))
            assertFalse("${failure.javaClass.simpleName}: a wipe is owed", mgr.isWipePending())
            assertEquals("${failure.javaClass.simpleName}: the record changed", "argon2id", store.getString("pin_method"))

            // Once the hash runs again the count carries on from where the counted entries left it.
            hash.runs = true
            assertEquals(PinVerifyResult.Wrong(3, null), mgr.verifyPin(wrong, nowMs = t0 + 100))
        }
    }

    // GUARD (passes before and after): a hash that cannot run when the PIN is SET still leaves a
    // record the PIN opens — the PBKDF2 one, as before.
    @Test fun `GUARD a hash that cannot run at setup writes a PBKDF2 record the PIN opens`() {
        val hash = StandInArgon2id().apply { runs = false }
        val store = FakePinStore()
        val mgr = PinManager(store, hash)
        mgr.setPin(correct)
        assertEquals("pbkdf2", store.getString("pin_method"))
        assertSame(PinVerifyResult.Success, mgr.verifyPin(correct, nowMs = t0))
        assertEquals(PinVerifyResult.Wrong(1, null), mgr.verifyPin(wrong, nowMs = t0 + 1))
    }
}
