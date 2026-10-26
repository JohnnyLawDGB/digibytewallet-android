package app.aroundtheblock.wallet.ui.navigation

import app.aroundtheblock.wallet.core.WalletState
import app.aroundtheblock.wallet.core.db.entity.WalletConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [shouldAutoLock] — the in-foreground inactivity lock.
 *
 * ## Why this gate exists
 *
 * Settings → Security offered a 1/5/15/30-minute auto-lock timeout that was stored in
 * `WalletConfigEntity.autoLockTimeoutMs` and read by NOTHING (2026-08-30 external audit).
 * A phone left face-up and unlocked on a table stayed on the wallet graph indefinitely; the
 * only lock was `MainActivity.onStop()`. The polling loop in MainActivity is the half that
 * needs a device; this function is the half that can be proven on the JVM.
 *
 * Two integration holes found in review live here as well, because the first cut kept them
 * outside the tested function:
 *  - nothing seeds the `wallet_config` row, so a fresh create/restore that never opens
 *    Settings → Security has `walletConfigDao.get() == null`; skipping the tick there meant
 *    the lock never fired for the common case while the screen displayed "1 minute".
 *  - the wallet is Unlocked with NO PIN on `pin_setup` (fresh restore, lost-PIN restore);
 *    locking there flips state to Locked, `shouldRouteToUnlock` correctly refuses to route,
 *    and PinSetupScreen then lands on the wallet graph under Locked — reopening the
 *    warm-resume hole the base commit closed.
 */
class AutoLockPolicyTest {

    private val timeout = 60_000L

    @Test
    fun `unlocked and idle past the timeout locks`() {
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 1_000L, unlockedAtMs = 0L, nowMs = 61_000L, timeoutMs = timeout))
    }

    @Test
    fun `unlocked and idle exactly at the timeout locks`() {
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = timeout, timeoutMs = timeout))
    }

    @Test
    fun `unlocked but still within the timeout does not lock`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 1_000L, unlockedAtMs = 0L, nowMs = 60_999L, timeoutMs = timeout))
    }

    @Test
    fun `locked wallet never re-locks`() {
        assertFalse(shouldAutoLock(WalletState.Locked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `no wallet never locks`() {
        assertFalse(shouldAutoLock(WalletState.NoWallet, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `zero timeout means the inactivity lock is disabled`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = Long.MAX_VALUE / 2, timeoutMs = 0L))
    }

    @Test
    fun `negative timeout means the inactivity lock is disabled`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = Long.MAX_VALUE / 2, timeoutMs = -1L))
    }

    @Test
    fun `backwards clock counts as no elapsed time`() {
        // now < last must not wrap into a huge positive elapsed and lock instantly.
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 500_000L, unlockedAtMs = 0L, nowMs = 1_000L, timeoutMs = timeout))
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = Long.MAX_VALUE, unlockedAtMs = 0L, nowMs = Long.MIN_VALUE + 1, timeoutMs = timeout))
    }

    @Test
    fun `unlocked with no PIN never locks — there is nothing to unlock with`() {
        // pin_setup after a restore, or the lost-PIN restore-from-disk: Unlocked, hasPin=false.
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = false, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `missing wallet_config row falls back to the entity default, not to never-lock`() {
        // Nothing seeds the row on create/restore; the Security screen shows the entity
        // default, so the lock must enforce that same default.
        val default = WalletConfigEntity().autoLockTimeoutMs
        assertEquals(default, autoLockTimeoutOrDefault(null))
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = default, timeoutMs = autoLockTimeoutOrDefault(null)))
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 0L, nowMs = default - 1, timeoutMs = autoLockTimeoutOrDefault(null)))
    }

    @Test
    fun `present wallet_config row wins over the default`() {
        assertEquals(300_000L, autoLockTimeoutOrDefault(300_000L))
        assertEquals(0L, autoLockTimeoutOrDefault(0L))
    }

    /*
     * 4.1.2 Wave-10 (Moto, 2026-10-25): a fingerprint unlock touches nothing in the activity, so
     * onUserInteraction never stamped and the idle clock still ran from process start. Every tick
     * after the unlock read "idle 205s >= 60s", locked, and the unlock screen re-prompted: an
     * endless sign-on loop. Becoming Unlocked is itself activity.
     */
    @Test
    fun `an unlock with no touch restarts the idle clock`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 200_000L, nowMs = 205_000L, timeoutMs = timeout))
    }

    @Test
    fun `idle past the timeout since the unlock still locks`() {
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 200_000L, nowMs = 260_000L, timeoutMs = timeout))
    }

    @Test
    fun `a touch after the unlock is the later activity`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 250_000L, unlockedAtMs = 200_000L, nowMs = 300_000L, timeoutMs = timeout))
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 250_000L, unlockedAtMs = 200_000L, nowMs = 310_000L, timeoutMs = timeout))
    }

    @Test
    fun `an unlock stamp ahead of the clock is no elapsed time`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, unlockedAtMs = 500_000L, nowMs = 400_000L, timeoutMs = timeout))
    }

    /** The policy can only see the unlock if MainActivity records it, from the state itself (any unlock path). */
    @Test
    fun `MainActivity stamps the unlock from walletState and passes it to the policy`() {
        val src = java.io.File(java.io.File("..").canonicalFile, "app/src/main/java/app/aroundtheblock/wallet/MainActivity.kt").readText()
        assertTrue("an unlock stamp is recorded when walletState becomes Unlocked",
            Regex("""walletState\s*\.collect\s*\{[^}]*is\s+WalletState\.Unlocked[^}]*unlockedAtMs\s*=\s*android\.os\.SystemClock\.elapsedRealtime\(\)""").containsMatchIn(src))
        assertTrue("the tick passes the unlock stamp to shouldAutoLock",
            Regex("""shouldAutoLock\([^)]*unlockedAtMs""").containsMatchIn(src))
    }
}
