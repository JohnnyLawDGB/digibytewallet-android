package app.aroundtheblock.wallet.ui.asset

import android.net.Uri
import android.util.Log
import app.aroundtheblock.wallet.core.ipfs.IpfsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ASSET-MEDIA-FIXES through the image loader, with the client the app ships (the public
 * constructor, so its first gateway is the trusted one, as on a phone).
 *
 * Coil keeps no record of a failed load and no disk cache is configured, so before this a file that
 * was fetched and did not decode was fetched again by every image instance, on every draw: asset
 * 5364's 4,265,952-byte MP3 was downloaded twice for two instances and four times for four. The
 * list card, the detail hero and the full-screen viewer all load through [IpfsFetcher], and its
 * memory is the one in [IpfsClient], so they share it.
 */
class IpfsFetcherNotAPictureTest {

    /** A dag-pb CID, as real media has; taken from the trusted gateway on its identity. */
    private val cid = "bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"
    private val OCTETS = "application/octet-stream".toMediaType()

    private val requested = ArrayList<String>()
    private var answer: () -> ResponseBody = { ByteArray(0).toResponseBody(OCTETS) }
    private var hold: CountDownLatch? = null
    private val errors = ArrayList<String>()
    private val lines = ArrayList<String>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        synchronized(requested) { requested += chain.request().url.toString() }
        hold?.await(10, TimeUnit.SECONDS)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x").body(answer()).build()
    }.build()

    private val client = IpfsClient(http, gateways = listOf("https://api.digiscope.me/api/ipfs/{cid}"))

    /** Stands for the platform decoder: these tests' "picture" starts with the PNG signature. */
    private val decodes: (ByteArray) -> Boolean = { it.size >= 4 && it[1] == 'P'.code.toByte() && it[2] == 'N'.code.toByte() }
    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64)
    private val MP3 = "ID3".toByteArray() + ByteArray(4_000)

    private fun uri(): Uri = mockk<Uri>().also {
        every { it.scheme } returns "ipfs"
        every { it.host } returns cid
    }

    private fun fetch() = runBlocking { IpfsFetcher(uri(), mockk(relaxed = true), client, decodes).fetch() }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } answers { synchronized(lines) { lines += secondArg<String>() }; 0 }
        every { Log.w(any(), any<String>()) } answers { synchronized(lines) { lines += secondArg<String>() }; 0 }
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } answers { synchronized(errors) { errors += secondArg<String>() }; 0 }
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `a file that is not a picture is downloaded once, however often its icon is drawn`() {
        answer = { MP3.toResponseBody(OCTETS) }
        repeat(40) { assertNull(fetch()) }          // list, detail, viewer, the list again
        assertEquals("downloads: $requested", 1, requested.size)
        // Routine, and not a gateway's fault: nothing at ERROR, and the reason is said once.
        assertEquals(emptyList<String>(), errors)
        assertEquals(1, lines.count { it.contains("not a picture") && it.contains(MP3.size.toString()) })
    }

    /** Measured on 4.1.5: 2 instances, 2 downloads; 4 instances, 4. */
    @Test fun `image instances drawn at the same moment download a file that is not a picture once`() {
        answer = { MP3.toResponseBody(OCTETS) }
        hold = CountDownLatch(1)
        val results = runBlocking {
            val all = (1..4).map { async(Dispatchers.IO) { IpfsFetcher(uri(), mockk(relaxed = true), client, decodes).fetch() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (synchronized(requested) { requested.size } == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            Thread.sleep(300)
            hold!!.countDown()
            all.awaitAll()
        }
        assertTrue(results.all { it == null })
        assertEquals("downloads: $requested", 1, requested.size)
    }

    @Test fun `a picture is still handed to the decoder, each time it is asked for`() {
        answer = { PNG.toResponseBody(OCTETS) }
        repeat(3) { assertNotNull(fetch()) }
        assertEquals(3, requested.size)             // the loader's memory cache, not this class, saves these
        assertEquals(emptyList<String>(), errors)
    }

    /** Assets 5361 to 5363 on 4.1.5: "possible gateway tampering" for a file that was merely too large. */
    @Test fun `a file refused only for its size is logged as that, not as tampering`() {
        answer = {
            object : ResponseBody() {
                override fun contentType() = OCTETS
                override fun contentLength() = 7_296_078L
                override fun source() = Buffer()
            }
        }
        repeat(5) { assertNull(fetch()) }
        assertEquals("asked once, then left alone", 1, requested.size)
        assertEquals("no line that reads as a security signal: $errors", emptyList<String>(), errors)
        assertEquals(1, lines.count { it.contains("over the size limit") })
        assertTrue(lines.none { it.contains("tampering") })
    }

    /** The signal itself is kept for what it is for: a gateway that answered and gave nothing usable. */
    @Test fun `an answer that fails verification is still reported at ERROR`() {
        // A raw-codec CID is content-verified even from the trusted gateway; these bytes are not its content.
        val rawCid = "bafkreicrmqxoxgm423z4nxbpcecc2kbixm4nnpyzfuz5kjkjmsbmgkpkae"
        answer = { PNG.toResponseBody(OCTETS) }
        val raw = mockk<Uri>().also { every { it.scheme } returns "ipfs"; every { it.host } returns rawCid }
        assertNull(runBlocking { IpfsFetcher(raw, mockk(relaxed = true), client, decodes).fetch() })
        verify(exactly = 1) { Log.e(any(), any<String>()) }
        assertTrue(errors.single().contains("tampering"))
    }
}
