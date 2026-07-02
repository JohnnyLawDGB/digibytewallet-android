package io.digibyte.core.ipfs

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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    // Size limit
    // -------------------------------------------------------------------------

    @Test
    fun `fetchVerified rejects responses over 5MB`() = runTest {
        // Build a body that reports a content-length beyond the limit
        val hugeBody: ResponseBody = mockk<ResponseBody>()
        every { hugeBody.contentLength() } returns IpfsClient.MAX_RESPONSE_SIZE + 1
        every { hugeBody.bytes() } returns ByteArray(0) // should never be called

        val response = Response.Builder()
            .code(200)
            .message("OK")
            .request(Request.Builder().url("https://gateway.example/ipfs/cid").build())
            .protocol(Protocol.HTTP_1_1)
            .body(hugeBody)
            .build()

        every { mockCall.execute() } returns response
        every { mockHttpClient.newCall(any()) } returns mockCall

        val client = IpfsClient(mockHttpClient, cidVerifier, listOf("https://gateway.example"))
        val result = client.fetchVerified(validCid)

        assertNull(result)
        // bytes() should not be called when content-length already exceeds limit
        verify(exactly = 0) { hugeBody.bytes() }
    }

    @Test
    fun `fetchVerified rejects response body bytes over 5MB even without content-length`() = runTest {
        // Content-length = -1 (unknown), but actual bytes exceed limit
        val oversizedBytes = ByteArray((IpfsClient.MAX_RESPONSE_SIZE + 1).toInt())
        val body = buildBodyWithUnknownLength(oversizedBytes)

        val response = Response.Builder()
            .code(200)
            .message("OK")
            .request(Request.Builder().url("https://gateway.example/ipfs/cid").build())
            .protocol(Protocol.HTTP_1_1)
            .body(body)
            .build()

        every { mockCall.execute() } returns response
        every { mockHttpClient.newCall(any()) } returns mockCall

        val client = IpfsClient(mockHttpClient, cidVerifier, listOf("https://gateway.example"))
        val result = client.fetchVerified(validCid)

        assertNull(result)
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

    /** Build a ResponseBody with contentLength() == -1 (chunked / unknown). */
    private fun buildBodyWithUnknownLength(bytes: ByteArray): ResponseBody {
        val body: ResponseBody = mockk<ResponseBody>()
        every { body.contentLength() } returns -1L
        every { body.bytes() } returns bytes
        return body
    }

    companion object {
        private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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
