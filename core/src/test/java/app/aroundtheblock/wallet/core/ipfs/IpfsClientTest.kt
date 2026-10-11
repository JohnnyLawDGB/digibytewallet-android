package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

/**
 * Unit tests for [IpfsClient].
 *
 * OkHttpClient is mocked at the [Call] level via MockK so we never touch the network.
 * CidVerifier is used as a real instance; the tests build correct CIDs from known content
 * so verification succeeds or fails deterministically.
 */
class IpfsClientTest {

    private lateinit var mockHttpClient: OkHttpClient
    private lateinit var mockCall: Call

    // Real verifier — we test end-to-end hash verification
    private val cidVerifier = CidVerifier()

    private val content = "DigiAsset metadata".toByteArray(Charsets.UTF_8)
    private val validCid = buildCidV0(content)

    private val gateways = listOf(
        "https://gateway-a.example",
        "https://gateway-b.example",
        "https://gateway-c.example"
    )

    @Before
    fun setUp() {
        // IpfsClient logs via android.util.Log on every gateway attempt; the
        // JVM unit-test classpath has a stub that throws, so without mocking
        // every @Test crashes before asserting anything useful.
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockHttpClient = mockk<OkHttpClient>()
        mockCall = mockk<Call>()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `fetchVerified returns bytes when hash matches`() = runTest {
        stubCall(200, content)
        every { mockHttpClient.newCall(any()) } returns mockCall

        val client = IpfsClient(mockHttpClient, cidVerifier, gateways)
        val result = client.fetchVerified(validCid)

        assertNotNull(result)
        assertArrayEquals(content, result)
    }

    // -------------------------------------------------------------------------
    // Hash mismatch — gateway served bad data
    // -------------------------------------------------------------------------

    @Test
    fun `fetchVerified returns null when hash mismatch on all gateways`() = runTest {
        val tamperedContent = "TAMPERED!".toByteArray(Charsets.UTF_8)
        // The CID is for `content` but gateways return `tamperedContent`
        stubCall(200, tamperedContent)
        every { mockHttpClient.newCall(any()) } returns mockCall

        val client = IpfsClient(mockHttpClient, cidVerifier, gateways)
        val result = client.fetchVerified(validCid)

        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // Gateway fallback
    // -------------------------------------------------------------------------

    @Test
    fun `fetchVerified tries next gateway on HTTP failure`() = runTest {
        val failCall: Call = mockk<Call>()
        val successCall: Call = mockk<Call>()

        val failResponse = buildResponse(500, ByteArray(0))
        val successResponse = buildResponse(200, content)

        every { failCall.execute() } returns failResponse
        every { successCall.execute() } returns successResponse

        // First two gateways get failCall, third gets successCall
        every { mockHttpClient.newCall(any()) } returnsMany listOf(failCall, failCall, successCall)

        val client = IpfsClient(mockHttpClient, cidVerifier, gateways)
        val result = client.fetchVerified(validCid)

        assertNotNull(result)
        assertArrayEquals(content, result)
        verify(exactly = 3) { mockHttpClient.newCall(any()) }
    }

    @Test
    fun `fetchVerified tries next gateway on network exception`() = runTest {
        val throwingCall: Call = mockk<Call>()
        val successCall: Call = mockk<Call>()

        every { throwingCall.execute() } throws IOException("Connection refused")
        every { successCall.execute() } returns buildResponse(200, content)

        every { mockHttpClient.newCall(any()) } returnsMany listOf(throwingCall, successCall)

        val twoGateways = listOf("https://bad.example", "https://good.example")
        val client = IpfsClient(mockHttpClient, cidVerifier, twoGateways)
        val result = client.fetchVerified(validCid)

        assertNotNull(result)
        assertArrayEquals(content, result)
    }

    // -------------------------------------------------------------------------
    // Size limit: checked while the answer streams, not after it is held
    // -------------------------------------------------------------------------

    @Test
    fun `fetchVerified refuses an answer whose declared length is over 5MB without reading it`() = runTest {
        val counting = CountingSource(IpfsClient.MAX_RESPONSE_SIZE + 1)
        stubStreamed(counting, declaredLength = IpfsClient.MAX_RESPONSE_SIZE + 1)

        val client = IpfsClient(mockHttpClient, cidVerifier, listOf("https://gateway.example"))

        assertNull(client.fetchVerified(validCid))
        assertEquals("bytes were taken from an answer declared over the limit", 0L, counting.taken)
    }

    @Test
    fun `fetchVerified stops reading an answer with no declared length one byte past 5MB`() = runTest {
        val counting = CountingSource(IpfsClient.MAX_RESPONSE_SIZE + 2 * 1024 * 1024)
        stubStreamed(counting)

        val client = IpfsClient(mockHttpClient, cidVerifier, listOf("https://gateway.example"))

        assertNull(client.fetchVerified(validCid))
        assertTrue(
            "took ${counting.taken} bytes of an answer over the ${IpfsClient.MAX_RESPONSE_SIZE}-byte limit",
            counting.taken <= IpfsClient.MAX_RESPONSE_SIZE + 1 + SEGMENT,
        )
    }

    /** The limit is the only reason either answer below could be refused: both match their CID. */
    @Test
    fun `fetchVerified returns content of exactly 5MB and refuses one byte more, though it matches its CID`() = runTest {
        for (declared in listOf(false, true)) {
            val atLimit = ByteArray(IpfsClient.MAX_RESPONSE_SIZE.toInt()) { (it % 251).toByte() }
            stubStreamed(CountingSource(atLimit), declaredLength = if (declared) atLimit.size.toLong() else -1L)
            val client = IpfsClient(mockHttpClient, cidVerifier, listOf("https://gateway.example"))
            assertArrayEquals("declared=$declared", atLimit, client.fetchVerified(buildCidV0(atLimit)))

            val overLimit = atLimit + byteArrayOf(7)
            stubStreamed(CountingSource(overLimit), declaredLength = if (declared) overLimit.size.toLong() else -1L)
            assertNull("declared=$declared", client.fetchVerified(buildCidV0(overLimit)))
        }
    }

    // -------------------------------------------------------------------------
    // A CID the verifier cannot verify
    // -------------------------------------------------------------------------

    /**
     * A CID naming SHA-1 (0x11), or naming sha3-512 (0x14) over a SHA-512 digest, is unverifiable:
     * nothing any gateway serves is returned, even bytes that match -- the same answer as for a
     * code the verifier does not compute at all (0x16, sha3-256).
     */
    @Test
    fun `fetchVerified returns nothing for a CID it cannot verify, whatever the gateways serve`() = runTest {
        for ((code, algorithm) in listOf(0x11 to "SHA-1", 0x14 to "SHA-512", 0x16 to "SHA3-256")) {
            every { mockCall.execute() } answers { buildResponse(200, content) } // a fresh body per gateway
            every { mockHttpClient.newCall(any()) } returns mockCall

            val client = IpfsClient(mockHttpClient, cidVerifier, gateways)
            assertNull("code 0x${code.toString(16)} over a $algorithm digest",
                client.fetchVerified(buildCidV1(content, code, algorithm)))
        }
    }

    // -------------------------------------------------------------------------
    // All gateways fail
    // -------------------------------------------------------------------------

    @Test
    fun `fetchVerified returns null when all gateways fail`() = runTest {
        every { mockCall.execute() } throws IOException("Network unreachable")
        every { mockHttpClient.newCall(any()) } returns mockCall

        val client = IpfsClient(mockHttpClient, cidVerifier, gateways)
        val result = client.fetchVerified(validCid)

        assertNull(result)
        verify(exactly = gateways.size) { mockHttpClient.newCall(any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Stub mockCall.execute() to return an HTTP [code] response with [body]. */
    private fun stubCall(code: Int, body: ByteArray) {
        every { mockCall.execute() } returns buildResponse(code, body)
    }

    private fun buildResponse(code: Int, body: ByteArray): Response {
        val responseBody = body.toResponseBody("application/vnd.ipld.raw".toMediaType())
        return Response.Builder()
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .request(Request.Builder().url("https://gateway.example/ipfs/$validCid").build())
            .protocol(Protocol.HTTP_1_1)
            .body(responseBody)
            .build()
    }

    /** Stub mockCall.execute() to answer 200 with a body read from [source], declaring [declaredLength]. */
    private fun stubStreamed(source: Source, declaredLength: Long = -1L) {
        val body = object : ResponseBody() {
            private val buffered: BufferedSource by lazy { source.buffer() }
            override fun contentLength() = declaredLength
            override fun contentType() = "application/vnd.ipld.raw".toMediaType()
            override fun source(): BufferedSource = buffered
        }
        every { mockCall.execute() } returns Response.Builder()
            .code(200)
            .message("OK")
            .request(Request.Builder().url("https://gateway.example/ipfs/cid").build())
            .protocol(Protocol.HTTP_1_1)
            .body(body)
            .build()
        every { mockHttpClient.newCall(any()) } returns mockCall
    }

    /** [total] bytes ([bytes] when given, else zeros), handed out only as asked; counts what was taken. */
    private class CountingSource(private val bytes: ByteArray?, val total: Long) : Source {
        constructor(total: Long) : this(null, total)
        constructor(bytes: ByteArray) : this(bytes, bytes.size.toLong())

        var taken = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = minOf(byteCount, total - taken)
            if (n <= 0L) return -1L
            if (bytes != null) sink.write(bytes, taken.toInt(), n.toInt()) else sink.write(ByteArray(n.toInt()))
            taken += n
            return n
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {}
    }

    companion object {
        /** The most one okio segment can add past what the reader asked for. */
        private const val SEGMENT = 8_192L

        private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        // A CIDv1 (base58btc, raw codec) whose multihash names [code] over [algorithm]'s digest
        fun buildCidV1(content: ByteArray, code: Int, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm).digest(content)
            return "z" + encodeBase58(byteArrayOf(0x01, 0x55, code.toByte(), digest.size.toByte()) + digest)
        }

        // Build a real CIDv0 for deterministic offline tests
        fun buildCidV0(content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            val multihash = byteArrayOf(0x12.toByte(), 0x20.toByte()) + digest
            return encodeBase58(multihash)
        }

        private fun encodeBase58(input: ByteArray): String {
            var num = java.math.BigInteger(1, input)
            val base = java.math.BigInteger.valueOf(58)
            val sb = StringBuilder()
            while (num > java.math.BigInteger.ZERO) {
                val (q, r) = num.divideAndRemainder(base)
                sb.append(B58[r.toInt()])
                num = q
            }
            for (b in input) {
                if (b != 0.toByte()) break
                sb.append('1')
            }
            return sb.reverse().toString()
        }
    }
}
