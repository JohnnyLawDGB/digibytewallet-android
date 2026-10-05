package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.core.network.DigiScopePins
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * [SeederClient]: the seeder list is fetched through the DigiScope-pinned client with a bounded body.
 *
 * The answer is served by an application interceptor on the base client, so no socket is opened;
 * what a pinned connection does on the wire is OkHttp's, what this pins is that the client the
 * request is made with carries the pins, that it is the shared client otherwise, and how much of
 * an answer is read before it is accepted or refused.
 */
class SeederClientTest {

    /** The bound a seeder answer must fit in: 256 KiB. A thousand-peer list is about 100 KB. */
    private val bound = 256L * 1024

    private val seederUrl = "https://${DigiScopePins.HOST}/api/peers?capability=filter"

    // ── the client ─────────────────────────────────────────────────────────────

    @Test fun `the seeder client carries the DigiScope pins`() {
        val base = OkHttpClient()
        assertTrue("the shared client is expected to carry no pins of its own", base.certificatePinner.pins.isEmpty())

        val pins = SeederClient(base).client.certificatePinner.pins
        assertTrue("the seeder client carries no pins", pins.isNotEmpty())
        assertEquals(DigiScopePins.certificatePinner().pins, pins)
    }

    /** GUARD: what the shared client carries (Tor proxy, DNS, the whole-call bound) reaches the seeder request. */
    @Test fun `the seeder client keeps what the shared client carries`() {
        val dns = object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname)
        }
        val proxies = object : ProxySelector() {
            override fun select(uri: java.net.URI?) = listOf(java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
        }
        val served = Served(body = "{}".toResponseBody(JSON))
        val base = OkHttpClient.Builder()
            .dns(dns)
            .proxySelector(proxies)
            .callTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(served)
            .build()

        val client = SeederClient(base).client
        assertSame(dns, client.dns)
        assertSame(proxies, client.proxySelector)
        assertEquals(base.callTimeoutMillis, client.callTimeoutMillis)
        assertEquals(base.connectTimeoutMillis, client.connectTimeoutMillis)
        assertEquals(base.readTimeoutMillis, client.readTimeoutMillis)
        assertEquals(base.interceptors, client.interceptors)
    }

    @Test fun `the seeder client does not follow redirects`() {
        // A pinner checks only the hosts it holds pins for, so a redirect followed to another host
        // would be served with no identity check; the list must come from the pinned server itself.
        val base = OkHttpClient()
        assertTrue("the shared client is expected to follow redirects", base.followRedirects && base.followSslRedirects)

        val client = SeederClient(base).client
        assertTrue("the seeder client follows redirects", !client.followRedirects)
        assertTrue("the seeder client follows https redirects", !client.followSslRedirects)
    }

    /** GUARD: a redirect answer is not a success, so it yields no list and nothing is followed. */
    @Test fun `a redirect from the seeder yields no list`() {
        val reasons = mutableListOf<String>()
        val served = Served(status = 302, body = "".toResponseBody(JSON), location = "https://example.invalid/api/peers")

        assertNull(SeederClient(clientServing(served)).fetch(seederUrl) { reasons += it })
        assertEquals("the redirect was followed", 1, served.hits)
        assertTrue(reasons.single(), reasons.single().contains("302"))
    }

    @Test fun `a url on a host the pins do not cover is not requested`() {
        val served = Served(body = peersJson(3).toResponseBody(JSON))
        val reasons = mutableListOf<String>()

        val body = SeederClient(clientServing(served)).fetch("https://example.invalid/api/peers") { reasons += it }

        assertNull(body)
        assertEquals("the request went out to a host with no pins", 0, served.hits)
        assertEquals(1, reasons.size)
    }

    // ── the body ───────────────────────────────────────────────────────────────

    @Test fun `a body under the bound is returned as sent`() {
        val json = peersJson(16)
        val served = Served(body = json.toResponseBody(JSON))

        assertEquals(json, SeederClient(clientServing(served)).fetch(seederUrl))
        assertEquals(1, served.hits)
    }

    @Test fun `a body at the bound is returned whole, with or without a declared length`() {
        val json = peersJsonOfLength(bound)
        assertEquals(bound, json.length.toLong())
        assertEquals("the default bound is not 256 KiB", bound, SeederClient.MAX_BODY_BYTES)

        assertEquals(json, SeederClient(clientServing(Served(body = json.toResponseBody(JSON)))).fetch(seederUrl))
        assertEquals(json, SeederClient(clientServing(Served(body = undeclared(json)))).fetch(seederUrl))
    }

    @Test fun `a body one byte over the bound yields no list`() {
        val json = peersJsonOfLength(bound + 1)
        val reasons = mutableListOf<String>()

        assertNull(SeederClient(clientServing(Served(body = json.toResponseBody(JSON)))).fetch(seederUrl) { reasons += it })
        assertNull(SeederClient(clientServing(Served(body = undeclared(json)))).fetch(seederUrl) { reasons += it })
        assertEquals(2, reasons.size)
    }

    @Test fun `a body far over the bound with no declared length yields no list and is not read whole`() {
        val total = 2L * 1024 * 1024
        val counting = CountingSource(total)
        val served = Served(body = streamed(counting))
        val reasons = mutableListOf<String>()

        val body = SeederClient(clientServing(served)).fetch(seederUrl) { reasons += it }

        assertNull(body)
        assertEquals(1, reasons.size)
        assertTrue("the whole answer was read: ${counting.read} bytes", counting.read < total)
        // The reader may run one buffer segment past the bound to learn that it is exceeded, no more.
        assertTrue("read ${counting.read} bytes past a $bound-byte bound", counting.read <= bound + 16 * 1024)
    }

    @Test fun `a body far over the bound with a declared length yields no list without being read`() {
        val counting = CountingSource(2L * 1024 * 1024)
        val served = Served(body = streamed(counting, declaredLength = counting.total))

        assertNull(SeederClient(clientServing(served)).fetch(seederUrl))
        assertEquals("the declared length was not enough to refuse the answer", 0L, counting.read)
    }

    // ── the answer ─────────────────────────────────────────────────────────────

    @Test fun `an unsuccessful answer yields no list`() {
        val reasons = mutableListOf<String>()
        val served = Served(status = 503, body = "unavailable".toResponseBody(JSON))

        assertNull(SeederClient(clientServing(served)).fetch(seederUrl) { reasons += it })
        assertTrue(reasons.single(), reasons.single().contains("503"))
    }

    @Test fun `a request that fails yields no list and says why`() {
        // The shape of a server that does not prove the pinned identity: the call ends in an
        // SSLPeerUnverifiedException before any body is read.
        val reasons = mutableListOf<String>()
        val failing = Interceptor { throw SSLPeerUnverifiedException("Certificate pinning failure!") }

        assertNull(SeederClient(clientServing(failing)).fetch(seederUrl) { reasons += it })
        assertTrue(reasons.single(), reasons.single().contains("Certificate pinning failure"))
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private companion object {
        val JSON = "application/json".toMediaType()
    }

    /** Serves every request with [status] and [body], never opening a socket. */
    private class Served(val status: Int = 200, val body: ResponseBody, val location: String? = null) : Interceptor {
        var hits = 0
        override fun intercept(chain: Interceptor.Chain): Response {
            hits++
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .apply { if (location != null) header("Location", location) }
                .body(body)
                .build()
        }
    }

    private fun clientServing(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    /** The seeder's shape with [count] peers. */
    private fun peersJson(count: Int): String {
        val peers = (0 until count).joinToString(",") { i ->
            """{"ip":"10.0.${i / 256}.${i % 256}","port":12024,"services_hex":"0x44d","peer_capability":"filter"}"""
        }
        return """{"capability":"filter","peers":[$peers]}"""
    }

    /** A well-formed seeder answer padded to exactly [length] bytes (ASCII, so bytes == chars). */
    private fun peersJsonOfLength(length: Long): String {
        val head = """{"capability":"filter","note":""""
        val tail = """","peers":[{"ip":"10.0.0.1","port":12024,"services_hex":"0x44d"}]}"""
        val padding = length - head.length - tail.length
        require(padding >= 0)
        return head + "x".repeat(padding.toInt()) + tail
    }

    /** [text] as a body without a declared length, as a chunked answer arrives. */
    private fun undeclared(text: String): ResponseBody = object : ResponseBody() {
        override fun contentLength() = -1L
        override fun contentType() = JSON
        override fun source(): BufferedSource = Buffer().writeUtf8(text)
    }

    /** A body read from [source], declaring [declaredLength] (none by default). */
    private fun streamed(source: Source, declaredLength: Long = -1L): ResponseBody = object : ResponseBody() {
        override fun contentLength() = declaredLength
        override fun contentType() = JSON
        override fun source(): BufferedSource = source.buffer()
    }

    /** [total] bytes of filler, counting how many of them were read. */
    private class CountingSource(val total: Long) : Source {
        var read = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = minOf(byteCount, total - read)
            if (n <= 0) return -1L
            sink.write(ByteArray(n.toInt()) { 'x'.code.toByte() })
            read += n
            return n
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {}
    }
}
