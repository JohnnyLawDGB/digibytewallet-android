package app.aroundtheblock.wallet.core.digistamp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The origin lock is the security boundary for housing a third-party site next to a hot wallet.
 *
 * There is deliberately NO JavaScript bridge — nothing in the page can call into the wallet, and
 * `addJavascriptInterface` is never used. The only route from a page to the wallet is navigation,
 * which the WebView client intercepts. That makes [DigistampUris.isInAppOrigin] the thing
 * standing between "our marketplace" and "any page that talked its way into the WebView", so it
 * is written as an allowlist and pushed on from every direction below.
 *
 * Anything that is not exactly `https://assets.digistamp.co`, or a sign-in page of exactly
 * `https://id.digistamp.co`, goes to the system browser, where it runs under the browser's sandbox
 * and the user can see the URL bar.
 */
class DigistampUrisTest {

    // ---- what belongs in the app ----------------------------------------------------------

    @Test fun `the site itself loads in-app`() {
        assertTrue(DigistampUris.isInAppOrigin("https://assets.digistamp.co/"))
        assertTrue(DigistampUris.isInAppOrigin("https://assets.digistamp.co/marketplace"))
        assertTrue(DigistampUris.isInAppOrigin("https://assets.digistamp.co/mint?ref=1"))
        assertTrue(DigistampUris.isInAppOrigin("https://assets.digistamp.co:443/collections"))
    }

    // ---- what must not --------------------------------------------------------------------

    /** A suffix match would accept these. They are domains an attacker owns. */
    @Test fun `a lookalike suffix is rejected`() {
        assertFalse(DigistampUris.isInAppOrigin("https://assets.digistamp.co.evil.com/"))
        assertFalse(DigistampUris.isInAppOrigin("https://notassets.digistamp.co/"))
        assertFalse(DigistampUris.isInAppOrigin("https://assets.digistamp.co@evil.com/"))
    }

    /** Subdomains are NOT the same origin, however friendly they look. */
    @Test fun `subdomains and the bare domain are rejected`() {
        assertFalse(DigistampUris.isInAppOrigin("https://cdn.assets.digistamp.co/"))
        assertFalse(DigistampUris.isInAppOrigin("https://digistamp.co/"))
    }

    /** Cleartext would put the session on the wire in the clear. */
    @Test fun `plaintext http is rejected even on the right host`() {
        assertFalse(DigistampUris.isInAppOrigin("http://assets.digistamp.co/marketplace"))
    }

    /** Script and inline-content schemes must never be treated as a page load. */
    @Test fun `script and data schemes are rejected`() {
        assertFalse(DigistampUris.isInAppOrigin("javascript:alert(1)"))
        assertFalse(DigistampUris.isInAppOrigin("data:text/html,<script>x</script>"))
        assertFalse(DigistampUris.isInAppOrigin("file:///etc/passwd"))
        assertFalse(DigistampUris.isInAppOrigin("content://media/external/file/1"))
    }

    @Test fun `nonsense is rejected rather than throwing`() {
        assertFalse(DigistampUris.isInAppOrigin(""))
        assertFalse(DigistampUris.isInAppOrigin("not a url at all"))
        assertFalse(DigistampUris.isInAppOrigin("https://"))
    }

    // ---- the sign-in host (MARKET-SIGNIN-ID-HOST) -------------------------------------------

