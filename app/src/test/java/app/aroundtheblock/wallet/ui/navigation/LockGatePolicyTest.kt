package app.aroundtheblock.wallet.ui.navigation

import app.aroundtheblock.wallet.core.WalletState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── B230: return to the screen the lock interrupted ────────────────────────────────────

    @Test
    fun `a credential prompt from Recover funds returns there after the unlock`() {
        // Note 8 2026-09-23: the system credential activity stopped MainActivity, which locked;
        // after the PIN the user landed on wallet home and had to open Recover funds again.
        assertEquals("recover_funds", routeToResumeAfterUnlock("recover_funds"))
        for (route in listOf("settings_security", "settings_network",
                "settings_display", "settings_about")) {
            assertEquals(route, routeToResumeAfterUnlock(route))
        }
    }

    @Test
    fun `a lock on the recovery phrase returns to Security settings, never to the phrase`() {
        // BB-2026-10-08-dino: the unlock takes one factor, the phrase takes two.
        assertEquals("settings_security", routeToResumeAfterUnlock("settings_view_seed"))
    }

    @Test
    fun `routes with arguments, the wallet itself and the pre-wallet graph are not resumed`() {
        for (route in listOf(null, "wallet", "unlock", "onboarding", "pin_setup", "send",
                "asset_detail/{assetId}", "transaction_detail/{txid}", "digiid_confirm/{uri}",
                "node_pair_confirm/{uri}", "qr_scanner")) {
            assertNull(route.toString(), routeToResumeAfterUnlock(route))
        }
    }

    // ── 4.1.2 row 35: a Digi-ID link that meets the lock is kept through the unlock ───────────────

    @Test fun `a Digi-ID login interrupted by the lock is kept`() {
        val uri = "digiid://example.com/callback?x=abc123"
        org.junit.Assert.assertEquals(uri, digiIdLinkToKeep(DIGIID_CONFIRM_ROUTE, uri))
    }

    @Test fun `nothing is kept from any other screen or for anything but a digiid link`() {
        org.junit.Assert.assertNull(digiIdLinkToKeep("send", "digiid://example.com/callback?x=1"))
        org.junit.Assert.assertNull(digiIdLinkToKeep(null, "digiid://example.com/callback?x=1"))
        org.junit.Assert.assertNull(digiIdLinkToKeep(DIGIID_CONFIRM_ROUTE, "https://example.com/callback?x=1"))
        org.junit.Assert.assertNull(digiIdLinkToKeep(DIGIID_CONFIRM_ROUTE, null))
    }

    @Test fun `the confirm screen itself is never reopened as a pattern`() {
        // Kept as the pending link instead: the route needs its argument.
        org.junit.Assert.assertNull(routeToResumeAfterUnlock(DIGIID_CONFIRM_ROUTE))
    }

    // ── 4.1.2 row 23: a PIN store that cannot be read is never "no PIN" ─────────────────────────

    @Test fun `a locked wallet whose PIN store did not open starts on the unavailable screen`() {
        org.junit.Assert.assertEquals(PIN_STORE_UNAVAILABLE_ROUTE,
            startDestinationFor(WalletState.Locked, storeAvailable = false) { error("must not be asked") }.route)
    }

    @Test fun `a PIN store that throws when asked is not read as no PIN`() {
        // Before: hasPin() throwing counted as false and sent a locked wallet to pin_setup, where
        // anyone holding the phone could set a new PIN.
        val d = startDestinationFor(WalletState.Locked, storeAvailable = true) { throw IllegalStateException("keyset") }
        org.junit.Assert.assertEquals(PIN_STORE_UNAVAILABLE_ROUTE, d.route)
        org.junit.Assert.assertFalse(d.restoreForLostPin)
    }

    @Test fun `the ordinary starts are unchanged`() {
        org.junit.Assert.assertEquals("unlock", startDestinationFor(WalletState.Locked, true) { true }.route)
        org.junit.Assert.assertEquals(StartDecision("pin_setup", true), startDestinationFor(WalletState.Locked, true) { false })
        org.junit.Assert.assertEquals("onboarding", startDestinationFor(WalletState.NoWallet, false) { error("no") }.route)
    }

    /** Review of the repairs: with the store unreadable the lock counts a PIN as set (R10), so the store-unavailable
     *  screen must not count as a wallet screen -- routed to unlock, whose PIN reads throw, it crashed every launch. */
    @Test fun `the store-unavailable screen is never routed to unlock`() {
        assertFalse(
            "a Locked wallet on the store-unavailable screen was routed to unlock",
            shouldRouteToUnlock(WalletState.Locked, hasPin = true, currentRoute = PIN_STORE_UNAVAILABLE_ROUTE),
        )
    }
}
