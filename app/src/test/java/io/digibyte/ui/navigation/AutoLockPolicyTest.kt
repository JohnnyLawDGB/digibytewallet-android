package io.digibyte.ui.navigation

import io.digibyte.core.WalletState
import io.digibyte.core.db.entity.WalletConfigEntity
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
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 1_000L, nowMs = 61_000L, timeoutMs = timeout))
    }

    @Test
    fun `unlocked and idle exactly at the timeout locks`() {
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, nowMs = timeout, timeoutMs = timeout))
    }

    @Test
    fun `unlocked but still within the timeout does not lock`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 1_000L, nowMs = 60_999L, timeoutMs = timeout))
    }

    @Test
    fun `locked wallet never re-locks`() {
        assertFalse(shouldAutoLock(WalletState.Locked, hasPin = true, lastInteractionMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `no wallet never locks`() {
        assertFalse(shouldAutoLock(WalletState.NoWallet, hasPin = true, lastInteractionMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `zero timeout means the inactivity lock is disabled`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, nowMs = Long.MAX_VALUE / 2, timeoutMs = 0L))
    }

    @Test
    fun `negative timeout means the inactivity lock is disabled`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, nowMs = Long.MAX_VALUE / 2, timeoutMs = -1L))
    }

    @Test
    fun `backwards clock counts as no elapsed time`() {
        // now < last must not wrap into a huge positive elapsed and lock instantly.
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 500_000L, nowMs = 1_000L, timeoutMs = timeout))
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = Long.MAX_VALUE, nowMs = Long.MIN_VALUE + 1, timeoutMs = timeout))
    }

    @Test
    fun `unlocked with no PIN never locks — there is nothing to unlock with`() {
        // pin_setup after a restore, or the lost-PIN restore-from-disk: Unlocked, hasPin=false.
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = false, lastInteractionMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `missing wallet_config row falls back to the entity default, not to never-lock`() {
        // Nothing seeds the row on create/restore; the Security screen shows the entity
        // default, so the lock must enforce that same default.
        val default = WalletConfigEntity().autoLockTimeoutMs
        assertEquals(default, autoLockTimeoutOrDefault(null))
        assertTrue(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, nowMs = default, timeoutMs = autoLockTimeoutOrDefault(null)))
        assertFalse(shouldAutoLock(WalletState.Unlocked, hasPin = true, lastInteractionMs = 0L, nowMs = default - 1, timeoutMs = autoLockTimeoutOrDefault(null)))
    }

    @Test
    fun `present wallet_config row wins over the default`() {
        assertEquals(300_000L, autoLockTimeoutOrDefault(300_000L))
        assertEquals(0L, autoLockTimeoutOrDefault(0L))
    }
}