    /**
     * The pages DigiStamp's Digi-ID sign-in walks through, read from the live site on 2026-11-21:
     * the marketplace's "Sign in" redirects to `/id/signin`, the page continues to `/id/welcome`
     * and `/id/continue`, and the host's own `/auth/` routes redirect into and out of it. Without
     * these the sign-in page opens in the browser and the session never reaches the app.
     */
    @Test fun `the sign-in pages of the Digi-ID host load in-app`() {
        listOf(
            "https://id.digistamp.co/id/signin?site=assets&state=nbbRyVZiCa_FHnuXzphjfE3JGrbgkEu5X9C3hZWyaj8&next=%2F",
            "https://id.digistamp.co/id/welcome?then=%2Fid%2Fcontinue%3Fsite%3Dassets%26next%3D%252F%26state%3Dabc",
            "https://id.digistamp.co/id/continue?site=assets&next=%2F&state=abc",
            "https://id.digistamp.co/id/help/bring-your-account-over",
            "https://id.digistamp.co/auth/login?next=%2Fid%2Fsignin",
            "https://id.digistamp.co/auth/failed?reason=request",
            "https://id.digistamp.co/id",
            "https://id.digistamp.co/",
            "https://id.digistamp.co",
            "https://id.digistamp.co/account",
            "https://ID.digistamp.co:443/id/signin",
        ).forEach {
            assertTrue(it, DigistampUris.isInAppOrigin(it))
            assertTrue(it, DigistampUris.isSignInPage(it))
        }
    }

    /** The sign-in host is in the app for sign-in, not as a second site: its other paths go to the browser. */
    @Test fun `any other path of the Digi-ID host is rejected`() {
        listOf(
            "https://id.digistamp.co/explorer",
            "https://id.digistamp.co/admin",
            "https://id.digistamp.co/api/auth/digiid/challenge",
            "https://id.digistamp.co/_next/static/chunks/x.js",
            "https://id.digistamp.co/identity",
            "https://id.digistamp.co/ids/signin",
            "https://id.digistamp.co/authx/login",
            "https://id.digistamp.co/auth",
            "https://id.digistamp.co/account/x",
            "https://id.digistamp.co/accounts",
            "https://id.digistamp.co//id/signin",
        ).forEach {
            assertFalse(it, DigistampUris.isInAppOrigin(it))
            assertFalse(it, DigistampUris.isSignInPage(it))
        }
    }

    /** A path that would name a different page once normalised is refused, not normalised. */
    @Test fun `a sign-in path that climbs out or is respelled is rejected`() {
        listOf(
            "https://id.digistamp.co/id/../admin",
            "https://id.digistamp.co/id/./../admin",
            "https://id.digistamp.co/id/..",
            "https://id.digistamp.co/auth/../explorer",
            "https://id.digistamp.co/id/%2e%2e/admin",
            "https://id.digistamp.co/id/%2E%2E%2Fadmin",
            "https://id.digistamp.co/%69d/signin",
            "https://id.digistamp.co/id/signin%00",
            "https://id.digistamp.co/id\\signin",
            "https://id.digistamp.co/id/sign in",
            "https://id.digistamp.co/id;x=1/signin",
        ).forEach { assertFalse(it, DigistampUris.isInAppOrigin(it)) }
    }

    /** Every trick refused for the marketplace host is refused for the sign-in host too. */
    @Test fun `lookalikes of the Digi-ID host are rejected`() {
        listOf(
            "https://id.digistamp.co.evil.com/id/signin",
            "https://notid.digistamp.co/id/signin",
            "https://xid.digistamp.co/id/signin",
            "https://id.digistamp.co@evil.com/id/signin",
            "https://id.digistamp.co:x@evil.com/id/signin",
            "https://id.digistamp.co%2Fid%2Fsignin@evil.com/",
            "https://a.id.digistamp.co/id/signin",
            "https://id.assets.digistamp.co/id/signin",
            "https://id.digistamp.co./id/signin",
            "https://id.digistamp.com/id/signin",
            "https://id-digistamp.co/id/signin",
            "https://evil.com/id.digistamp.co/id/signin",
            "https://evil.com/?https://id.digistamp.co/id/signin",
            "https://evil.com/#https://id.digistamp.co/id/signin",
            "http://id.digistamp.co/id/signin",
            "https://id.digistamp.co:8443/id/signin",
            "https://id.digistamp.co:80/id/signin",
            "digiid://id.digistamp.co/api/auth/digiid/callback?x=abc",
            "javascript://id.digistamp.co/id/signin%0aalert(1)",
            "intent://id.digistamp.co/id/signin#Intent;scheme=https;end",
        ).forEach {
            assertFalse(it, DigistampUris.isInAppOrigin(it))
            assertFalse(it, DigistampUris.isSignInPage(it))
        }
    }

