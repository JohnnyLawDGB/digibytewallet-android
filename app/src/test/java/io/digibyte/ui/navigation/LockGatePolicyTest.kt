package io.digibyte.ui.navigation

import io.digibyte.core.WalletState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [shouldRouteToUnlock] — the decision that turns a
 * [WalletState.Locked] transition into a navigation to the "unlock" route.
 *
 * ## Why this gate exists
 *
 * `MainActivity.onStop()` calls `walletManager.lockUi()`, which flips the state to Locked
 * and — by design — keeps the native seed loaded so SyncService keeps syncing. But
 * `AppNavigation` computes its start destination ONCE, so on a warm resume of the same
 * Activity instance (launcher tap, Recents, returning from the Market browser) the
 * NavHost was still sitting on the wallet graph: balance, Send, Receive, Assets, the
 * DigiAsset send confirm, the foreign-seed sweep and Hub login were all reachable with no
 * PIN. Measured on the Note 8 (v4.0.75): HOME → 6 s → launcher tap → full Wallet screen.
 * A fresh instance (`am start -n` stacks a new Activity) DOES prompt — which is why casual
 * testing missed it.
 *
 * The routing itself lives in a `LaunchedEffect(walletState)` in AppNavigation; this
 * function is the part that can be proven on the JVM. Each guard below corresponds to a
 * flow that must NOT be bounced to the PIN screen.
 */
class LockGatePolicyTest {

    @Test
    fun `locked with a PIN on any wallet route routes to unlock`() {
        for (route in listOf("wallet", "send", "receive", "assets", "asset_send/abc",
                "settings", "settings_security", "recover_funds", "hub", "digistamp",
                "digiid_confirm/x", "node_pair_confirm/x", "qr_scanner", "transaction_detail/t")) {
            assertTrue(route, shouldRouteToUnlock(WalletState.Locked, hasPin = true, currentRoute = route))
        }
    }

    @Test
    fun `already on unlock never re-navigates`() {
        // Re-navigating onto "unlock" is the "double PIN prompt" the once-only start
        // destination was introduced to avoid.
        assertFalse(shouldRouteToUnlock(WalletState.Locked, hasPin = true, currentRoute = "unlock"))
    }

    @Test
    fun `unlocked or no wallet never routes`() {
        assertFalse(shouldRouteToUnlock(WalletState.Unlocked, hasPin = true, currentRoute = "wallet"))
        assertFalse(shouldRouteToUnlock(WalletState.NoWallet, hasPin = true, currentRoute = "onboarding"))
        assertFalse(shouldRouteToUnlock(WalletState.NoWallet, hasPin = false, currentRoute = "wallet"))
    }

    @Test
    fun `lost-PIN branch is left to pin_setup`() {
        // Locked && !hasPin is the lost-PIN path: startDestination routes it to pin_setup,
        // which restores the wallet from disk off the main thread. Sending it to "unlock"
        // would present a PIN prompt that no PIN can satisfy.
        assertFalse(shouldRouteToUnlock(WalletState.Locked, hasPin = false, currentRoute = "wallet"))
        assertFalse(shouldRouteToUnlock(WalletState.Locked, hasPin = false, currentRoute = "pin_setup"))
    }

    @Test
    fun `before the NavHost has a graph the start destination is authoritative`() {
        // currentRoute is null until NavHost sets its graph. Cold start already lands on
        // "unlock" via startDestination; navigating here would be a second prompt.
        assertFalse(shouldRouteToUnlock(WalletState.Locked, hasPin = true, currentRoute = null))
    }

    @Test
    fun `onboarding graph is never re-gated`() {
        // "Restore a different wallet" from the unlock screen walks the onboarding graph
        // while the on-disk wallet is still Locked with a PIN. Bouncing those screens to
        // the PIN prompt would strand the restore.
        for (route in listOf("onboarding", "seed_display/12", "seed_verify", "seed_passphrase",
                "mnemonic_input", "recovery_scan", "recovery_date", "pin_setup")) {
            assertFalse(route, shouldRouteToUnlock(WalletState.Locked, hasPin = true, currentRoute = route))
        }
    }
}

/**
 * The wipe-after-N backstop reached through the spend gate: a wrong-PIN streak inside the
 * Send / sweep / Digi-ID PIN dialog wipes the wallet exactly as the unlock screen does, but
 * those screens hold no NavController — so the navigation to onboarding is driven from the
 * wallet state instead.
 */
class WipeRouteGatePolicyTest {

    @Test
    fun `a wiped wallet on any wallet route routes to onboarding`() {
        for (route in listOf("wallet", "send", "asset_send/abc", "recover_funds", "hub",
                "digiid_confirm/x", "node_pair_confirm/x", "settings_security")) {
            assertTrue(route, shouldRouteToOnboardingAfterWipe(WalletState.NoWallet, route))
        }
    }

    @Test
    fun `the onboarding graph itself is NoWallet by definition and never bounces`() {
        for (route in listOf("onboarding", "seed_display/12", "seed_verify", "seed_passphrase",
                "mnemonic_input", "recovery_scan", "recovery_date", "pin_setup", "unlock")) {
            assertFalse(route, shouldRouteToOnboardingAfterWipe(WalletState.NoWallet, route))
        }
    }

    @Test
    fun `no graph yet and non-NoWallet states never route`() {
        assertFalse(shouldRouteToOnboardingAfterWipe(WalletState.NoWallet, null))
        assertFalse(shouldRouteToOnboardingAfterWipe(WalletState.Locked, "wallet"))
        assertFalse(shouldRouteToOnboardingAfterWipe(WalletState.Unlocked, "wallet"))
    }
}
