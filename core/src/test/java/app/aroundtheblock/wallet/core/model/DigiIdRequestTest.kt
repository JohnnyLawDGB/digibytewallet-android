package app.aroundtheblock.wallet.core.model

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DigiIdRequestTest {

    @Test
    fun `parse valid digiid URI extracts callback and nonce`() {
        val uri = "digiid://example.com/callback?x=abc123nonce"
        val result = DigiIdRequest.parse(uri)

        assertNotNull(result)
        assertEquals("digiid://example.com/callback?x=abc123nonce", result!!.rawUri)
        assertEquals("https://example.com/callback", result.callbackUrl)
        assertEquals("abc123nonce", result.nonce)
        assertFalse(result.isUnsecure)
    }

    @Test
    fun `parse detects unsecure http callback`() {
        val uri = "digiid://example.com/auth?x=nonce42&u=1"
        val result = DigiIdRequest.parse(uri)

        assertNotNull(result)
        assertTrue(result!!.isUnsecure)
        assertEquals("http://example.com/auth", result.callbackUrl)
    }

    @Test
    fun `parse returns null for non-digiid URI`() {
        assertNull(DigiIdRequest.parse("https://example.com/callback?x=nonce"))
        assertNull(DigiIdRequest.parse("digibyte:Dxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertNull(DigiIdRequest.parse(""))
        assertNull(DigiIdRequest.parse("bitcoin://something?x=nonce"))
    }

    @Test
    fun `parse returns null for missing nonce`() {
        // Missing the x= param entirely
        val uri = "digiid://example.com/callback"
        assertNull(DigiIdRequest.parse(uri))

        // Has query string but no x param
        val uri2 = "digiid://example.com/callback?u=1"
        assertNull(DigiIdRequest.parse(uri2))
    }

    @Test
    fun `parse extracts domain correctly`() {
        // Simple domain
        val r1 = DigiIdRequest.parse("digiid://mydomain.com/auth?x=abc")
        assertEquals("mydomain.com", r1!!.domain)

        // Domain with path segments
        val r2 = DigiIdRequest.parse("digiid://shop.example.com/digiid/callback/v1?x=xyz")
        assertEquals("shop.example.com", r2!!.domain)

        // Domain with port number — port stripped from domain
        val r3 = DigiIdRequest.parse("digiid://localhost:8080/auth?x=nonce&u=1")
        assertEquals("localhost", r3!!.domain)
        assertEquals("http://localhost:8080/auth", r3.callbackUrl)
    }

    @Test
    fun `parse handles secure callback with u=0 explicitly`() {
        val uri = "digiid://secure.example.com/login?x=mynonce&u=0"
        val result = DigiIdRequest.parse(uri)

        assertNotNull(result)
        assertFalse(result!!.isUnsecure)
        assertEquals("https://secure.example.com/login", result.callbackUrl)
    }

    // ── One parse for the domain, the check and the POST ───────────────────────

    /**
     * Captured from the parser as it was before the single-parse change (4.0.88), for URIs a
     * relying party actually issues. The per-site identity key is derived from callbackUrl and
     * the signed message is rawUri, so for these the two must stay byte-for-byte what they were,
     * and the domain (which picks the legacy or per-site key) must too — a difference here would
     * silently give returning users a new identity.
     *
     * Columns: uri, rawUri, callbackUrl, domain, isUnsecure, nonce.
     */
    private val golden = listOf(
        listOf("digiid://example.com/callback?x=abc123nonce", "digiid://example.com/callback?x=abc123nonce",
            "https://example.com/callback", "example.com", "false", "abc123nonce"),
        listOf("digiid://example.com:8443/callback?x=n1", "digiid://example.com:8443/callback?x=n1",
            "https://example.com:8443/callback", "example.com", "false", "n1"),
        listOf("digiid://example.com:443/callback?x=n1", "digiid://example.com:443/callback?x=n1",
            "https://example.com:443/callback", "example.com", "false", "n1"),
        listOf("digiid://shop.example.com/digiid/callback/v1?x=xyz&u=0", "digiid://shop.example.com/digiid/callback/v1?x=xyz&u=0",
            "https://shop.example.com/digiid/callback/v1", "shop.example.com", "false", "xyz"),
        listOf("digiid://example.com/auth?x=nonce42&u=1", "digiid://example.com/auth?x=nonce42&u=1",
            "http://example.com/auth", "example.com", "true", "nonce42"),
        listOf("digiid://localhost:8080/auth?x=nonce&u=1", "digiid://localhost:8080/auth?x=nonce&u=1",
            "http://localhost:8080/auth", "localhost", "true", "nonce"),
        listOf("digiid://api.digiscope.me/api/digiid/callback?x=7f3a9c0e&u=0", "digiid://api.digiscope.me/api/digiid/callback?x=7f3a9c0e&u=0",
            "https://api.digiscope.me/api/digiid/callback", "api.digiscope.me", "false", "7f3a9c0e"),
        listOf("digiid://example.com/cb?u=0&x=nonce&extra=1", "digiid://example.com/cb?u=0&x=nonce&extra=1",
            "https://example.com/cb", "example.com", "false", "nonce"),
        listOf("digiid://example.com/?x=abc", "digiid://example.com/?x=abc",
            "https://example.com/", "example.com", "false", "abc"),
        listOf("digiid://example.com?x=abc", "digiid://example.com?x=abc",
            "https://example.com", "example.com", "false", "abc"),
        listOf("digiid://sub.domain-with-dash.co.uk/digiid/login.php?x=1a2b3c4d5e", "digiid://sub.domain-with-dash.co.uk/digiid/login.php?x=1a2b3c4d5e",
            "https://sub.domain-with-dash.co.uk/digiid/login.php", "sub.domain-with-dash.co.uk", "false", "1a2b3c4d5e"),
        listOf("digiid://192.168.1.10:3000/digiid?x=ip1&u=1", "digiid://192.168.1.10:3000/digiid?x=ip1&u=1",
            "http://192.168.1.10:3000/digiid", "192.168.1.10", "true", "ip1"),
    )

    @Test
    fun `ordinary URIs keep the signed message, derivation input and domain they had before`() {
        for (row in golden) {
            val (uri, rawUri, callbackUrl, domain, unsecure) = row
            val nonce = row[5]
            val r = DigiIdRequest.parse(uri)
            assertNotNull("rejected an ordinary URI: $uri", r)
            assertEquals("signed message changed for $uri", rawUri, r!!.rawUri)
            assertEquals("key-derivation input changed for $uri", callbackUrl, r.callbackUrl)
            assertEquals("domain changed for $uri", domain, r.domain)
            assertEquals(unsecure.toBoolean(), r.isUnsecure)
            assertEquals(nonce, r.nonce)
        }
    }

    @Test
    fun `the domain shown is exactly the host the callback is sent to`() {
        for (row in golden) {
            val r = DigiIdRequest.parse(row[0])!!
            assertEquals(row[0], r.callbackHttpUrl.host, r.domain)
            assertEquals(row[0], r.callbackUrl.toHttpUrl(), r.callbackHttpUrl)
        }
    }

    @Test
    fun `a URI carrying user info is rejected`() {
        val forms = listOf(
            "digiid://legit.com@evil.com/cb?x=n",
            "digiid://legit.com:443@evil.com/cb?x=n",
            "digiid://digiscope.me:443@evil-digiscope.me/cb?x=n",
            "digiid://api.digiscope.me.attacker.net:8443@node.api.digiscope.me.attacker.net/digiid?x=n",
            "digiid://digiscope.me:443@api.digiscope.me/cb?x=n",
            "digiid://:80@evil.com./cb?x=n",
            "digiid://@evil.com/cb?x=n",
            "digiid://user:pass@example.com/cb?x=n",
            "digiid://user@example.com/cb?x=n",
        )
        for (uri in forms) assertNull("accepted $uri", DigiIdRequest.parse(uri))
    }

    @Test
    fun `a URI whose host is not plain ASCII is rejected`() {
        val forms = listOf(
            "digiid://digiscope.me\u3002evil.com/cb?x=n",   // ideographic full stop
            "digiid://digiscope.me\uFF0Eevil.com/cb?x=n",   // fullwidth full stop
            "digiid://digiscope.me\uFF61evil.com/cb?x=n",   // halfwidth ideographic full stop
            "digiid://\uFF45xample.com/cb?x=n",             // fullwidth letter
            "digiid://ex\u00E4mple.com/cb?x=n",             // an IDN host
            "digiid://example.com\u200B/cb?x=n",            // zero-width space
            "digiid://exa mple.com/cb?x=n",
            "digiid://example.com\t/cb?x=n",
        )
        for (uri in forms) assertNull("accepted $uri", DigiIdRequest.parse(uri))
    }

    @Test
    fun `an authority OkHttp would respell is rejected`() {
        val forms = listOf(
            "digiid://legit.com\\@evil.com/cb?x=n",   // backslash ends the authority for OkHttp
            "digiid://legit.com#@evil.com/cb?x=n",     // so does a fragment
            "digiid://evil%2ecom/cb?x=n",
            "digiid://legit.com%40evil.com/cb?x=n",
            "digiid://example.com:0443/cb?x=n",
            "digiid://[::1]:8080/cb?x=n",
            "digiid:///cb?x=n",
            "digiid://:443/cb?x=n",
            "digiid://example.com:/cb?x=n",
            "digiid://example.com:99999/cb?x=n",
        )
        for (uri in forms) assertNull("accepted $uri", DigiIdRequest.parse(uri))
    }

    @Test
    fun `letter case in the host is accepted, the derivation input kept as written`() {
        val r = DigiIdRequest.parse("digiid://Example.COM/cb?x=n")
        assertNotNull(r)
        assertEquals("example.com", r!!.domain)
        assertEquals("example.com", r.callbackHttpUrl.host)
        assertEquals("https://Example.COM/cb", r.callbackUrl)
    }

    @Test
    fun `DigiIdResult Success carries domain`() {
        val result: DigiIdResult = DigiIdResult.Success("example.com")
        assertTrue(result is DigiIdResult.Success)
        assertEquals("example.com", (result as DigiIdResult.Success).domain)
    }

    @Test
    fun `DigiIdResult Error carries code and message`() {
        val result: DigiIdResult = DigiIdResult.Error(401, "Unauthorized")
        assertTrue(result is DigiIdResult.Error)
        val err = result as DigiIdResult.Error
        assertEquals(401, err.code)
        assertEquals("Unauthorized", err.message)
    }
}