    /** Credentials in the URL are no part of sign-in, and the classic way to disguise a host. */
    @Test fun `user info is rejected even on the right host`() {
        assertFalse(DigistampUris.isInAppOrigin("https://user@id.digistamp.co/id/signin"))
        assertFalse(DigistampUris.isInAppOrigin("https://user:pw@id.digistamp.co/id/signin"))
        assertFalse(DigistampUris.isInAppOrigin("https://assets.digistamp.co@id.digistamp.co/id/signin"))
        assertFalse(DigistampUris.isInAppOrigin("https://id.digistamp.co@assets.digistamp.co/"))
        assertFalse(DigistampUris.isInAppOrigin("https://user@assets.digistamp.co/"))
    }

    /** The other DigiStamp products the sign-in page links to stay out: nothing in the Market needs them. */
    @Test fun `no other DigiStamp host is in-app`() {
        listOf("proof", "signal", "tickets", "contracts", "www", "api", "cdn").forEach {
            assertFalse(it, DigistampUris.isInAppOrigin("https://$it.digistamp.co/"))
            assertFalse(it, DigistampUris.isInAppOrigin("https://$it.digistamp.co/id/signin"))
        }
        assertFalse(DigistampUris.isInAppOrigin("https://digistamp.co/id/signin"))
    }

    /** A marketplace page is in the app and is not a sign-in page: the two are told apart by host. */
    @Test fun `a marketplace page is not a sign-in page`() {
        assertTrue(DigistampUris.isInAppOrigin("https://assets.digistamp.co/id/signin"))
        assertFalse(DigistampUris.isSignInPage("https://assets.digistamp.co/id/signin"))
        assertFalse(DigistampUris.isSignInPage("https://assets.digistamp.co/"))
        assertFalse(DigistampUris.isSignInPage(""))
    }

    // ---- per-asset explorer link ----------------------------------------------------------

    /**
     * Replaces a hardcoded `https://diginexum.trade/` on the asset screen — a stale host whose
     * certificate is issued for `CN = digiassets.info`, so tapping Marketplace showed users a
     * full-page browser security warning. It also carried no asset id; it opened a homepage.
     */
    @Test fun `builds the explorer url for an asset`() {
        assertEquals(
            "https://assets.digistamp.co/explorer/La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC",
            DigistampUris.explorerUrlFor("La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC"),
        )
    }

    /**
     * The asset id becomes part of a URL that a WebView then loads, so anything that could
     * escape the path is refused rather than encoded. Asset ids are base58 — no slashes, dots,
     * colons or spaces occur legitimately, so rejecting them costs nothing real.
     */
    @Test fun `refuses an asset id that could escape the path`() {
        assertNull(DigistampUris.explorerUrlFor("../../etc/passwd"))
        assertNull(DigistampUris.explorerUrlFor("La8k/../../x"))
        assertNull(DigistampUris.explorerUrlFor("La8k?next=https://evil.example"))
        assertNull(DigistampUris.explorerUrlFor("La8k#frag"))
        assertNull(DigistampUris.explorerUrlFor("javascript:alert(1)"))
        assertNull(DigistampUris.explorerUrlFor(""))
        assertNull(DigistampUris.explorerUrlFor("   "))
    }

    /** Whatever it builds must satisfy the origin lock, or the WebView would bounce it out. */
    @Test fun `what it builds is in-app by construction`() {
        val url = DigistampUris.explorerUrlFor("La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC")!!
        assertTrue(DigistampUris.isInAppOrigin(url))
    }
}
