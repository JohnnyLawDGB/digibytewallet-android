package app.aroundtheblock.wallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import app.aroundtheblock.wallet.core.network.DigiScopePins
import app.aroundtheblock.wallet.core.tor.TorManager
import app.aroundtheblock.wallet.core.tor.TorNotReadyException
import app.aroundtheblock.wallet.core.tor.TorRoute
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** How long a request made while Tor is on but not yet connected waits for it before failing. */
    internal const val TOR_WAIT_MS = 30_000L

    /**
     * The route for a request while Tor is on and not connected: a SOCKS proxy on loopback port 0.
     * Nothing can listen on port 0, so the connect is refused on the device and the request goes
     * nowhere. Only reached if Tor drops between [TorWaitInterceptor]'s check and the connect.
     */
    private val UNROUTABLE = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    /** Where an app-made connection may go right now, from the user's Tor setting and Tor's state. */
    fun torRoute(torManager: TorManager): TorRoute = torManager.route()

    @Provides
    @Singleton
    fun provideOkHttpClient(torManager: TorManager): OkHttpClient = buildSharedClient({ torRoute(torManager) })

    /**
     * The ONE client the app's HTTP and WebSocket traffic is made on; every other client is derived
     * from it with `newBuilder()`, which keeps everything set here.
     *
     * Routing ([TorRoute]): Tor connected → its SOCKS proxy; the user's Tor setting off → direct;
     * Tor on and not connected → the request WAITS for Tor ([TorWaitInterceptor]) and then fails
     * with [TorNotReadyException] — it is never sent direct. The one way to direct with the setting
     * on is the clearnet fallback, which SyncService takes only after raising the "Tor unavailable"
     * banner ([TorManager.announceClearnetFallback]).
     *
     * Pins: the DigiScope pins ([DigiScopePins]) are set here, so every connection to that host is
     * pinned whichever client makes it — the Hub WebSocket, the IPFS gateway list, the Digi-ID
     * callback, Coil, the reconcile client. OkHttp checks a pinner only for hosts it holds pins
     * for; every other host gets ordinary TLS validation.
     */
    internal fun buildSharedClient(route: () -> TorRoute, torWaitMs: Long = TOR_WAIT_MS): OkHttpClient {
        val torProxySelector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = when (val now = route()) {
                is TorRoute.Socks -> listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", now.port)))
                TorRoute.Direct -> listOf(Proxy.NO_PROXY)
                // Never an empty list: OkHttp reads an empty answer as "go direct".
                TorRoute.Blocked -> listOf(UNROUTABLE)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                // No-op: OkHttp retries or surfaces the error normally.
            }
        }

        // Name lookups. Through a SOCKS proxy OkHttp hands the host name to the proxy unresolved
        // (Tor resolves it at the exit), so no local lookup happens; this Dns makes sure none can
        // happen by any other path either. Tor's SafeSocks is OFF (TorManager: the C core dials
        // peers by IP), so Tor itself would not refuse a request whose name was resolved locally —
        // keeping lookups off the device's resolver while Tor is on is done here.
        val torDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = when (route()) {
                // Not used for the connection (the name goes to the proxy); no lookup is made.
                is TorRoute.Socks -> listOf(InetAddress.getLoopbackAddress())
                TorRoute.Direct -> Dns.SYSTEM.lookup(hostname)
                TorRoute.Blocked -> throw UnknownHostException("Tor is on and not connected yet: no local lookup of $hostname")
            }
        }

        // Owned here so a change of route can drop idle connections made on the other one; every
        // client derived with newBuilder() shares this pool.
        val pool = ConnectionPool()

        return OkHttpClient.Builder()
            .addInterceptor(TorWaitInterceptor(route, pool, torWaitMs))
            .connectionPool(pool)
            .proxySelector(torProxySelector)
            .dns(torDns)
            .certificatePinner(DigiScopePins.certificatePinner())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // WHOLE-CALL bound. connect/read/write are PER-PHASE and per-attempt: with
            // retries, redirects and a slow-but-not-dead peer a single call can exceed all
            // three and run effectively unbounded. That is load-bearing here because the
            // seeder fetch runs inside the 0-peer recovery path, and a hang there used to
            // wedge the recovery watchdog outright (Note 8, 2026-08-02: 47 minutes of
            // silence after "reviving recovery"). It also bounds a wait for Tor.
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Holds a request while the user's Tor setting is on and Tor is not connected, so it is never
     * sent outside Tor. It goes ahead as soon as Tor connects (or the clearnet fallback has been
     * announced, or the user turns Tor off); after [waitMs], or when the call is cancelled or hits
     * its call timeout, it fails with [TorNotReadyException] / an IOException, which every caller
     * already treats as a network failure.
     *
     * When the route changes (direct ↔ Tor, or a new Tor port), idle pooled connections opened on
     * the previous route are evicted, so a request made after the change does not reuse one.
     */
    internal class TorWaitInterceptor(
        private val route: () -> TorRoute,
        private val pool: ConnectionPool,
        private val waitMs: Long,
        private val pollMs: Long = 250L,
    ) : Interceptor {
        @Volatile private var lastRoute: TorRoute? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
            var now = route()
            while (now is TorRoute.Blocked) {
                if (chain.call().isCanceled()) throw IOException("Canceled while waiting for Tor")
                if (System.nanoTime() - deadline >= 0) {
                    throw TorNotReadyException("Tor is on and not connected yet; the request was not sent")
                }
                try {
                    Thread.sleep(pollMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while waiting for Tor")
                }
                now = route()
            }
            val previous = lastRoute
            if (previous != null && previous != now) pool.evictAll()
            lastRoute = now
            return chain.proceed(chain.request())
        }
    }
}
