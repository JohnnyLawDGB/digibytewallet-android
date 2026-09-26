package io.digibyte.ui.navigation

import io.digibyte.core.WalletState

/**
 * Routes that belong to the onboarding / first-unlock graph. A [WalletState.Locked] wallet
 * legitimately sits underneath these (e.g. "Restore a different wallet" from the unlock
 * screen walks the onboarding graph while the on-disk wallet is still Locked), so they are
 * never re-gated to "unlock".
 */
private val PRE_WALLET_ROUTES = setOf(
    "onboarding", "seed_display", "seed_verify", "seed_passphrase", "pin_setup", "unlock",
)

/**
 * Should a [WalletState.Locked] wallet be navigated to the "unlock" route right now?
 *
 * This is the reactive half of the app's lock. `MainActivity.onStop()` calls
 * `WalletManager.lockUi()` (state → Locked, native seed deliberately kept so SyncService
 * keeps syncing), but `AppNavigation` computes its start destination once, so without this
 * decision a warm resume of the same Activity instance returned to the live wallet graph
 * with no PIN (measured on the Note 8, v4.0.75).
 *
 * Guards, each tied to a flow that must not be bounced:
 *  - only [WalletState.Locked] routes; Unlocked / NoWallet never do.
 *  - `hasPin == false` is the lost-PIN branch — startDestination routes it to pin_setup,
 *    which restores from disk; a PIN prompt there can never be satisfied.
 *  - `currentRoute == null` means the NavHost has no graph yet; the start destination is
 *    authoritative on cold start and navigating here is the "double PIN prompt".
 *  - pre-wallet routes (onboarding graph, pin_setup, unlock itself) are never re-gated.
 */
fun shouldRouteToUnlock(state: WalletState, hasPin: Boolean, currentRoute: String?): Boolean {
    if (state !is WalletState.Locked) return false
    if (!hasPin) return false
    if (currentRoute == null) return false
    val base = currentRoute.substringBefore("?").substringBefore("/")
    return base !in PRE_WALLET_ROUTES
}

/**
 * Should a wallet that has just been wiped be navigated to "onboarding" right now?
 *
 * Wipe-after-N can trip inside the spend gate's PIN dialog (Send, sweep, Digi-ID, …), and
 * those screens hold no NavController — UnlockScreen and Security settings navigate
 * themselves, everything else relies on this. Only [WalletState.NoWallet] on a wallet route
 * qualifies: the onboarding graph is NoWallet by definition and must never be bounced, and a
 * null route means the start destination is still authoritative.
 */
fun shouldRouteToOnboardingAfterWipe(state: WalletState, currentRoute: String?): Boolean {
    if (state !is WalletState.NoWallet) return false
    if (currentRoute == null) return false
    val base = currentRoute.substringBefore("?").substringBefore("/")
    return base !in PRE_WALLET_ROUTES
}

/**
 * Screens a lock may interrupt that the wallet reopens once the user has unlocked (B230).
 *
 * A system credential prompt (the device-lock refresh Recover funds and the seed screens ask for)
 * is another activity: it stops MainActivity, which locks, and the unlock then lands on the wallet
 * home — the user had to find their way back to the screen they were using. Only argument-free
 * settings-side screens are listed: the route is reopened as a pattern, and anything typed on the
 * screen before the lock is gone either way. The user has just authenticated, so reopening the
 * screen shows nothing the unlock did not already open.
 */
private val RESUMABLE_AFTER_UNLOCK = setOf(
    "recover_funds", "settings_security", "settings_view_seed", "settings_network",
    "settings_display", "settings_about", "settings_reconcile",
)

/** The route to reopen after the unlock that followed a lock on [interrupted], or null. */
fun routeToResumeAfterUnlock(interrupted: String?): String? =
    interrupted?.takeIf { it in RESUMABLE_AFTER_UNLOCK }
