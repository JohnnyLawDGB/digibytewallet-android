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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The image loader's side of IPFS-NO-NULL and IPFS-MISS-BACKOFF (server hand-off 2026-11-21).
 *
 * Coil keeps no record of a failed load: an icon whose picture could not be fetched is fetched
 * again each time it is drawn. So the loader is the path with no brake of its own, and the two rules
 * have to hold through [IpfsFetcher] with the client the app ships (the public constructor).
 * The gateways are an interceptor; nothing leaves the process.
 */
class IpfsFetcherMissingCidTest {

    private val requested = ArrayList<String>()
    private var status = 404

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        synchronized(requested) { requested += chain.request().url.toString() }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType())).build()
    }.build()

    private val gateways = listOf("https://first.example/api/ipfs/{cid}", "https://second.example/ipfs/{cid}?format=raw")
    private val client = IpfsClient(http, gateways = gateways)

    private fun uri(host: String?): Uri = mockk<Uri>().also {
        every { it.scheme } returns "ipfs"
        every { it.host } returns host
    }

    private fun fetch(host: String?) = runBlocking { IpfsFetcher(uri(host), mockk(relaxed = true), client).fetch() }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `an ipfs uri that names no cid makes no request`() {
        status = 200
        for (host in listOf(null, "", " ", "null", "NULL", "undefined", "QmAB", "logo.png")) assertNull("'$host'", fetch(host))
        assertEquals("requests made: $requested", 0, requested.size)
    }

    @Test fun `a picture no gateway has is asked for once, however often its icon is drawn`() {
        val cid = "QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        repeat(40) { assertNull(fetch(cid)) }                  // the list scrolled, the screen reopened
        assertEquals(gateways.map { it.replace("{cid}", cid) }, requested)
    }

    @Test fun `a picture the gateways failed to serve is asked for on each draw, as before`() {
        status = 503
        val cid = "QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        repeat(3) { assertNull(fetch(cid)) }
        assertEquals(6, requested.size)
    }
}
