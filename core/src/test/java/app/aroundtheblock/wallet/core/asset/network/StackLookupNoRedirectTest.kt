package app.aroundtheblock.wallet.core.asset.network

import android.util.Log
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The indexer stack lookup decides what an asset send may spend and what a received output is
 * credited with, so its answer must come from the endpoint that was asked: a redirect is not
 * followed, and reads as "no answer". The client the caller passes in, and so every other request
 * made with it, keeps following redirects.
 *
 * Served by a minimal HTTP/1.1 server on the loopback interface (plain HTTP, so the DigiScope
 * certificate pins, which name only DigiScope hosts, do not apply).
 */
class StackLookupNoRedirectTest {

    private val txid = "ab".repeat(32)
    private val found = """{"txid":"$txid","vout":1,"confirmations":3,"assets":[{"assetId":"LaRedirected","count":1000000}]}"""
    private val honest = """{"txid":"$txid","vout":1,"confirmations":3,"assets":[{"assetId":"LaHonest","count":5}]}"""

    private lateinit var server: ServerSocket
    private val requested = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Serves [routes] (path -> (status line, extra headers, body)) until closed. */
    private fun serve(routes: Map<String, Triple<String, String, String>>) {
        server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s: Socket = try { server.accept() } catch (e: Exception) { break }
                s.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    requested += path
                    val (status, headers, body) = routes[path] ?: Triple("404 Not Found", "", "{}")
                    val bytes = body.toByteArray()
                    sock.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\n$headers" +
                            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun tearDown() {
        runCatching { server.close() }
        unmockkStatic(Log::class)
    }

    @Test fun a_redirected_stack_lookup_is_no_answer() = runBlocking {
        serve(mapOf(
            "/digiassets/txout/$txid/1" to Triple("302 Found", "Location: /elsewhere\r\n", ""),
            "/elsewhere" to Triple("200 OK", "", found),
        ))
        val base = OkHttpClient()
        val client = DigiScopeAssetClient(baseClient = base, baseUrl = this@StackLookupNoRedirectTest.base)

        assertEquals(StackLookup.Unavailable, client.stackOf(txid, 1))
        assertEquals("the redirect target was never fetched", listOf("/digiassets/txout/$txid/1"), requested.toList())

        // The caller's client is not changed: it still follows the same redirect.
        assertTrue(base.followRedirects && base.followSslRedirects)
        base.newCall(Request.Builder().url("${this@StackLookupNoRedirectTest.base}/digiassets/txout/$txid/1").build())
            .execute().use { assertEquals(200, it.code) }
        assertEquals("/elsewhere", requested.last())
    }

    @Test fun an_answer_from_the_endpoint_asked_is_read() = runBlocking {
        serve(mapOf("/digiassets/txout/$txid/1" to Triple("200 OK", "", honest)))
        val client = DigiScopeAssetClient(baseClient = OkHttpClient(), baseUrl = base)
        assertEquals(StackLookup.Found(listOf(StackEntry("LaHonest", 5))), client.stackOf(txid, 1))
    }
}
