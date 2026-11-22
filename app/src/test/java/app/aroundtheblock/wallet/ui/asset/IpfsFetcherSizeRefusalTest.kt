package app.aroundtheblock.wallet.ui.asset

import android.net.Uri
import android.util.Log
import app.aroundtheblock.wallet.core.ipfs.IpfsClient
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * ASSET-MEDIA-FIXES, second review (R2 and R3), through the image loader with the client the app
 * ships: `IpfsClient(http)`, so the gateways are the SHIPPED FOUR and the trusted one is the shipped
 * one. Nothing leaves the process: the four hosts are answered by an interceptor.
 *
 * R2. Assets 5361 to 5363 on 4.1.5: the trusted gateway declared a 5.7 to 7.3 MB MP3, the wallet
 * refused it, went on to the three public gateways (which could not be reached on that network),
 * and logged `possible gateway tampering or unreachable` at ERROR on every draw. With one gateway
 * in the list the size was already reported as the reason; with the shipped four it was not,
 * because the public gateways' failures were counted beside it.
 *
 * R3. The trusted gateway's answer for a dag-pb CID is believed on its pinned identity, so nothing
 * checks its bytes. An EMPTY 200 was therefore "the file", it did not decode, and the picture was
 * hidden for 24 hours.
 */
class IpfsFetcherSizeRefusalTest {

