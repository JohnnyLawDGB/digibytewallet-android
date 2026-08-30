package io.digibyte.ui.navigation

import io.digibyte.core.WalletState
import io.digibyte.core.db.entity.WalletConfigEntity

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
 *  - `hasPin == false` never locks. The wallet sits Unlocked with no PIN on `pin_setup`
 *    (fresh restore, lost-PIN restore-from-disk). Locking there is unrecoverable:
 *    `shouldRouteToUnlock` refuses to route a no-PIN Locked state, PinSetupScreen then lands
 *    on the wallet graph under Locked, and from there onStop's lock and AppNavigation's
 *    Locked→"unlock" effect are both no-ops for the rest of the process — the warm-resume
 *    hole the base commit closed, reopened.
 *  - `timeoutMs <= 0` disables the lock (no such option exists in Settings today, but a
 *    zero from a migrated/corrupt row must not lock on every tick).
 *  - `nowMs < lastInteractionMs` is treated as no elapsed time: the caller stamps
 *    `SystemClock.elapsedRealtime()`, which is monotonic, but a reset of the stamp that
 *    races a tick must never read as a huge positive gap and lock instantly.
 */
fun shouldAutoLock(
    state: WalletState,
    hasPin: Boolean,
    lastInteractionMs: Long,
    nowMs: Long,
    timeoutMs: Long,
): Boolean {
    if (state !is WalletState.Unlocked) return false
    if (!hasPin) return false
    if (timeoutMs <= 0L) return false
    if (nowMs < lastInteractionMs) return false
    return nowMs - lastInteractionMs >= timeoutMs
}

/**
 * The effective auto-lock timeout for a `wallet_config` row that may not exist.
 *
 * Nothing seeds the row on wallet create/restore — the only upserts are Settings writes that
 * themselves require an existing row — so `WalletConfigDao.get()` is null for every wallet
 * whose owner never opened Settings → Security. That screen displays the entity default in
 * that case (`SettingsViewModel.loadConfig`), so the lock must enforce the same default;
 * treating null as "skip this tick" left the setting inert for the common case.
 */
fun autoLockTimeoutOrDefault(storedTimeoutMs: Long?): Long =
    storedTimeoutMs ?: WalletConfigEntity().autoLockTimeoutMs
