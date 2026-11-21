package app.aroundtheblock.wallet.core.hub

import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.tor.TorRoute
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * HUB-WS-NO-REDIRECT: the Hub socket never follows a redirect on its upgrade request. A 3xx is a
 * failed connect, counted as any refused connection is (so the ordinary retry and its wait apply),
 * and no connection is made to the Location, on the same origin or on another.
 *
 * Served by minimal HTTP/1.1 servers on the loopback interface, as IpfsNoRedirectTest is (plain
 * `ws://`, so the DigiScope pins, which name only DigiScope hosts, do not apply). Every server
 * here can complete a WebSocket upgrade, so a socket that did go to a Location would open there and
 * be seen; the test never depends on how long something took.
 */
class HubSocketNoRedirectTest {

    private val hubPath = "/api/hub/ws"
    private val servers = ArrayList<Server>()
    private val digiScope = mockk<DigiScopeClient>().also { every { it.getToken() } returns "token" }

    /**
     * Answers [redirects] (path -> Location) with a 302 and upgrades any other path to a WebSocket,
     * then reads text frames into [frames]. [requested] is every path asked, in order.
     */
    private inner class Server(private val redirects: Map<String, String> = emptyMap()) {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val base get() = "127.0.0.1:${socket.localPort}"
        val requested: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val frames: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val firstFrame = CountDownLatch(1)

        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s: Socket = try { socket.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { runCatching { s.use { serve(it) } } }
                }
            }
        }

        private fun serve(sock: Socket) {
            val input = sock.getInputStream()
            val path = readLine(input)?.split(" ")?.getOrNull(1) ?: return
            var nonce = ""
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("sec-websocket-k")) nonce = line.substringAfter(":").trim()
            }
            requested += path
            val out = sock.getOutputStream()
            val location = redirects[path]
            if (location != null) {
                out.write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${acceptFor(nonce)}\r\n\r\n").toByteArray())
            out.flush()
            val data = DataInputStream(input)
            while (true) {
                val opcode = data.readUnsignedByte() and 0x0F
                val second = data.readUnsignedByte()
                var length = (second and 0x7F).toLong()
                if (length == 126L) length = data.readUnsignedShort().toLong() else if (length == 127L) length = data.readLong()
                val mask = ByteArray(4).also { if (second and 0x80 != 0) data.readFully(it) }
                val payload = ByteArray(length.toInt()).also { data.readFully(it) }
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                if (opcode == 0x8) return
                if (opcode == 0x1) { frames += String(payload, Charsets.UTF_8); firstFrame.countDown() }
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        /** The upgrade answer RFC 6455 section 4.2.2 asks for. */
        private fun acceptFor(nonce: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
    }

    private fun hubAt(server: Server) =
        HubWebSocket(OkHttpClient(), digiScope, route = { TorRoute.Direct }, url = "ws://${server.base}$hubPath")

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun tearDown() {
        servers.forEach { runCatching { it.socket.close() } }
        unmockkStatic(Log::class)
    }

    /** Waits for the connect [hub] started to end one way or the other, and answers how it ended. */
    private fun outcomeOf(hub: HubWebSocket): ConnectionState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        // connect() moves to RECONNECTING at once; an open moves it to CONNECTED, a failure to DISCONNECTED.
        while (hub.connectionState.value == ConnectionState.RECONNECTING) {
            assertTrue("the Hub socket neither opened nor failed", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
        return hub.connectionState.value
    }

    @Test fun `a redirect to another origin is a failed connect and nothing is opened at the Location`() {
        val elsewhere = Server()
        val pinned = Server(mapOf(hubPath to "http://${elsewhere.base}/elsewhere"))
        val hub = hubAt(pinned)

        assertTrue(hub.connect())

        assertEquals("the socket opened after a redirect", ConnectionState.DISCONNECTED, outcomeOf(hub))
        assertEquals(listOf(hubPath), pinned.requested.toList())
        assertEquals("a connection was made to the Location", emptyList<String>(), elsewhere.requested.toList())
        assertEquals("a frame was sent to the Location", emptyList<String>(), elsewhere.frames.toList())
        // Counted as a refused connection: the ordinary retry applies, with its wait.
        assertEquals(HubWebSocket.reconnectDelayAfter(1), hub.reconnectDelayMs())
        hub.disconnect()
    }

    @Test fun `a redirect to the same origin is a failed connect and nothing is opened at the Location`() {
        val pinned = Server(mapOf(hubPath to "/moved"))
        val hub = hubAt(pinned)

        assertTrue(hub.connect())

        assertEquals("the socket opened after a redirect", ConnectionState.DISCONNECTED, outcomeOf(hub))
        assertEquals("the Location was asked", listOf(hubPath), pinned.requested.toList())
        assertEquals("a frame was sent after a redirect", emptyList<String>(), pinned.frames.toList())
        assertEquals(HubWebSocket.reconnectDelayAfter(1), hub.reconnectDelayMs())
        hub.disconnect()
    }

    @Test fun `an upgrade the host answers itself still opens and joins the chat room`() {
        val pinned = Server()
        val hub = hubAt(pinned)

        assertTrue(hub.connect())

        assertEquals(ConnectionState.CONNECTED, outcomeOf(hub))
        assertTrue("no frame reached the server", pinned.firstFrame.await(20, TimeUnit.SECONDS))
        val join = JSONObject(pinned.frames.first())
        assertEquals("join", join.getString("type"))
        assertEquals(HubWebSocket.CHAT_CHANNEL_ID, join.getInt("channelId"))
        assertEquals(listOf(hubPath), pinned.requested.toList())
        hub.disconnect()
    }
}
