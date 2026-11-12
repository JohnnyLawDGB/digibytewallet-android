package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * A correct PIN whose unlock then stops at a cancelled device-lock prompt is not a wrong PIN.
 *
 * With the seed key auth-bound and outside its window, the unlock screen checks the PIN
 * ([PinManager.verifyPin] answers Success), marks the unlock ([PinManager.onUnlockSuccess]), and
 * only then meets the Keystore's demand for the device lock. Cancelling that prompt is the device's
 * business, not a PIN guess: the screen makes no further [PinManager] call on that path (gated in
 * the app's `UnlockRetryAfterCancelTest`). This shows what that sequence leaves in the rate limit,
 * however often it repeats — no count, no cooldown, no wipe — and that wrong PINs around it still
 * count as they always did.
 */
class PinCorrectThenDeviceLockCancelledTest {

    private class MapStore : PinStore {
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

    private object StandIn : Argon2idHash {
        override fun hash(pin: ByteArray, salt: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run { update(salt); update(pin); digest() }
    }

    private object StillClock : PinClock {
        override fun sinceBootMs(): Long = 7L * 86_400_000L
        override fun bootCount(): Int = 41
        override fun wallMs(): Long = 1_000_000_000_000L
    }

    private val correct = "258147"
    private val wrong = "000000"

    private fun manager(store: PinStore = MapStore()) = PinManager(store, StandIn, StillClock).apply {
        setPin(correct)
        setWipeAfterN(true)
    }

    /** What the unlock screen does with a correct PIN whose device-lock prompt is then cancelled. */
    private fun correctPinThenPromptCancelled(mgr: PinManager) {
        assertEquals(PinVerifyResult.Success, mgr.verifyPin(correct))
        mgr.onUnlockSuccess()
        // KeystoreUserAuthRequiredException -> prompt -> cancelled: no PinManager call.
    }

    @Test fun `repeated correct PIN with a cancelled prompt never counts, locks or wipes`() {
        val mgr = manager()
        repeat(PinManager.WIPE_THRESHOLD * 2) { correctPinThenPromptCancelled(mgr) }
        assertEquals(0L, mgr.currentLockout())
        assertFalse(mgr.isWipePending())
        assertEquals(PinVerifyResult.Success, mgr.verifyPin(correct))
    }

    @Test fun `the first wrong PIN after a cancelled prompt is the first`() {
        val mgr = manager()
        repeat(PinManager.FREE_ATTEMPTS) { correctPinThenPromptCancelled(mgr) }
        val r = mgr.verifyPin(wrong)
        assertTrue("a wrong PIN was not counted as one", r is PinVerifyResult.Wrong)
        assertEquals(1, (r as PinVerifyResult.Wrong).failCount)
        assertEquals(null, r.lockedUntil)
    }

    @Test fun `a cancelled prompt between wrong PINs neither adds to nor hides them`() {
        val mgr = manager()
        repeat(2) { mgr.verifyPin(wrong) }
        // The correct PIN resets the count as it always has; the cancelled prompt adds nothing.
        correctPinThenPromptCancelled(mgr)
        val r = mgr.verifyPin(wrong) as PinVerifyResult.Wrong
        assertEquals(1, r.failCount)
    }
}
