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
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * ASSET-MEDIA-FIXES, review F1 (blocking): a trusted-gateway outage must not be remembered as
 * "not a picture".
 *
 * With the client the app ships: `api.digiscope.me` first, trusted on its pin, then a public
 * gateway. Every real picture's CID is dag-pb, and for a dag-pb CID a public gateway's
 * `?format=raw` answer is the CID's ROOT BLOCK: it hashes to the CID, so it is returned, and it
 * does not decode as an image. When the trusted gateway answered 5xx, timed out, or 404'd a CID it
 * had not pinned yet, that block was judged, and the picture was hidden for 24 hours.
 *
 * "Not a picture" may be remembered only for the whole file, from a source that can speak for it.
 */
class IpfsFetcherRootBlockTest {

    private val OCTETS = "application/octet-stream".toMediaType()
    private val TRUSTED = "api.digiscope.me"
    private val PUBLIC = "dweb.link"

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64)
    private val MP3 = "ID3".toByteArray() + ByteArray(4_000)
    private val decodes: (ByteArray) -> Boolean = { it.size >= 4 && it[1] == 'P'.code.toByte() && it[2] == 'N'.code.toByte() }

    /** The block a public gateway holds for the picture's CID, and the CIDv0 it hashes to. */
    private val rootBlock = byteArrayOf(0x12, 0x2a) + "links to the blocks of the picture".toByteArray()
    private val cid = cidV0(rootBlock)

    /** host -> (status, body); a host with no entry does not answer (a timeout). */
    private val answers = HashMap<String, Pair<Int, ByteArray>>()
    private val requested = ArrayList<String>()
    private val lines = ArrayList<String>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val host = chain.request().url.host
        synchronized(requested) { requested += host }
        val (code, body) = answers[host] ?: throw java.net.SocketTimeoutException("timeout")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody(OCTETS)).build()
    }.build()

    /** The shipped constructor: the trusted gateway is the shipped one and nothing else. */
    private val client = IpfsClient(http, gateways = listOf("https://$TRUSTED/api/ipfs/{cid}", "https://$PUBLIC/ipfs/{cid}?format=raw"))

    private fun fetch() = runBlocking {
        val uri = mockk<Uri>().also { every { it.scheme } returns "ipfs"; every { it.host } returns cid }
        IpfsFetcher(uri, mockk(relaxed = true), client, decodes).fetch()
    }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } answers { synchronized(lines) { lines += secondArg<String>() }; 0 }
        every { Log.w(any(), any<String>()) } answers { synchronized(lines) { lines += secondArg<String>() }; 0 }
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun outageThenRecovery(trustedSays: Int?) {
        if (trustedSays != null) answers[TRUSTED] = trustedSays to ByteArray(0)
        answers[PUBLIC] = 200 to rootBlock

        assertNull("nothing to show during the outage", fetch())
        assertEquals(listOf(TRUSTED, PUBLIC), requested)
        assertFalse("a root block fetched during an outage is not a verdict on the picture", client.isKnownNotAPicture(cid))
        assertTrue("$lines", lines.none { it.contains("is not a picture") })

        // The trusted gateway recovers (or pins the file). A 404 is remembered for ten minutes by
        // the gateway memory, which is that memory's business; a failure is not remembered at all.
        answers[TRUSTED] = 200 to PNG
        requested.clear()
        if (trustedSays == 404) {
            assertNull(fetch())
            assertFalse("still no verdict while the trusted gateway is inside its ten minutes", client.isKnownNotAPicture(cid))
        } else {
            assertNotNull("the picture is shown as soon as the trusted gateway serves it", fetch())
            assertEquals(TRUSTED, requested.first())
        }
    }

    @Test fun `a trusted-gateway 503 while a public gateway has the root block is not remembered as not a picture`() = outageThenRecovery(503)

    @Test fun `a trusted-gateway timeout while a public gateway has the root block is not remembered as not a picture`() = outageThenRecovery(null)

    @Test fun `a CID the trusted gateway has not pinned yet is not remembered as not a picture`() = outageThenRecovery(404)

    /** Draw after draw during an outage: asked again each time, as on develop, and never filed. */
    @Test fun `an outage is not remembered however often the icon is drawn`() {
        answers[TRUSTED] = 503 to ByteArray(0)
        answers[PUBLIC] = 200 to rootBlock
        repeat(10) { assertNull(fetch()) }
        assertFalse(client.isKnownNotAPicture(cid))
        assertEquals(10, requested.count { it == TRUSTED })
    }

    /** What the memory is for is kept: the trusted gateway's own answer is the file, and is judged. */
    @Test fun `the file itself, from the trusted gateway, is still remembered when it is not a picture`() {
        answers[TRUSTED] = 200 to MP3
        answers[PUBLIC] = 200 to rootBlock
        repeat(20) { assertNull(fetch()) }
        assertTrue(client.isKnownNotAPicture(cid))
        assertEquals("downloaded once", listOf(TRUSTED), requested)
        assertEquals(1, lines.count { it.contains("is not a picture") })
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
