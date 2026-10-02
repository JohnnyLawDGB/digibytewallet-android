package io.digibyte.di

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorNotReadyException
import io.digibyte.core.tor.TorRoute
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The shared client's routing for each [TorRoute], driven directly. Companion to
 * [SharedClientTorRouteTest], which drives the Hilt provider with a real, unstarted TorManager.
 * Every connection goes to a socket on this host's loopback.
 */
class SharedClientRouteBuilderTest {

    private val uri = URI("https://api.digiscope.me/api/peers")

    /** Counts connections; answers HTTP 204 unless [socks], when it reads the SOCKS greeting and closes. */
    private class Listener(private val socks: Boolean = false) : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = 4_000 }
        val accepted = AtomicInteger(0)
        val firstByte = AtomicInteger(-1)
        private val thread = Thread {
            try {
                while (true) {
                    socket.accept().use { s ->
                        accepted.incrementAndGet()
                        if (socks) {
                            firstByte.compareAndSet(-1, s.getInputStream().read())
                        } else {
                            s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; start() }
        val port get() = socket.localPort
        override fun close() { socket.close(); thread.join(5_000) }
    }

    @Test fun `direct goes direct and resolves names on the device`() {
        val client = NetworkModule.buildSharedClient({ TorRoute.Direct })
        assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(uri))
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), client.dns.lookup("127.0.0.1"))
    }

    @Test fun `connected Tor is used through its SOCKS port and no name is resolved locally`() {
        val client = NetworkModule.buildSharedClient({ TorRoute.Socks(9150) })
        val proxy = client.proxySelector.select(uri).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        assertEquals(9150, (proxy.address() as java.net.InetSocketAddress).port)
        assertEquals(listOf(InetAddress.getLoopbackAddress()), client.dns.lookup("api.digiscope.me"))
    }

    @Test fun `blocked selects a route that leaves nothing and resolves nothing`() {
        val client = NetworkModule.buildSharedClient({ TorRoute.Blocked })
        val proxy = client.proxySelector.select(uri).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as java.net.InetSocketAddress
        assertTrue("the blocked route is not on loopback", address.address.isLoopbackAddress)
        assertEquals("the blocked route is not port 0", 0, address.port)
        try {
            client.dns.lookup("api.digiscope.me")
            fail("a name was resolved while blocked")
        } catch (_: UnknownHostException) {
        }
    }

    @Test fun `a blocked request waits, then fails as waiting for Tor, and opens nothing`() {
        Listener().use { server ->
            val client = NetworkModule.buildSharedClient({ TorRoute.Blocked }, torWaitMs = 600L)
            val started = System.nanoTime()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/").build()).execute().close()
                fail("the request was sent while blocked")
            } catch (e: TorNotReadyException) {
            }
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("did not wait for Tor ($waitedMs ms)", waitedMs >= 500)
            assertEquals(0, server.accepted.get())
        }
    }

    @Test fun `a waiting request goes through Tor once Tor connects, not direct`() {
        Listener().use { direct ->
            Listener(socks = true).use { tor ->
                val route = AtomicReference<TorRoute>(TorRoute.Blocked)
                val client = NetworkModule.buildSharedClient({ route.get() }, torWaitMs = 10_000L)
                Thread { Thread.sleep(700); route.set(TorRoute.Socks(tor.port)) }.start()
                try {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${direct.port}/").build()).execute().close()
                } catch (_: IOException) {
                    // The stand-in proxy closes after the greeting; the request then fails. Expected.
                }
                assertEquals("the request reached the server directly", 0, direct.accepted.get())
                assertTrue("the request did not reach the Tor proxy", tor.accepted.get() >= 1)
                assertEquals("the proxy did not receive a SOCKS5 greeting", 5, tor.firstByte.get())
            }
        }
    }

    @Test fun `the announced fallback is what lets a Tor-on client go direct`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean("tor_enabled", any()) } returns true
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val tor = TorManager(context)

        assertEquals(TorRoute.Blocked, NetworkModule.torRoute(tor))
        assertTrue(NetworkModule.provideOkHttpClient(tor).proxySelector.select(uri).none { it == Proxy.NO_PROXY })
        mockkStatic(android.util.Log::class)
        try {
            every { android.util.Log.w(any<String>(), any<String>()) } returns 0
            tor.announceClearnetFallback()
        } finally {
            unmockkStatic(android.util.Log::class)
        }
        assertEquals(TorRoute.Direct, NetworkModule.torRoute(tor))
        assertEquals(listOf(Proxy.NO_PROXY), NetworkModule.provideOkHttpClient(tor).proxySelector.select(uri))
    }
}
