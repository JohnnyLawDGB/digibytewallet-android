package io.digibyte.core.model

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
