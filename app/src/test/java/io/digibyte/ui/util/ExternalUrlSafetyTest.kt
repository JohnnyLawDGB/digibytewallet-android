package io.digibyte.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defensive scheme/length filter on [openExternalUrl]. Android's
 * Custom Tabs typically rejects non-http(s) schemes itself, but the
 * ACTION_VIEW fallback does not — and any caller who lets a
 * network-sourced URL (asset metadata `urls[]`, release-channel JSON)
 * flow in shouldn't be one carelessness away from shipping a
 * tap-to-execute primitive.
 *
 * Pure-Kotlin predicate — no Android dependencies.
 */
class ExternalUrlSafetyTest {

    // -------------------------------------------------------------------------
    // Allowed schemes
    // -------------------------------------------------------------------------

    @Test
    fun `https URL is accepted`() {
        assertTrue(isSafeExternalUrl("https://example.com/asset/abc"))
    }

    @Test
    fun `http URL is accepted`() {
        assertTrue(isSafeExternalUrl("http://example.com/"))
    }

    @Test
    fun `mixed-case https scheme is accepted`() {
        assertTrue(isSafeExternalUrl("HTTPS://example.com/"))
        assertTrue(isSafeExternalUrl("Https://example.com/"))
    }

    // -------------------------------------------------------------------------
    // Refused: classic xss / app-targeting schemes
    // -------------------------------------------------------------------------

    @Test
    fun `javascript scheme is refused`() {
        assertFalse(isSafeExternalUrl("javascript:alert(1)"))
        assertFalse(isSafeExternalUrl("JavaScript:alert(1)"))
        assertFalse(isSafeExternalUrl("JAVASCRIPT:alert(1)"))
        assertFalse(isSafeExternalUrl("javaScript:void(0)"))
    }

    @Test
    fun `data URI is refused`() {
        // We allow data: in AssetImageResolver because Coil decodes it
        // safely as image bytes; but for *external launch* the same
        // scheme can carry arbitrary HTML/JS payloads when the receiver
        // is a browser, so refuse here.
        assertFalse(isSafeExternalUrl("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun `file scheme is refused`() {
        assertFalse(isSafeExternalUrl("file:///data/data/io.digibyte/databases/wallet.db"))
        assertFalse(isSafeExternalUrl("FILE:///etc/passwd"))
    }

    @Test
    fun `intent scheme is refused`() {
        // intent:// can target arbitrary in-app activities — never let
        // a tainted URL through this helper.
        assertFalse(isSafeExternalUrl("intent://com.example#Intent;scheme=https;end"))
    }

    @Test
    fun `content scheme is refused`() {
        assertFalse(isSafeExternalUrl("content://com.example.provider/secret"))
    }

    @Test
    fun `app-custom schemes are refused`() {
        assertFalse(isSafeExternalUrl("digibyte://send?address=foo"))
        assertFalse(isSafeExternalUrl("digiid://example.com/login"))
        assertFalse(isSafeExternalUrl("market://details?id=com.foo"))
    }

    // -------------------------------------------------------------------------
    // Refused: malformed / malicious
    // -------------------------------------------------------------------------

    @Test
    fun `empty and whitespace-only inputs are refused`() {
        assertFalse(isSafeExternalUrl(""))
        assertFalse(isSafeExternalUrl("   "))
        assertFalse(isSafeExternalUrl("\t\n"))
    }

    @Test
    fun `bare hostname without scheme is refused`() {
        // Without a scheme there's no contract about how the OS handles
        // it; refuse rather than guessing.
        assertFalse(isSafeExternalUrl("example.com"))
        assertFalse(isSafeExternalUrl("//example.com/"))
    }

    @Test
    fun `embedded control chars are refused`() {
        // CR/LF/null/etc. — some parsers strip them during normalization,
        // so a "safe" string with embedded \r\njavascript: could end up
        // launching JavaScript. Reject up-front.
        assertFalse(isSafeExternalUrl("https://example.com/\nabc"))
        assertFalse(isSafeExternalUrl("https://example.com/\u0000"))
        assertFalse(isSafeExternalUrl("https://example.com/\rabc"))
        assertFalse(isSafeExternalUrl("https://example.com/\u007F"))
    }

    @Test
    fun `oversized URL is refused`() {
        val payload = "https://example.com/" + "a".repeat(3000)
        assertFalse(isSafeExternalUrl(payload))
    }

    @Test
    fun `URL right at the size cap is accepted`() {
        // 2048 chars total — boundary check
        val tail = "a".repeat(2048 - "https://example.com/".length)
        val payload = "https://example.com/$tail"
        assertTrue(isSafeExternalUrl(payload))
    }
}