    private val OCTETS = "application/octet-stream".toMediaType()
    private val TRUSTED = "api.digiscope.me"
    private val PUBLIC = listOf("trustless-gateway.link", "dweb.link", "ipfs.io")

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64)
    private val decodes: (ByteArray) -> Boolean = { it.size >= 4 && it[1] == 'P'.code.toByte() && it[2] == 'N'.code.toByte() }

    /** The block a public gateway holds for the file's CID, and the dag-pb CID (CIDv0) it hashes to. */
    private val rootBlock = byteArrayOf(0x12, 0x2a) + "links to the blocks of a 7 MB song".toByteArray()
    private val cid = cidV0(rootBlock)

    /** host -> what it answers; a host with no entry cannot be reached. */
    private val answers = HashMap<String, () -> Response.Builder>()
    private val requested = ArrayList<String>()
    private val info = ArrayList<String>()
    private val errors = ArrayList<String>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val host = chain.request().url.host
        synchronized(requested) { requested += host }
        val answer = answers[host] ?: throw java.net.ConnectException("Failed to connect to $host")
        answer().request(chain.request()).protocol(Protocol.HTTP_1_1).message("x").build()
    }.build()

    /** The shipped constructor and the shipped gateway list. */
    private val client = IpfsClient(http)

    private fun status(code: Int, body: ByteArray = ByteArray(0)): () -> Response.Builder =
        { Response.Builder().code(code).body(body.toResponseBody(OCTETS)) }

    /** 200 with a declared length of [declared] bytes; the body is never read. */
    private fun declares(declared: Long): () -> Response.Builder = {
        Response.Builder().code(200).body(object : ResponseBody() {
            override fun contentType() = OCTETS
            override fun contentLength() = declared
            override fun source() = Buffer()
        })
    }

    private fun fetch() = runBlocking {
        val uri = mockk<Uri>().also { every { it.scheme } returns "ipfs"; every { it.host } returns cid }
        IpfsFetcher(uri, mockk(relaxed = true), client, decodes).fetch()
    }

    private fun askedOf(host: String) = synchronized(requested) { requested.count { it == host } }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } answers { synchronized(info) { info += secondArg<String>() }; 0 }
        every { Log.w(any(), any<String>()) } answers { synchronized(info) { info += secondArg<String>() }; 0 }
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } answers { synchronized(errors) { errors += secondArg<String>() }; 0 }
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `the client under test is the shipped one`() {
        assertEquals(listOf(TRUSTED) + PUBLIC, IpfsClient.DEFAULT_GATEWAYS.map { it.substringAfter("://").substringBefore('/') })
        assertEquals(listOf("https://$TRUSTED/"), IpfsClient.TRUSTED_GATEWAY_PREFIXES)
        assertTrue(cid, cid.startsWith("Qm") && IpfsClient.normalizeCid(cid) == cid)
    }

    // ---------------------------------------------------------------------------------------------
    // R2
    // ---------------------------------------------------------------------------------------------

    /** Whatever the three public gateways do, the reason is the size, and it is not said at ERROR. */
    private fun sizeIsTheReason(publicDoes: (() -> Response.Builder)?, what: String) {
        answers[TRUSTED] = declares(7_296_078L)
        if (publicDoes != null) for (host in PUBLIC) answers[host] = publicDoes

        repeat(40) { assertNull("$what: nothing to show", fetch()) }           // forty draws

        assertEquals("$what: no line that reads as a security signal", emptyList<String>(), errors)
        val fetcherLines = synchronized(info) { info.filter { it.startsWith("Not shown") || it.startsWith("Not fetched") } }
        assertEquals("$what: the size is the reason, said once: $fetcherLines", 1, fetcherLines.count { it.contains("over the size limit") })
        assertTrue("$what: $fetcherLines", fetcherLines.none { it.contains("only a block") || it.contains("tampering") })
        assertEquals("$what: the trusted gateway is asked once and then left alone", 1, askedOf(TRUSTED))
        for (host in PUBLIC) assertEquals("$what: $host is not asked for a file only the trusted gateway can serve", 0, askedOf(host))
        assertFalse("$what: a size refusal is not a verdict on what the file is", client.isKnownNotAPicture(cid))
        assertEquals("$what: the size the trusted gateway declared is known", 7_296_078L, client.knownSize(cid))
    }

    /** The condition the 4.1.5 evidence was captured under. */
    @Test fun `refused for size by the trusted gateway, public gateways unreachable`() = sizeIsTheReason(null, "unreachable")

    @Test fun `refused for size by the trusted gateway, public gateways answering 504`() = sizeIsTheReason(status(504), "504")

    @Test fun `refused for size by the trusted gateway, public gateways answering 404`() = sizeIsTheReason(status(404), "404")

    @Test fun `refused for size by the trusted gateway, public gateways serving the root block`() =
        sizeIsTheReason(status(200, rootBlock), "root block")

    /** The refusal lives in the client's memory only (and ends there after its wait, measured in core). */
    @Test fun `a size refusal is not for ever, a new process asks the trusted gateway again`() {
        answers[TRUSTED] = declares(7_296_078L)
        for (host in PUBLIC) answers[host] = status(200, rootBlock)
        assertNull(fetch())

        answers[TRUSTED] = status(200, PNG)             // re-pinned as a small picture, say
        assertNull("inside the wait nobody is asked", fetch())
        assertEquals(1, askedOf(TRUSTED))

        val uri = mockk<Uri>().also { every { it.scheme } returns "ipfs"; every { it.host } returns cid }
        assertNotNull(runBlocking { IpfsFetcher(uri, mockk(relaxed = true), IpfsClient(http), decodes).fetch() })
        assertEquals(2, askedOf(TRUSTED))
    }

    // ---------------------------------------------------------------------------------------------
    // R3
    // ---------------------------------------------------------------------------------------------

    @Test fun `an empty 200 from the trusted gateway is a failed fetch, never a verdict`() {
        answers[TRUSTED] = status(200, ByteArray(0))
        for (host in PUBLIC) answers[host] = status(200, rootBlock)

        repeat(5) { assertNull(fetch()) }
        assertFalse("an empty answer says nothing about the file", client.isKnownNotAPicture(cid))
        assertNull("and it is not the file's size", client.knownSize(cid))
        assertTrue("$info", info.none { it.contains("is not a picture") })
        assertEquals("not remembered: asked again on every draw, like any failed fetch", 5, askedOf(TRUSTED))

        // The gateway recovers: the picture is shown at once.
        answers[TRUSTED] = status(200, PNG)
        assertNotNull(fetch())
    }

    @Test fun `an empty 200 from the trusted gateway with no public gateway reachable is reported as a failed fetch`() {
        answers[TRUSTED] = status(200, ByteArray(0))
        assertNull(fetch())
        assertFalse(client.isKnownNotAPicture(cid))
        assertEquals("a gateway that was asked and gave nothing usable: the ERROR line is for this", 1, errors.size)
    }

    private fun cidV0(block: ByteArray): String {
        val input = byteArrayOf(0x12, 0x20) + MessageDigest.getInstance("SHA-256").digest(block)
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(BigInteger.valueOf(58))
            sb.append(alphabet[r.toInt()]); num = q
        }
        return sb.reverse().toString()
    }
}
