package app.aroundtheblock.wallet.di

import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.ConnectionState
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.tor.TorRoute
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * GUARD (no red can exist on this base: the Hub socket has been made on the shared client since
 * 4.0.86): with the user's Tor setting on, the Hub socket — which carries the Hub session token —
 * is never opened outside Tor. Built through AppModule's provider on the shared client, as the app
 * builds it:
 *  - Tor on and not connected: the handshake request is held by the shared client's Tor wait and
 *    then refused; it never gets past it;
 *  - Tor connected: the handshake goes to Tor's SOCKS port, not to the host.
 */
class HubSocketTorRouteTest {

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val digiScope = mockk<DigiScopeClient>().also { every { it.getToken() } returns "token" }

    /** Counts the requests that reach it. */
    private class Seen : Interceptor {
        val hits = AtomicInteger(0)
        override fun intercept(chain: Interceptor.Chain): Response {
            hits.incrementAndGet()
            return chain.proceed(chain.request())
        }
    }

    @Test fun `with Tor on and not connected, the Hub socket opens nothing`() {
        val before = Seen()
        val after = Seen()
        val shared = NetworkModule.buildSharedClient({ TorRoute.Blocked }, torWaitMs = 300L)
        // `before` runs ahead of the shared client's Tor wait, `after` behind it.
        val client = shared.newBuilder().apply {
            interceptors().add(0, before)
            interceptors().add(after)
        }.build()

        val hub = AppModule.provideHubWebSocket(client, digiScope)
        hub.connect()
        awaitDisconnected(hub, timeoutMs = 10_000)

        assertEquals("the Hub socket was not made on the client it was given", 1, before.hits.get())
        assertEquals("the Hub socket's handshake got past the Tor wait", 0, after.hits.get())
    }

    @Test fun `with Tor connected, the Hub socket goes through Tor`() {
        StandInTor().use { tor ->
            val client = NetworkModule.buildSharedClient({ TorRoute.Socks(tor.port) })

            val hub = AppModule.provideHubWebSocket(client, digiScope)
            hub.connect()
            // The stand-in closes after the SOCKS greeting, so the handshake then fails.
            awaitDisconnected(hub, timeoutMs = 10_000)

            assertTrue("the Hub socket did not reach Tor's SOCKS port", tor.accepted.get() >= 1)
            assertEquals("Tor's port did not receive a SOCKS5 greeting", 5, tor.firstByte.get())
        }
    }

    private fun awaitDisconnected(hub: HubWebSocket, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        // connect() moves to RECONNECTING at once; the failure moves it back to DISCONNECTED.
        while (hub.connectionState.value != ConnectionState.DISCONNECTED) {
            assertTrue("the Hub socket neither failed nor stopped in $timeoutMs ms", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }

    /** A SOCKS listener on loopback that reads the greeting's first byte and closes. */
    private class StandInTor : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = 8_000 }
        val accepted = AtomicInteger(0)
        val firstByte = AtomicInteger(-1)
        private val thread = Thread {
            try {
                while (true) {
                    socket.accept().use { s ->
                        accepted.incrementAndGet()
                        firstByte.compareAndSet(-1, s.getInputStream().read())
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; start() }
        val port get() = socket.localPort
        override fun close() { socket.close(); thread.join(5_000) }
    }
}
