package app.aroundtheblock.wallet.ui.navigation

import app.aroundtheblock.wallet.core.WalletState

/**
 * Routes that belong to the onboarding / first-unlock graph. A [WalletState.Locked] wallet
 * legitimately sits underneath these (e.g. "Restore a different wallet" from the unlock
 * screen walks the onboarding graph while the on-disk wallet is still Locked), so they are
 * never re-gated to "unlock".
 */
private val PRE_WALLET_ROUTES = setOf(
    "onboarding", "seed_display", "seed_verify", "seed_passphrase", "mnemonic_input",
    "recovery_scan", "recovery_date", "pin_setup", "unlock",
    // Shows no wallet, and with the store unreadable the lock counts a PIN as set (pinAssumedForLock): re-gated
    // to unlock, whose PIN reads throw on that store, it crashed every launch (4.1.2 row 23).
    PIN_STORE_UNAVAILABLE_ROUTE,
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
    "recover_funds", "settings_security", "settings_network",
    "settings_display", "settings_about", "settings_reconcile",
)

/**
 * The route to reopen after the unlock that followed a lock on [interrupted], or null.
 *
 * The recovery phrase is the exception to "the unlock already opened it": the unlock takes the PIN
 * OR a biometric, while the phrase takes the PIN AND a biometric. A lock on the phrase therefore
 * returns to Security settings, where the phrase must be asked for again (BB-2026-10-08-dino).
 */
fun routeToResumeAfterUnlock(interrupted: String?): String? = when (interrupted) {
    "settings_view_seed" -> "settings_security"
    else -> interrupted?.takeIf { it in RESUMABLE_AFTER_UNLOCK }
}

/** The Digi-ID confirm route; its one argument is the login URI. */
const val DIGIID_CONFIRM_ROUTE = "digiid_confirm/{uri}"

/**
 * The Digi-ID login to keep through an unlock, or null (4.1.2 row 35). A digiid:// link opened from a
 * website or another app while the wallet is locked lands on the confirm screen, which the lock then
 * replaced with the unlock screen: the login was dropped and the user had to open the link again.
 * Kept, it is handed back as the pending link and opens once the unlock reaches the wallet. Nothing
 * is signed on the way: the confirm screen still needs Approve and the spend check.
 */
fun digiIdLinkToKeep(interrupted: String?, loginUri: String?): String? =
    loginUri?.takeIf { interrupted == DIGIID_CONFIRM_ROUTE && it.startsWith("digiid://") }

/**
 * The pending Digi-ID link to open now, or null (4.1.2 row 36). A link opens on the wallet screen
 * only. It used to be looked at only when the route changed to the wallet, so a link that arrived
 * while the wallet screen was already showing -- a login QR scanned with the camera while the app
 * sat on its home screen -- waited, unopened, until the user left the screen and came back. The
 * caller therefore keys on the pending link as well as the route. Only an unlocked wallet opens it
 * (review R11): on a warm resume the link and the lock arrive together, and the confirm screen was
 * pushed over the unlock screen for a frame before the lock pulled it back.
 */
fun digiIdLinkToOpen(route: String?, pending: String?, unlocked: Boolean): String? =
    pending?.takeIf { unlocked && route == WALLET_ROUTE && it.startsWith("digiid://") }

/** The wallet's home route ([Screen.Wallet]). */
const val WALLET_ROUTE = "wallet"

/** The route shown when the PIN store cannot be opened (4.1.2 row 23). */
const val PIN_STORE_UNAVAILABLE_ROUTE = "pin_store_unavailable"

/**
 * Where the app starts. A wallet whose PIN store cannot be read -- the store could not be opened, or
 * asking it whether a PIN is set throws -- starts on [PIN_STORE_UNAVAILABLE_ROUTE]: it is never read as
 * "no PIN", which routed a locked wallet to pin_setup, where anyone holding the phone could set a new one.
 * [hasPin] is asked only when the store is available; a throw counts as unreadable.
 */
fun startDestinationFor(walletState: WalletState, storeAvailable: Boolean, hasPin: () -> Boolean): StartDecision {
    if (walletState is WalletState.NoWallet) return StartDecision("onboarding", restoreForLostPin = false)
    val pinSet: Boolean? = if (!storeAvailable) null else try { hasPin() } catch (_: Exception) { null }
    return when {
        pinSet == null -> StartDecision(PIN_STORE_UNAVAILABLE_ROUTE, restoreForLostPin = false)
        walletState is WalletState.Locked -> if (pinSet) StartDecision("unlock", false) else StartDecision("pin_setup", true)
        else -> if (pinSet) StartDecision(Screen.Wallet.route, false) else StartDecision("pin_setup", false)
    }
}

/** [route] to start on, and whether pin_setup must first restore the wallet from disk (the lost-PIN branch). */
data class StartDecision(val route: String, val restoreForLostPin: Boolean)
