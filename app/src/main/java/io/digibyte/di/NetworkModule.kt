package io.digibyte.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.digibyte.core.tor.TorManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(torManager: TorManager): OkHttpClient {
        // Dynamic ProxySelector: routes through Tor SOCKS5 when connected,
        // falls back to direct when Tor is off. Singleton OkHttpClient adapts
        // automatically — no rebuild needed.
        val torProxySelector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val port = torManager.getSocksPort()
                return if (port != null) {
                    listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                } else {
                    listOf(Proxy.NO_PROXY)
                }
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                // No-op: OkHttp retries or surfaces the error normally.
            }
        }

        // DNS leak prevention (defense-in-depth):
        // OkHttp 4.x creates unresolved InetSocketAddresses for SOCKS proxy
        // connections, meaning the hostname is sent to the SOCKS5 proxy for
        // remote DNS resolution — no local DNS query needed. This custom Dns
        // is a safety net in case that behavior changes in a future OkHttp
        // version. Combined with SafeSocks 1 in torrc, DNS leaks are blocked.
        val torDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (torManager.getSocksPort() != null) {
                    // Return loopback without any DNS query. OkHttp won't use this
                    // address for SOCKS connections (it sends the hostname directly
                    // to the proxy), but this prevents local DNS as a fallback.
                    listOf(InetAddress.getLoopbackAddress())
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        return OkHttpClient.Builder()
            .proxySelector(torProxySelector)
            .dns(torDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // WHOLE-CALL bound. connect/read/write are PER-PHASE and per-attempt: with
            // retries, redirects and a slow-but-not-dead peer a single call can exceed all
            // three and run effectively unbounded. That is load-bearing here because the
            // seeder fetch runs inside the 0-peer recovery path, and a hang there used to
            // wedge the recovery watchdog outright (Note 8, 2026-08-02: 47 minutes of
            // silence after "reviving recovery").
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
