package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ASSET-MEDIA-FIXES, second review: what the TRUSTED gateway's answer may be taken for.
 *
 * R3. For a dag-pb CID the trusted gateway is believed on its pinned identity: nothing checks the
 * bytes against the CID. So "a 200" is the file only when it is all of the file. These tests serve
 * real HTTP over a real socket, because whether a body is complete is decided by how it is framed
 * on the wire, which a canned response object does not have.
 *
 * R2 and R4. What a fetch reports about the trusted gateway in particular: that IT refused the
 * file for size, and that IT said the CID is not there. With the shipped gateway list.
 */
class IpfsTrustedAnswerTest {

    private val OCTETS = "application/octet-stream".toMediaType()

    /** A dag-pb CID (CIDv0): taken from the trusted gateway on its identity. */
    private val rootBlock = byteArrayOf(0x12, 0x2a) + "links to the blocks of a picture".toByteArray()
    private val dagPb = IpfsClientTest.buildCidV0(rootBlock)
    private val file = ByteArray(40_000) { (it % 251).toByte() }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ---------------------------------------------------------------------------------------------
    // R3, over a socket
    // ---------------------------------------------------------------------------------------------

    /**
     * Serves each connection [head] and then [body], and closes it. The request is read first, so
     * the close is an orderly end of stream and not a reset.
     */
    private class OneAnswerServer(private val head: String, private val body: ByteArray) : java.io.Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        @Volatile var served = 0
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    s.use {
                        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                        served++
                        val out = it.getOutputStream()
                        out.write(head.toByteArray(Charsets.ISO_8859_1))
                        out.write(body)
                        out.flush()
                        it.shutdownOutput()
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.join(2_000) }
    }

    private fun chunked(bytes: ByteArray, finished: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray()); out.write(bytes); out.write("\r\n".toByteArray())
        if (finished) out.write("0\r\n\r\n".toByteArray())
        return out.toByteArray()
    }

    /** One fetch of [dagPb] from a trusted gateway that answers [head] + [body], and nobody else. */
    private fun fetchFromSocket(head: String, body: ByteArray): Pair<IpfsClient, IpfsClient.Fetched> =
        OneAnswerServer(head, body).use { server ->
            val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
            val base = "http://127.0.0.1:${server.port}/"
            val client = IpfsClient(http, CidVerifier(), listOf("${base}api/ipfs/{cid}"), trustedGatewayPrefixes = listOf(base))
            val fetched = runBlocking { client.fetch(dagPb) }
            assertEquals("the gateway was asked", 1, server.served)
            client to fetched
        }

    private fun assertFailedFetch(what: String, head: String, body: ByteArray) {
        val (client, fetched) = fetchFromSocket(head, body)
        assertNull("$what: not returned as the file", fetched.bytes)
        assertFalse("$what", fetched.wholeFile)
        assertEquals("$what: a failed fetch of the one gateway asked", 1, fetched.gatewaysFailed)
        assertEquals("$what: not a size refusal", 0, fetched.refusedForSize)
        assertEquals("$what: not an answer about the CID", 0, fetched.saidNotHere)
        assertNull("$what: its length is not the file's", client.knownSize(dagPb))
    }

    private fun assertTheWholeFile(what: String, head: String, body: ByteArray) {
        val (client, fetched) = fetchFromSocket(head, body)
        assertArrayEquals(what, file, fetched.bytes)
        assertTrue("$what: the complete answer of the trusted gateway is the file", fetched.wholeFile)
        assertEquals(what, file.size.toLong(), client.knownSize(dagPb))
    }

    @Test fun `a complete answer with a declared length is the whole file`() =
        assertTheWholeFile("content-length", "HTTP/1.1 200 OK\r\nContent-Length: ${file.size}\r\nConnection: close\r\n\r\n", file)

    @Test fun `a complete chunked answer is the whole file`() =
        assertTheWholeFile("chunked", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", chunked(file, finished = true))

    @Test fun `a body shorter than its declared length is a failed fetch`() =
        assertFailedFetch("short of content-length", "HTTP/1.1 200 OK\r\nContent-Length: ${file.size}\r\nConnection: close\r\n\r\n", file.copyOf(12_000))

    @Test fun `a chunked body that ends without its last chunk is a failed fetch`() =
        assertFailedFetch("chunked, cut", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", chunked(file.copyOf(12_000), finished = false))

    /**
     * No length and not chunked: the body runs until the connection closes, and a connection that
     * dropped a third of the way through reads exactly like one that finished. The two cannot be
     * told apart, so neither is taken on the pin.
     */
    @Test fun `a body with no declared length that the connection ended early is a failed fetch`() =
        assertFailedFetch("no length, cut", "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n", file.copyOf(12_000))

    @Test fun `an empty 200 is a failed fetch, however it is framed`() {
        assertFailedFetch("empty, content-length 0", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", ByteArray(0))
        assertFailedFetch("empty, chunked", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", "0\r\n\r\n".toByteArray())
        assertFailedFetch("empty, no length", "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n", ByteArray(0))
    }

    /** What marks the end of a body, and the one shape in which nothing does. */
    @Test fun `the end of a body is marked by a length, by chunking or by HTTP 2, and by nothing else`() {
        fun response(protocol: Protocol, vararg headers: Pair<String, String>): Response {
            val b = Response.Builder().request(okhttp3.Request.Builder().url("https://trusted.example/x").build())
                .protocol(protocol).code(200).message("OK").body(ByteArray(1).toResponseBody(OCTETS))
            for ((k, v) in headers) b.header(k, v)
            return b.build()
        }
        assertTrue(IpfsClient.endIsMarked(response(Protocol.HTTP_1_1), declared = 10))
        assertTrue(IpfsClient.endIsMarked(response(Protocol.HTTP_1_1, "Transfer-Encoding" to "chunked"), declared = -1))
        assertTrue(IpfsClient.endIsMarked(response(Protocol.HTTP_2), declared = -1))
        // Compressed: OkHttp drops Content-Length from what the caller sees; the network response has it.
        val gz = response(Protocol.HTTP_1_1).newBuilder()
            .networkResponse(response(Protocol.HTTP_1_1, "Content-Length" to "123", "Content-Encoding" to "gzip").newBuilder().body(null).build()).build()
        assertTrue(IpfsClient.endIsMarked(gz, declared = -1))
        assertFalse(IpfsClient.endIsMarked(response(Protocol.HTTP_1_1), declared = -1))
        assertFalse(IpfsClient.endIsMarked(response(Protocol.HTTP_1_0), declared = -1))
    }

    // ---------------------------------------------------------------------------------------------
    // R3: what is NOT changed. A hash-verified answer needs no framing: the hash is the check.
    // ---------------------------------------------------------------------------------------------

    @Test fun `a raw-codec file is verified by its hash whatever the framing, and a cut one fails the hash`() {
        val raw = IpfsClientTest.buildCidV1(file, 0x12, "SHA-256")
        fun fetchRaw(body: ByteArray) = OneAnswerServer("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n", body).use { server ->
            val base = "http://127.0.0.1:${server.port}/"
            val client = IpfsClient(OkHttpClient(), CidVerifier(), listOf("${base}api/ipfs/{cid}"), trustedGatewayPrefixes = listOf(base))
            runBlocking { client.fetch(raw) }
        }
        val whole = fetchRaw(file)
        assertArrayEquals(file, whole.bytes)
        assertTrue(whole.wholeFile)
        assertNull(fetchRaw(file.copyOf(12_000)).bytes)
    }

    // ---------------------------------------------------------------------------------------------
    // R2 and R4: what a fetch says about the trusted gateway, with the shipped list
    // ---------------------------------------------------------------------------------------------

    private val TRUSTED = "api.digiscope.me"
    private val PUBLIC = listOf("trustless-gateway.link", "dweb.link", "ipfs.io")
    private val answers = HashMap<String, () -> Response.Builder>()
    private val asked = ArrayList<String>()
    private var now = 1_000_000L

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val host = chain.request().url.host
        synchronized(asked) { asked += host }
        val answer = answers[host] ?: throw java.net.ConnectException("Failed to connect to $host")
        answer().request(chain.request()).protocol(Protocol.HTTP_1_1).message("x").build()
    }.build()

    /** The shipped gateways and the shipped trusted prefix; only the clock is the test's. */
    private fun shipped() = IpfsClient(http, CidVerifier(), IpfsClient.DEFAULT_GATEWAYS, IpfsClient.TRUSTED_GATEWAY_PREFIXES, clock = { now })

    private fun status(code: Int, body: ByteArray = ByteArray(0)): () -> Response.Builder =
        { Response.Builder().code(code).body(body.toResponseBody(OCTETS)) }

    private fun declares(declared: Long): () -> Response.Builder = {
        Response.Builder().code(200).body(object : ResponseBody() {
            override fun contentType() = OCTETS
            override fun contentLength() = declared
            override fun source() = Buffer()
        })
    }

    private fun askedOf(host: String) = synchronized(asked) { asked.count { it == host } }

    @Test fun `the trusted gateway's size refusal is the reason reported whatever the public gateways answer`() = runBlocking {
        val publicBehaviours: Map<String, (() -> Response.Builder)?> =
            mapOf("unreachable" to null, "504" to status(504), "404" to status(404), "root block" to status(200, rootBlock), "too large too" to declares(9_000_000))
        for ((what, publicDoes) in publicBehaviours) {
            answers.clear(); asked.clear()
            answers[TRUSTED] = declares(7_296_078)
            if (publicDoes != null) for (host in PUBLIC) answers[host] = publicDoes
            val client = shipped()

            val first = client.fetch(dagPb)
            assertNull(what, first.bytes)
            assertTrue("$what: the trusted gateway refused it for size", first.trustedRefusedForSize)
            assertEquals("$what: the one gateway asked", 1, first.gatewaysFailed)
            assertEquals(what, 1, first.refusedForSize)

            // Every later draw inside the wait: nobody asked, and the reason is still the size.
            repeat(40) {
                val later = client.fetch(dagPb)
                assertNull(later.bytes)
                assertTrue("$what: still the reason", later.trustedRefusedForSize)
                assertEquals("$what: nothing was asked, so nothing failed", 0, later.gatewaysFailed)
            }
            assertEquals("$what: the trusted gateway, once", 1, askedOf(TRUSTED))
            for (host in PUBLIC) assertEquals("$what: $host never", 0, askedOf(host))
            assertEquals(7_296_078L, client.knownSize(dagPb))

            // It ends with the trusted gateway's own wait, and the question is put to it again.
            now += IpfsClient.MISS_FIRST_MS
            assertTrue(client.fetch(dagPb).trustedRefusedForSize)
            assertEquals("$what: asked again after ${IpfsClient.MISS_FIRST_MS / 60_000} min", 2, askedOf(TRUSTED))
            for (host in PUBLIC) assertEquals(0, askedOf(host))
        }
    }

    /** A 404 is a different answer: the public gateways ARE still asked, and no size is reported. */
    @Test fun `a remembered 404 from the trusted gateway is not taken for a size refusal`() = runBlocking {
        answers[TRUSTED] = status(404)
        for (host in PUBLIC) answers[host] = status(200, rootBlock)
        val client = shipped()
        repeat(3) {
            val fetched = client.fetch(dagPb)
            assertArrayEquals(rootBlock, fetched.bytes)
            assertFalse(fetched.trustedRefusedForSize)
            assertFalse(fetched.wholeFile)
        }
        assertEquals(1, askedOf(TRUSTED))
        assertEquals(3, askedOf(PUBLIC[0]))
    }

    /** For a content-verified CID any gateway can serve the file, so the others are still asked. */
    @Test fun `a raw-codec file the trusted gateway refused for size is still asked of the public gateways`() = runBlocking {
        val raw = IpfsClientTest.buildCidV1(file, 0x12, "SHA-256")
        answers[TRUSTED] = declares(7_296_078)
        answers[PUBLIC[1]] = status(200, file)
        val client = shipped()
        val fetched = client.fetch(raw)
        assertArrayEquals(file, fetched.bytes)
        assertFalse("a file was fetched: nothing to report", fetched.trustedRefusedForSize)

        // ...and when none of them gives it, the size is still the reason.
        answers.remove(PUBLIC[1])
        now += IpfsClient.MISS_TTL_MS * 3
        val none = shipped().fetch(raw)
        assertNull(none.bytes)
        assertTrue(none.trustedRefusedForSize)
        assertEquals(4, none.gatewaysFailed)
        assertEquals(1, none.refusedForSize)
    }

    @Test fun `only the trusted gateway's own not-here answer is reported as the trusted gateway's`() = runBlocking {
        data class Case(val trusted: (() -> Response.Builder)?, val public: (() -> Response.Builder)?, val expected: Boolean, val anyNotHere: Int)
        val cases = mapOf(
            "trusted 404, public 404" to Case(status(404), status(404), true, 4),
            "trusted 400, public unreachable" to Case(status(400), null, true, 1),
            "trusted 404, public root block" to Case(status(404), status(200, rootBlock), true, 1),
            // The trusted gateway is FAILING, and the public ones say 404: nothing about the document.
            "trusted 503, public 404" to Case(status(503), status(404), false, 3),
            "trusted 500, public 400" to Case(status(500), status(400), false, 3),
            "trusted 302, public 404" to Case(status(302), status(404), false, 3),
            "trusted unreachable, public 404" to Case(null, status(404), false, 3),
            "trusted empty 200, public 404" to Case(status(200), status(404), false, 3),
            "trusted too large, public 404" to Case(declares(9_000_000), status(404), false, 0),
            "all unreachable" to Case(null, null, false, 0),
        )
        for ((what, case) in cases) {
            answers.clear()
            if (case.trusted != null) answers[TRUSTED] = case.trusted
            if (case.public != null) for (host in PUBLIC) answers[host] = case.public
            val fetched = shipped().fetch(dagPb)
            assertEquals(what, case.expected, fetched.trustedSaidNotHere)
            assertEquals("$what: the count over every gateway is unchanged", case.anyNotHere, fetched.saidNotHere)
        }
        // A trusted gateway inside its remembered 404 is not asked, so it has not said so in THAT fetch.
        answers.clear()
        answers[TRUSTED] = status(404); for (host in PUBLIC) answers[host] = status(404)
        val client = shipped()
        assertTrue(client.fetch(dagPb).trustedSaidNotHere)
        assertFalse(client.fetch(dagPb).trustedSaidNotHere)
    }
}
