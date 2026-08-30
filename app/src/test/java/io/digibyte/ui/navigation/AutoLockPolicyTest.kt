package io.digibyte.ui.navigation

import io.digibyte.core.WalletState
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
 */
class AutoLockPolicyTest {

    private val timeout = 60_000L

    @Test
    fun `unlocked and idle past the timeout locks`() {
        assertTrue(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 1_000L, nowMs = 61_000L, timeoutMs = timeout))
    }

    @Test
    fun `unlocked and idle exactly at the timeout locks`() {
        assertTrue(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 1_000L, nowMs = 61_000L, timeoutMs = timeout))
        assertTrue(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 0L, nowMs = timeout, timeoutMs = timeout))
    }

    @Test
    fun `unlocked but still within the timeout does not lock`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 1_000L, nowMs = 60_999L, timeoutMs = timeout))
    }

    @Test
    fun `locked wallet never re-locks`() {
        assertFalse(shouldAutoLock(WalletState.Locked, lastInteractionMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `no wallet never locks`() {
        assertFalse(shouldAutoLock(WalletState.NoWallet, lastInteractionMs = 0L, nowMs = 10 * timeout, timeoutMs = timeout))
    }

    @Test
    fun `zero timeout means the inactivity lock is disabled`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 0L, nowMs = Long.MAX_VALUE / 2, timeoutMs = 0L))
    }

    @Test
    fun `negative timeout means the inactivity lock is disabled`() {
        assertFalse(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 0L, nowMs = Long.MAX_VALUE / 2, timeoutMs = -1L))
    }

    @Test
    fun `backwards clock counts as no elapsed time`() {
        // now < last must not wrap into a huge positive elapsed and lock instantly.
        assertFalse(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = 500_000L, nowMs = 1_000L, timeoutMs = timeout))
        assertFalse(shouldAutoLock(WalletState.Unlocked, lastInteractionMs = Long.MAX_VALUE, nowMs = Long.MIN_VALUE + 1, timeoutMs = timeout))
    }
}
