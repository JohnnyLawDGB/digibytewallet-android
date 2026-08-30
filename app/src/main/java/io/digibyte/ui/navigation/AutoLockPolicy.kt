package io.digibyte.ui.navigation

import io.digibyte.core.WalletState

/**
 * Should the foreground UI lock for inactivity right now?
 *
 * The Security settings auto-lock timeout (`WalletConfigEntity.autoLockTimeoutMs`) was
 * stored and displayed but read by nothing (2026-08-30 external audit) — the only lock was
 * `MainActivity.onStop()`, so an unlocked phone left on a table stayed on the wallet graph
 * indefinitely. This is the decision half of the fix; `MainActivity` polls it and calls
 * `WalletManager.lockUi()`, and the existing `LaunchedEffect(walletState)` in AppNavigation
 * does the routing to "unlock" — this function never navigates.
 *
 * Guards:
 *  - only [WalletState.Unlocked] locks; Locked / NoWallet have nothing to protect and
 *    re-flipping Locked would re-fire the reactive route.
 *  - `timeoutMs <= 0` disables the lock (no such option exists in Settings today, but a
 *    zero from a migrated/corrupt row must not lock on every tick).
 *  - `nowMs < lastInteractionMs` is treated as no elapsed time: the caller stamps
 *    `SystemClock.elapsedRealtime()`, which is monotonic, but a reset of the stamp that
 *    races a tick must never read as a huge positive gap and lock instantly.
 */
fun shouldAutoLock(state: WalletState, lastInteractionMs: Long, nowMs: Long, timeoutMs: Long): Boolean {
    if (state !is WalletState.Unlocked) return false
    if (timeoutMs <= 0L) return false
    if (nowMs < lastInteractionMs) return false
    return nowMs - lastInteractionMs >= timeoutMs
}
