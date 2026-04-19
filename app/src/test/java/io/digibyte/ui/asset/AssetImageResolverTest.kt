package io.digibyte.ui.asset

import android.net.Uri
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-function tests for [AssetImageResolver]. Android's [Uri] is mocked
 * via mockkStatic so that `Uri.parse(...)` in the resolver returns a mock
 * carrying the scheme/host the tests need to assert on.
 */
class AssetImageResolverTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            val scheme = raw.substringBefore("://", missingDelimiterValue = "")
            val host = raw.substringAfter("://", missingDelimiterValue = raw).substringBefore("/")
            val mock = mockk<Uri>(relaxed = true)
            every { mock.scheme } returns scheme.ifEmpty { null }
            every { mock.host } returns host.ifEmpty { null }
            every { mock.toString() } returns raw
            mock
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        unmockkStatic(Log::class)
    }

    @Test
    fun `null input returns null`() {
        assertNull(AssetImageResolver.resolve(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(AssetImageResolver.resolve(""))
        assertNull(AssetImageResolver.resolve("   "))
    }

    @Test
    fun `https url passes through as string`() {
        val input = "https://example.com/logo.png"
        val result = AssetImageResolver.resolve(input)
        assertTrue("expected String, got ${result?.javaClass?.simpleName}", result is String)
        assertEquals(input, result)
    }

    @Test
    fun `http url passes through as string`() {
        val input = "http://example.com/logo.png"
        assertEquals(input, AssetImageResolver.resolve(input))
    }

    @Test
    fun `ipfs scheme uri is returned as Uri with ipfs scheme and cid host`() {
        val result = AssetImageResolver.resolve("ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG")
        assertTrue(result is Uri)
        assertEquals("ipfs", (result as Uri).scheme)
        assertEquals("QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG", result.host)
    }

    @Test
    fun `slash ipfs path is rewritten to ipfs scheme`() {
        val result = AssetImageResolver.resolve("/ipfs/QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG")
        assertTrue(result is Uri)
        assertEquals("ipfs", (result as Uri).scheme)
        assertEquals("QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG", result.host)
    }

    @Test
    fun `bare cidv0 is wrapped in ipfs uri`() {
        val cid = "QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        val result = AssetImageResolver.resolve(cid)
        assertTrue(result is Uri)
        assertEquals("ipfs", (result as Uri).scheme)
        assertEquals(cid, result.host)
    }

    @Test
    fun `bare cidv1 base32 is wrapped in ipfs uri`() {
        val cid = "bafybeigdyrzt56landrfsmkojbm2xbfzber5jl7mqdauzwpnrv4i4l7g5m"
        val result = AssetImageResolver.resolve(cid)
        assertTrue(result is Uri)
        assertEquals(cid, (result as Uri).host)
    }

    @Test
    fun `whitespace around input is trimmed`() {
        val cid = "QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        val result = AssetImageResolver.resolve("  $cid  ")
        assertTrue(result is Uri)
        assertEquals(cid, (result as Uri).host)
    }

    @Test
    fun `garbage string returns null`() {
        assertNull(AssetImageResolver.resolve("notacid"))
        assertNull(AssetImageResolver.resolve("data:image/png;base64,iVBOR"))
        assertNull(AssetImageResolver.resolve("file:///tmp/foo.png"))
    }
}
