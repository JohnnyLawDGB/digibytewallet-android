package io.digibyte.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.digibyte.core.tor.TorManager
import okhttp3.OkHttpClient
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
        // Use a ProxySelector so that the singleton OkHttpClient adapts dynamically
        // when Tor connects or disconnects — no rebuild needed.
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
                // No-op: OkHttp will retry or surface the error normally.
            }
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .proxySelector(torProxySelector)
            .build()
    }
}
