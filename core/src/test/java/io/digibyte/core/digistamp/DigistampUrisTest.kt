package io.digibyte.core.digistamp

import org.json.JSONObject
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
 * Anything that is not exactly `https://assets.digistamp.co` goes to the system browser, where it
 * runs under the browser's sandbox and the user can see the URL bar.
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

    // ---- the Digi-ID challenge ------------------------------------------------------------

    /** Verbatim from GET https://assets.digistamp.co/api/auth/digiid/challenge, 2026-08-23. */
    private val realChallenge = """
        {"uri":"digiid://assets.digistamp.co/api/auth/digiid/callback?x=0b654336c3fcc835a5f325a8f3d4dc77",
         "nonce":"0b654336c3fcc835a5f325a8f3d4dc77",
         "callbackUrl":"https://assets.digistamp.co/api/auth/digiid/callback",
         "expiresAt":"2026-08-23T12:42:29.259Z"}
    """.trimIndent()

    @Test fun `the live challenge parses`() {
        val c = DigistampUris.parseChallenge(JSONObject(realChallenge))!!

        assertEquals(
            "digiid://assets.digistamp.co/api/auth/digiid/callback?x=0b654336c3fcc835a5f325a8f3d4dc77",
            c.digiIdUri,
        )
        assertEquals("0b654336c3fcc835a5f325a8f3d4dc77", c.nonce)
    }

    /**
     * The wallet signs whatever URI this returns, so a challenge naming someone else's callback
     * would have it sign an authentication for a site the user never visited. The host is checked
     * here as well as inside DigiIdManager — one of those is a backstop, not a duplicate.
     */
    @Test fun `a challenge pointing at another domain is refused`() {
        val hostile = """
            {"uri":"digiid://evil.com/callback?x=abc","nonce":"abc",
             "callbackUrl":"https://evil.com/callback"}
        """.trimIndent()

        assertNull(DigistampUris.parseChallenge(JSONObject(hostile)))
    }

    @Test fun `a challenge whose callback disagrees with its uri is refused`() {
        val mismatched = """
            {"uri":"digiid://assets.digistamp.co/api/auth/digiid/callback?x=abc","nonce":"abc",
             "callbackUrl":"https://evil.com/api/auth/digiid/callback"}
        """.trimIndent()

        assertNull(DigistampUris.parseChallenge(JSONObject(mismatched)))
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

    @Test fun `an error body or a missing uri yields no challenge`() {
        assertNull(DigistampUris.parseChallenge(JSONObject("""{"error":"rate limited"}""")))
        assertNull(DigistampUris.parseChallenge(JSONObject("""{"nonce":"abc"}""")))
    }
}
