package io.digibyte.di

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.tor.TorManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * While the user's Tor setting is on, the shared client never routes a request outside Tor — not
 * while Tor is still starting, and not before it has started at all. Such a request waits, then
 * fails; it does not go direct. With the setting off, requests go direct as they always have.
 *
 * Built through the Hilt provider with a real [TorManager] whose setting is read from mocked
 * preferences and which has not been started, i.e. the state of every launch between the user's
 * setting being read and Tor reporting connected. Requests go to a server socket on this host's
 * loopback, so whether a direct connection was made is observed, not inferred.
 */
class SharedClientTorRouteTest {

    private fun torManager(torOn: Boolean): TorManager {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean("tor_enabled", any()) } returns torOn
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationContext } returns context
        return TorManager(context)
    }

    private fun client(torOn: Boolean): OkHttpClient = NetworkModule.provideOkHttpClient(torManager(torOn))

    private val anyHttps = URI("https://api.digiscope.me/api/peers")

    /** A loopback server that counts the connections it accepts, for [seconds] at most. */
    private class Listener(seconds: Int) : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = seconds * 1000 }
        val accepted = AtomicInteger(0)
        private val thread = Thread {
            try {
                while (true) {
                    socket.accept().use {
                        accepted.incrementAndGet()
                        it.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; start() }
        val url get() = "http://127.0.0.1:${socket.localPort}/"
        override fun close() { socket.close(); thread.join(5_000) }
    }

    @Test fun `while Tor is on and not yet connected the shared client selects no direct route`() {
        val proxies = client(torOn = true).proxySelector.select(anyHttps)
        assertTrue("no route was selected", proxies.isNotEmpty())
        assertTrue(
            "a direct route was selected while Tor is on and not connected: $proxies",
            proxies.none { it == Proxy.NO_PROXY || it.type() == Proxy.Type.DIRECT },
        )
    }

    @Test fun `while Tor is on and not yet connected a request opens no direct connection`() {
        Listener(seconds = 4).use { server ->
            val call = client(torOn = true).newCall(Request.Builder().url(server.url).build())
            // The request may wait for Tor; cancelling it is what a caller's timeout does.
            val canceller = Thread { Thread.sleep(1_500); call.cancel() }.apply { start() }
            var failure: IOException? = null
            try {
                call.execute().close()
            } catch (e: IOException) {
                failure = e
            }
            canceller.join()
            assertEquals("a direct connection reached the server while Tor is on and not connected", 0, server.accepted.get())
            assertNotNull("the request did not fail", failure)
        }
    }

    /** GUARD: with the user's Tor setting off, traffic goes direct, as before. */
    @Test fun `with Tor off the shared client goes direct`() {
        val shared = client(torOn = false)
        assertEquals(listOf(Proxy.NO_PROXY), shared.proxySelector.select(anyHttps))
        Listener(seconds = 4).use { server ->
            shared.newCall(Request.Builder().url(server.url).build()).execute().use { response ->
                assertEquals(204, response.code)
            }
            assertEquals(1, server.accepted.get())
        }
    }
}
