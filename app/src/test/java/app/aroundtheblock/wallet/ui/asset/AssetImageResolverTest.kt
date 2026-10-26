package app.aroundtheblock.wallet.ui.asset

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

    /** 4.1.2 row 28: an issuer-chosen host is never contacted (it would learn who views the asset). */
    @Test
    fun `an https url on an issuer host is not loaded`() {
        assertNull(AssetImageResolver.resolve("https://example.com/logo.png"))
    }

    @Test
    fun `an http url is not loaded`() {
        assertNull(AssetImageResolver.resolve("http://example.com/logo.png"))
    }

    @Test
    fun `an ipfs gateway url is fetched by its cid through the wallet's gateways`() {
        val cid = "QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        val path = AssetImageResolver.resolve("https://ipfs.issuer.example/ipfs/$cid/logo.png")
        assertTrue(path is Uri)
        assertEquals("ipfs://$cid/logo.png", path.toString())
        val sub = AssetImageResolver.resolve("https://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi.ipfs.issuer.example/logo.png")
        assertEquals("ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi/logo.png", sub.toString())
    }

    @Test
    fun `a gateway-shaped url with no cid is not loaded`() {
        assertNull(AssetImageResolver.resolve("https://ipfs.issuer.example/ipfs/not-a-cid/logo.png"))
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
        assertNull(AssetImageResolver.resolve("file:///tmp/foo.png"))
    }

    @Test
    fun `data URIs are rejected whatever media type they declare`() {
        // Coil 2.x has no data-URI fetcher, so none of these could ever render; null keeps the
        // placeholder and keeps the detail screen from offering an empty viewer.
        assertNull(AssetImageResolver.resolve("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"))
        assertNull(AssetImageResolver.resolve("data:text/html,<p>x</p>"))
        assertNull(AssetImageResolver.resolve("data:image/svg+xml;utf8,<svg/>"))
        assertNull(AssetImageResolver.resolve("  DATA:image/png;base64,AAAA"))
    }
}
