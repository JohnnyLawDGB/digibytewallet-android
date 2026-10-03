package io.digibyte.core.reconcile

import android.content.Context
import io.mockk.mockk
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The reconcile client is always built on a client the caller hands it — in the app, the shared
 * one, which routes through Tor when the user's Tor setting is on — and keeps what that client
 * carries. It has no way to make a client of its own: a default here is what let three call sites
 * build one with no Tor routing at all.
 */
class DgbNodeClientConstructionTest {

    @Test fun `the reconcile client cannot be built without a client`() {
        val constructors = DgbNodeClient::class.java.declaredConstructors
        assertTrue("no constructor", constructors.isNotEmpty())
        for (constructor in constructors) {
            val types = constructor.parameterTypes.toList()
            assertTrue(
                "a constructor builds its own client: $types",
                OkHttpClient::class.java in types &&
                    types.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" },
            )
        }
    }

    /** GUARD: both clients it derives keep the base client's routing, name lookup and interceptors. */
    @Test fun `the clients it derives keep the base client's routing`() {
        val proxies = object : ProxySelector() {
            override fun select(uri: URI?) = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
        }
        val dns = object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname)
        }
        val interceptor = Interceptor { it.proceed(it.request()) }
        val base = OkHttpClient.Builder().proxySelector(proxies).dns(dns).addInterceptor(interceptor).build()

        val node = DgbNodeClient(mockk<Context>(relaxed = true), base)
        for (field in listOf("pinnedClient", "unpinnedClient")) {
            val derived = DgbNodeClient::class.java.getDeclaredField(field)
                .apply { isAccessible = true }.get(node) as OkHttpClient
            assertSame("$field lost the routing", proxies, derived.proxySelector)
            assertSame("$field lost the name lookup", dns, derived.dns)
            assertEquals("$field lost the interceptors", base.interceptors, derived.interceptors)
        }
    }

    /** A scan batch can take up to a minute on the server (90 s read timeout). The shared client's
     *  45 s whole-call limit must not cut it short: both derived clients allow at least the read
     *  timeout for the whole call. */
    @Test fun `a slow scan batch is not cut off by the shared call limit`() {
        val base = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
        val node = DgbNodeClient(mockk<Context>(relaxed = true), base)
        for (field in listOf("pinnedClient", "unpinnedClient")) {
            val derived = DgbNodeClient::class.java.getDeclaredField(field)
                .apply { isAccessible = true }.get(node) as OkHttpClient
            assertTrue(
                "$field callTimeout ${derived.callTimeoutMillis} ms cuts off a ${derived.readTimeoutMillis} ms read",
                derived.callTimeoutMillis == 0 || derived.callTimeoutMillis >= derived.readTimeoutMillis,
            )
        }
    }
}
