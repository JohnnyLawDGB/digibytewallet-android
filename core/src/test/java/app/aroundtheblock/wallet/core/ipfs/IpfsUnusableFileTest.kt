package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ASSET-MEDIA-FIXES, the client's side: a file the wallet cannot use is not downloaded again.
 *
 * Measured on 4.1.5 with asset 5364, whose "icon" is a 4,265,952-byte MP3: every image instance on
 * screen downloaded it whole and discarded it (2 instances, 2 downloads; 4, 4), and again on each
 * return to the list. The 4.1.5 memory of a gateway's 404 did not cover it, because the gateway
 * HAD the file. This extends that one memory ([GatewayMissMemory], inside [IpfsClient], shared by
 * every caller) rather than adding a second:
 *
 *  - a gateway whose answer was refused for size is not asked for that CID again inside the wait;
 *  - a CID whose bytes turned out not to be a picture is remembered as that, for every screen;
 *  - callers that want the same CID at the same moment share one request.
 *
 * The clock is the test's and the gateways are an interceptor: nothing waits, nothing leaves the process.
 */
class IpfsUnusableFileTest {

    private val content = "a picture, for the purposes of this test".toByteArray()
    private val cid = IpfsClientTest.buildCidV1(content, 0x12, "SHA-256")
    /** dag-pb: accepted from the trusted gateway on its identity, as every real media file is. */
    private val dagPbCid = "bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"

    private val trusted = "https://trusted.example/api/ipfs/{cid}"
    private val public = "https://public.example/ipfs/{cid}?format=raw"

