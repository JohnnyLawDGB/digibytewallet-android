package app.aroundtheblock.wallet.ui.navigation

import app.aroundtheblock.wallet.core.WalletState
import app.aroundtheblock.wallet.core.settings.AutoLockPrefs

/**
 * Should the foreground UI lock for inactivity right now?
 *
 * The Security settings auto-lock timeout (then `WalletConfigEntity.autoLockTimeoutMs`, now the
 * app-wide [AutoLockPrefs]) was
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
 *  - idle runs from the LATER of the last touch and the last unlock (`unlockedAtMs`). A
 *    fingerprint unlock touches nothing in the activity, so onUserInteraction never stamps;
 *    measured from the touch alone, every tick after a biometric unlock read the process
 *    age as idle time, locked, and the unlock screen re-prompted — an endless sign-on loop
 *    (Moto, 4.1.2 Wave 10, 2026-10-25).
 */
fun shouldAutoLock(
    state: WalletState,
    hasPin: Boolean,
    lastInteractionMs: Long,
    unlockedAtMs: Long,
    nowMs: Long,
    timeoutMs: Long,
): Boolean {
    if (state !is WalletState.Unlocked) return false
    if (!hasPin) return false
    if (timeoutMs <= 0L) return false
    val idleSinceMs = maxOf(lastInteractionMs, unlockedAtMs)
    if (nowMs < idleSinceMs) return false
    return nowMs - idleSinceMs >= timeoutMs
}

/**
 * The effective auto-lock timeout for an app-wide value ([AutoLockPrefs.storedTimeoutMs]) that may
 * not exist. The one place the default is applied: the inactivity lock and Settings → Security both
 * read through here, so the screen never shows one timeout while the lock enforces another.
 *
 * Nothing is stored until the owner picks a timeout (or the one-time move carries the one they
 * picked before it was app-wide), so "nothing stored" is the common case and means the default,
 * [AutoLockPrefs.DEFAULT_TIMEOUT_MS] (60 s, the wallet database's default before the move);
 * treating it as "skip this tick" would leave the setting inert for most wallets.
 */
fun autoLockTimeoutOrDefault(storedTimeoutMs: Long?): Long =
    storedTimeoutMs ?: AutoLockPrefs.DEFAULT_TIMEOUT_MS
