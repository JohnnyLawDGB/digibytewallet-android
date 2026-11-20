package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * IPFS-NO-REDIRECT: the IPFS client never follows a redirect from a trusted (pinned) gateway, so
 * trust-by-pin covers only bytes the pinned host itself answered. A 3xx from that gateway is a
 * failed fetch: the next gateway is asked, as after any failure, and the Location is never fetched.
 * The public gateways are content-verified on every answer and keep following redirects.
 *
 * Served by minimal HTTP/1.1 servers on the loopback interface (plain HTTP, so the DigiScope
 * certificate pins, which name only DigiScope hosts, do not apply). One server is handed to the
 * client as its trusted gateway; the others are other hosts.
 */
class IpfsNoRedirectTest {

    private val content = "DigiAsset metadata".toByteArray(Charsets.UTF_8)
    private val other = "not the content the CID names".toByteArray(Charsets.UTF_8)

    /** A dag-pb CID (`Qm…`) whose digest is the hash of [content]: a content-verified gateway accepts [content] only. */
    private val cid = IpfsClientTest.buildCidV0(content)

    private val servers = ArrayList<Server>()

    /** Serves [routes] (path -> (status line, extra headers, body)) until closed; [requested] is every path asked. */
    private inner class Server(routes: (Server) -> Map<String, Triple<String, String, ByteArray>>) {
        val socket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val base get() = "http://127.0.0.1:${socket.localPort}"
        val requested: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        private val routes = routes(this)

        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s: Socket = try { socket.accept() } catch (e: Exception) { break }
                    s.use { sock ->
                        val reader = sock.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                        requested += path
                        val (status, headers, body) = this.routes[path] ?: Triple("404 Not Found", "", ByteArray(0))
                        sock.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\n$headers" +
                                "Content-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }
    }

    private fun serving(path: String, body: ByteArray) = Server { mapOf(path to Triple("200 OK", "", body)) }
    private fun redirecting(path: String, to: String) =
        Server { mapOf(path to Triple("302 Found", "Location: $to\r\n", ByteArray(0))) }

    private fun client(gateways: List<Server>, trusted: Server?): IpfsClient = IpfsClient(
        OkHttpClient(), CidVerifier(), gateways.map { "${it.base}/gw/{cid}" },
        trustedGatewayPrefixes = listOfNotNull(trusted?.let { "${it.base}/" }),
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After fun tearDown() {
        servers.forEach { runCatching { it.socket.close() } }
        unmockkStatic(Log::class)
    }

    @Test fun `a redirect from the trusted gateway is a failed fetch and its target is never asked`() = runBlocking {
        val elsewhere = serving("/elsewhere", other)
        val trusted = redirecting("/gw/$cid", "${elsewhere.base}/elsewhere")

        assertNull(client(listOf(trusted), trusted).fetchVerified(cid))
        assertEquals(listOf("/gw/$cid"), trusted.requested.toList())
        assertEquals("the redirect target was fetched", emptyList<String>(), elsewhere.requested.toList())
    }

    @Test fun `a redirect on the trusted gateway to its own host is not followed either`() = runBlocking {
        lateinit var self: Server
        self = Server { mapOf(
            "/gw/$cid" to Triple("301 Moved Permanently", "Location: /moved\r\n", ByteArray(0)),
            "/moved" to Triple("200 OK", "", other),
        ) }

        assertNull(client(listOf(self), self).fetchVerified(cid))
        assertEquals(listOf("/gw/$cid"), self.requested.toList())
    }

    @Test fun `after the trusted gateway redirects the next gateway is asked and its answer is content-verified`() = runBlocking {
        val elsewhere = serving("/elsewhere", other)
        val trusted = redirecting("/gw/$cid", "${elsewhere.base}/elsewhere")
        val next = serving("/gw/$cid", content)

        assertArrayEquals(content, client(listOf(trusted, next), trusted).fetchVerified(cid))
        assertEquals(emptyList<String>(), elsewhere.requested.toList())
        assertEquals(listOf("/gw/$cid"), next.requested.toList())
    }

    @Test fun `an answer from the trusted gateway itself is still read`() = runBlocking {
        val trusted = serving("/gw/$cid", other)
        // Accepted on the gateway's identity (dag-pb): the bytes are not the CID's digest and are returned.
        assertArrayEquals(other, client(listOf(trusted), trusted).fetchVerified(cid))
    }

    @Test fun `a public gateway may still redirect and what it lands on is content-verified`() = runBlocking {
        val good = serving("/landed", content)
        val bad = serving("/landed", other)
        val toGood = redirecting("/gw/$cid", "${good.base}/landed")
        val toBad = redirecting("/gw/$cid", "${bad.base}/landed")

        assertArrayEquals(content, client(listOf(toGood), trusted = null).fetchVerified(cid))
        assertNull(client(listOf(toBad), trusted = null).fetchVerified(cid))
        assertTrue(good.requested.isNotEmpty() && bad.requested.isNotEmpty())
    }
}