    private val asked = ArrayList<HttpUrl>()
    private val bodyBytesRead = AtomicLong()
    private val answers = HashMap<String, () -> ResponseBody>()
    private var hold: CountDownLatch? = null
    private var now = 1_000_000L

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        synchronized(asked) { asked += request.url }
        hold?.await(10, TimeUnit.SECONDS)
        val body = answers[request.url.host]?.invoke()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200).message("x")
            .body(body ?: ByteArray(0).toResponseBody(OCTETS)).build()
    }.build()

    private val OCTETS = "application/octet-stream".toMediaType()

    /** A body that declares [declared] bytes (-1: none) and counts what is actually read from it. */
    private fun body(size: Long, declared: Long): ResponseBody = object : ResponseBody() {
        override fun contentType() = OCTETS
        override fun contentLength() = declared
        override fun source(): BufferedSource = object : ForwardingSource(ZeroSource(size)) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it > 0) bodyBytesRead.addAndGet(it) }
        }.buffer()
    }

    /** [size] zero bytes, never held whole. */
    private class ZeroSource(private var left: Long) : okio.Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (left <= 0) return -1
            val n = minOf(left, byteCount, 8192L)
            sink.write(ByteArray(n.toInt()))
            left -= n
            return n
        }
        override fun timeout() = okio.Timeout.NONE
        override fun close() {}
    }

    private fun client(gateways: List<String> = listOf(trusted, public)) =
        IpfsClient(http, CidVerifier(), gateways, trustedGatewayPrefixes = listOf("https://trusted.example/"), clock = { now })

    private fun askedOf(host: String) = synchronized(asked) { asked.count { it.host == host } }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private val OVER = IpfsClient.MAX_RESPONSE_SIZE + 1

    // ---------------------------------------------------------------------------------------------
    // Refused for size
    // ---------------------------------------------------------------------------------------------

    /** Assets 5361 to 5363: MP3s of 5.7 to 7.3 MB, declared as such by the gateway. */
    @Test fun `a file refused on its declared length is reported as that, unread`() = runBlocking {
        answers["trusted.example"] = { body(7_296_078, declared = 7_296_078) }
        val fetched = client(listOf(trusted)).fetch(dagPbCid)
        assertNull(fetched.bytes)
        assertEquals(1, fetched.gatewaysFailed)
        assertEquals("the one failure is a size refusal, not an unusable or tampered answer", 1, fetched.refusedForSize)
        assertEquals("refused before a byte of the body was read", 0L, bodyBytesRead.get())
    }

    @Test fun `a gateway that refused for size is not asked for that file again on every draw`() = runBlocking {
        answers["trusted.example"] = { body(7_296_078, declared = 7_296_078) }
        val client = client(listOf(trusted))
        repeat(40) { assertNull(client.fetch(dagPbCid).bytes) }
        assertEquals(1, askedOf("trusted.example"))
        // The draws that asked nobody report no failure at all.
        val later = client.fetch(dagPbCid)
        assertEquals(0, later.gatewaysFailed)
        assertEquals(0, later.refusedForSize)
    }

    /** No declared length: the read stops one byte past the cap. That is 5 MiB of data, so it is remembered too. */
    @Test fun `a file that ran past the cap with no declared length is not streamed again`() = runBlocking {
        answers["trusted.example"] = { body(OVER + 100_000, declared = -1) }
        val client = client(listOf(trusted))
        repeat(10) { assertNull(client.fetch(dagPbCid).bytes) }
        assertEquals(1, askedOf("trusted.example"))
        assertTrue("read once, to the cap and no further", bodyBytesRead.get() in IpfsClient.MAX_RESPONSE_SIZE..OVER + 8192)
    }

    @Test fun `a size refusal is per gateway and it ends`() = runBlocking {
        answers["trusted.example"] = { body(OVER, declared = OVER) }
        answers["public.example"] = { content.toResponseBody(OCTETS) }
        val client = client()
        // The other gateway is still asked, and its answer still verified.
        assertArrayEquals(content, client.fetch(cid).bytes)
        assertArrayEquals(content, client.fetch(cid).bytes)
        assertEquals(1, askedOf("trusted.example"))
        assertEquals(2, askedOf("public.example"))
        // It is the same memory as a 404's: it ends after the wait.
        now += IpfsClient.MISS_FIRST_MS
        client.fetch(cid)
        assertEquals(2, askedOf("trusted.example"))
    }

    /** An answer of exactly the cap is a file, not a refusal. */
    @Test fun `a file of exactly the cap is fetched and nothing is remembered`() = runBlocking {
        answers["trusted.example"] = { body(IpfsClient.MAX_RESPONSE_SIZE, declared = IpfsClient.MAX_RESPONSE_SIZE) }
        val client = client(listOf(trusted))
        repeat(2) {
            val fetched = client.fetch(dagPbCid)
            assertEquals(IpfsClient.MAX_RESPONSE_SIZE.toInt(), fetched.bytes?.size)
            assertEquals(0, fetched.refusedForSize)
        }
        assertEquals(2, askedOf("trusted.example"))
    }

    // ---------------------------------------------------------------------------------------------
    // Not a picture
    // ---------------------------------------------------------------------------------------------

    @Test fun `a file found not to be a picture is remembered as that, under any spelling of its CID`() {
        val client = client()
        assertFalse(client.isKnownNotAPicture(dagPbCid))
        client.rememberNotAPicture("ipfs://$dagPbCid")
        assertTrue(client.isKnownNotAPicture(dagPbCid))
        assertTrue(client.isKnownNotAPicture("/ipfs/$dagPbCid"))
        assertFalse("another file is not", client.isKnownNotAPicture(cid))
        // A value that is not a CID is remembered as nothing and is never "known".
        client.rememberNotAPicture("null")
        assertFalse(client.isKnownNotAPicture("null"))
    }

    /** The bytes of a CID do not change, so the verdict outlives a gateway's "not here" by far; it is still not for ever. */
    @Test fun `the verdict lasts a day and no longer`() {
        val client = client()
        client.rememberNotAPicture(dagPbCid)
        now += IpfsClient.MISS_TTL_MS * 3
        assertTrue(client.isKnownNotAPicture(dagPbCid))
        now = 1_000_000L + IpfsClient.NOT_A_PICTURE_MS - 1
        assertTrue(client.isKnownNotAPicture(dagPbCid))
        now += 1
        assertFalse(client.isKnownNotAPicture(dagPbCid))
    }

    @Test fun `the verdict does not stop the file being fetched as a file`() = runBlocking {
        answers["public.example"] = { content.toResponseBody(OCTETS) }
        val client = client(listOf(public))
        client.rememberNotAPicture(cid)
        assertArrayEquals("a metadata document or a later player still gets the bytes", content, client.fetch(cid).bytes)
    }

    @Test fun `the memory stays bounded`() {
        val client = client()
        val cids = (0 until IpfsClient.MISS_MAX_ENTRIES + 50).map { IpfsClientTest.buildCidV1("file $it".toByteArray(), 0x12, "SHA-256") }
        cids.forEach { client.rememberNotAPicture(it) }
        assertEquals(IpfsClient.MISS_MAX_ENTRIES, cids.count { client.isKnownNotAPicture(it) })
        assertTrue("the youngest are the ones kept", client.isKnownNotAPicture(cids.last()))
    }

    // ---------------------------------------------------------------------------------------------
    // One request for callers that ask together
    // ---------------------------------------------------------------------------------------------

    /** The list card, the detail hero and the viewer each ask for the picture; together, they ask once. */
    @Test fun `four callers asking for one file at the same moment make one request`() = runBlocking {
        answers["public.example"] = { content.toResponseBody(OCTETS) }
        val client = client(listOf(public))
        hold = CountDownLatch(1)
        val all = (1..4).map { async(Dispatchers.IO) { client.fetch(cid) } }
        // Let all four arrive while the one request is held open.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (askedOf("public.example") == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        Thread.sleep(300)
        hold!!.countDown()
        for (fetched in all.awaitAll()) assertArrayEquals(content, fetched.bytes)
        assertEquals("requests: $asked", 1, askedOf("public.example"))
        // Not a cache: a later caller asks again.
        hold = null
        client.fetch(cid)
        assertEquals(2, askedOf("public.example"))
    }

    // ---------------------------------------------------------------------------------------------
    // A size already learned
    // ---------------------------------------------------------------------------------------------

    @Test fun `the size of a file is known from an answer already made, and from nothing else`() = runBlocking {
        val client = client(listOf(trusted, public))
        assertNull("nothing was asked: nothing is known", client.knownSize(dagPbCid))
        assertEquals(0, asked.size)

        // The trusted gateway serves the file itself: its declared length is the file's.
        answers["trusted.example"] = { body(7_296_078, declared = 7_296_078) }
        client.fetch(dagPbCid)
        assertEquals(7_296_078L, client.knownSize("ipfs://$dagPbCid"))

        // A file that was fetched is as long as its bytes.
        answers["trusted.example"] = { content.toResponseBody(OCTETS) }
        client.fetch(cid)
        assertEquals(content.size.toLong(), client.knownSize(cid))

        val before = asked.size
        client.knownSize(dagPbCid); client.knownSize(cid); client.knownSize("null"); client.knownSize(null)
        assertEquals("asking for a size makes no request", before, asked.size)
    }

    /** A public gateway's `?format=raw` answer for a dag-pb CID is the root block, not the file. */
    @Test fun `a public gateway's block is not taken for the file's size`() = runBlocking {
        answers["public.example"] = { body(OVER, declared = OVER) }
        val client = client(listOf(public))
        client.fetch(dagPbCid)
        assertNull(client.knownSize(dagPbCid))
    }
}
