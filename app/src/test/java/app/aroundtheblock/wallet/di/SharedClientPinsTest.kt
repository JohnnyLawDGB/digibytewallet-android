package app.aroundtheblock.wallet.di

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.UpdateChecker
import app.aroundtheblock.wallet.core.asset.network.MultiEndpointAssetClient
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.ipfs.IpfsClient
import app.aroundtheblock.wallet.core.network.DigiScopePins
import app.aroundtheblock.wallet.core.network.DigistampIdPins
import app.aroundtheblock.wallet.core.network.DigistampPins
import app.aroundtheblock.wallet.core.reconcile.DgbNodeClient
import app.aroundtheblock.wallet.core.tor.TorManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every connection to the DigiScope host is certificate-pinned, whichever client makes it.
 *
 * The pins sit on the ONE shared client Hilt hands out, so every client the app builds from it —
 * the Hub WebSocket (which carries the session token), the IPFS gateway list (whose first gateway
 * is the DigiScope proxy), the Digi-ID callback POST, Coil's image loader, the reconcile client —
 * holds them. OkHttp checks a pinner only for the hosts it has pins for, so every other host is
 * served with ordinary TLS, exactly as before.
 */
class SharedClientPinsTest {

    private fun context(torOn: Boolean = false): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean("tor_enabled", any()) } returns torOn
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationContext } returns context
        return context
    }

    private val shared: OkHttpClient by lazy { NetworkModule.provideOkHttpClient(TorManager(context())) }

    private fun assertPinsDigiScope(what: String, client: OkHttpClient) {
        val pins = client.certificatePinner.findMatchingPins(DigiScopePins.HOST)
        assertTrue("$what holds no pins for ${DigiScopePins.HOST}", pins.isNotEmpty())
        assertEquals("$what does not hold exactly the shared pin set", DigiScopePins.certificatePinner().pins, pins.toSet())
    }

    /** The field a component keeps its client in. Read reflectively: the components keep it private. */
    private fun clientOf(component: Any, field: String): OkHttpClient =
        component.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(component) as OkHttpClient

    @Test fun `the shared client pins the DigiScope host`() {
        assertPinsDigiScope("the shared client", shared)
    }

    /** GUARD: OkHttp applies a pinner only to the hosts it holds pins for. */
    @Test fun `the shared client holds no pins for any other host`() {
        for (host in listOf(
            "api.coingecko.com", "api.binance.com", "api.github.com", "assets.digistamp.co", "id.digistamp.co",
            "trustless-gateway.link", "dweb.link", "ipfs.io", "digiassets.net", "digiscope.me",
        )) {
            assertTrue("the shared client pins $host", shared.certificatePinner.findMatchingPins(host).isEmpty())
        }
    }

    @Test fun `the Hub WebSocket is made on a pinned client`() {
        val hub = AppModule.provideHubWebSocket(shared, mockk(relaxed = true), TorManager(context()))
        val client = clientOf(hub, "httpClient")
        assertPinsDigiScope("the Hub WebSocket's client", client)
        // OkHttp opens a WebSocket on a copy of the client it is given, changing only the event
        // listener and the protocol list; the pinner is carried over.
        val socketCopy = client.newBuilder().eventListener(EventListener.NONE).protocols(listOf(Protocol.HTTP_1_1)).build()
        assertPinsDigiScope("the WebSocket's copy of the client", socketCopy)
    }

    /**
     * HUB-WS-NO-REDIRECT: the Hub socket's client follows no redirect, and is the shared client in
     * every other respect: the same route selection, name lookups, connection pool, pins and
     * interceptors (the Tor wait among them). The shared client itself is unchanged, so no other
     * request's redirect behaviour moved.
     */
    @Test fun `the Hub WebSocket's client follows no redirect and keeps the shared client's routing`() {
        val hub = AppModule.provideHubWebSocket(shared, mockk(relaxed = true), TorManager(context()))
        val client = clientOf(hub, "httpClient")
        assertTrue("the Hub socket's client follows redirects", !client.followRedirects && !client.followSslRedirects)
        // OkHttp opens the socket on a copy of that client; the switches are carried over.
        val socketCopy = client.newBuilder().eventListener(EventListener.NONE).protocols(listOf(Protocol.HTTP_1_1)).build()
        assertTrue("the WebSocket's copy follows redirects", !socketCopy.followRedirects && !socketCopy.followSslRedirects)

        assertTrue("another proxy selector", client.proxySelector === shared.proxySelector)
        assertTrue("another Dns", client.dns === shared.dns)
        assertTrue("another connection pool", client.connectionPool === shared.connectionPool)
        assertTrue("another dispatcher", client.dispatcher === shared.dispatcher)
        assertEquals(shared.certificatePinner, client.certificatePinner)
        assertEquals(shared.interceptors.size, client.interceptors.size)
        assertTrue("another interceptor", shared.interceptors.zip(client.interceptors).all { (a, b) -> a === b })
        assertTrue("the Tor wait is not on the Hub socket's client",
            client.interceptors.any { it is NetworkModule.TorWaitInterceptor })
        assertEquals(shared.callTimeoutMillis, client.callTimeoutMillis)

        assertTrue("the shared client's own redirect behaviour changed", shared.followRedirects && shared.followSslRedirects)
    }

    /**
     * The Hub socket carries the session token, and the pins cover only the DigiScope host, so the
     * socket the app provides is opened at that host's address and at no other.
     */
    @Test fun `the Hub WebSocket the app provides is opened at the pinned host`() {
        val hub = AppModule.provideHubWebSocket(shared, mockk(relaxed = true), TorManager(context()))
        val url = privateFieldOf(hub, "url") as String
        assertEquals("the provided Hub socket is opened somewhere else", HubWebSocket.WS_URL, url)
        val parsed = url.replaceFirst("wss://", "https://").toHttpUrlOrNull()
        assertTrue("$url is not a wss address", url.startsWith("wss://") && parsed != null)
        assertEquals("the Hub socket's host is not the pinned one", DigiScopePins.HOST, parsed!!.host)
    }

    @Test fun `the IPFS gateway fetch is made on a pinned client`() {
        val ipfs = AppModule.provideIpfsClient(shared)
        assertPinsDigiScope("the IPFS client", clientOf(ipfs, "httpClient"))
        // IPFS-NO-REDIRECT: the trusted gateway is asked on a client derived from that one. It keeps
        // the pins and follows no redirect, so what it reads is the pinned host's own answer.
        val trusted = (privateFieldOf(ipfs, "trustedGatewayClient\$delegate") as Lazy<*>).value as OkHttpClient
        assertPinsDigiScope("the IPFS client's trusted-gateway client", trusted)
        assertTrue("the trusted-gateway client follows redirects", !trusted.followRedirects && !trusted.followSslRedirects)
    }

    /**
     * IPFS-NO-REDIRECT: trust-by-pin covers only hosts the pinner holds pins for. The gateways the
     * shipped IPFS client accepts on identity are the one list in IpfsClient, and every entry is an
     * https origin, ended at the host, that the client it is asked on has pins for.
     */
    @Test fun `every gateway the IPFS client trusts on identity is a host the client pins`() {
        val ipfs = AppModule.provideIpfsClient(shared)
        val trusted = privateFieldOf(ipfs, "trustedGatewayPrefixes") as List<*>
        assertEquals("the shipped IPFS client trusts a list of its own", IpfsClient.TRUSTED_GATEWAY_PREFIXES, trusted)
        assertTrue("the IPFS client trusts no gateway at all", trusted.isNotEmpty())
        for (prefix in IpfsClient.TRUSTED_GATEWAY_PREFIXES) {
            val url = prefix.toHttpUrlOrNull()
            assertTrue("$prefix is not an https origin ended at its host", url != null && url.isHttps && prefix == "https://${url.host}/")
            assertTrue("$prefix is trusted on identity but ${url!!.host} holds no pins",
                shared.certificatePinner.findMatchingPins(url.host).isNotEmpty())
        }
    }

    /** A private member of a component, by the name the compiler gives its field (`name$delegate` for `by lazy`). */
    private fun privateFieldOf(component: Any, field: String): Any? = try {
        component.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(component)
    } catch (e: NoSuchFieldException) {
        throw AssertionError("${component.javaClass.simpleName} has no field '$field': it was renamed or is no longer " +
            "held this way; update this test to read it where it now lives", e)
    }

    @Test fun `the Digi-ID callback is posted on a pinned client`() {
        val manager = AppModule.provideDigiIdManager(shared, mockk(relaxed = true), mockk(relaxed = true))
        assertPinsDigiScope("the Digi-ID manager's client", clientOf(manager, "httpClient"))
    }

    /**
     * MARKET-SIGNIN-ID-HOST: the Market signs in at DigiStamp's Digi-ID host, so the signed answer the
     * wallet posts there goes out on a client that holds that host's pins, all of them and no others.
     * The shared client holds none for it (the guard above), so this is the manager's own pinner.
     */
    @Test fun `the Digi-ID callback to DigiStamp's sign-in host is pinned`() {
        val manager = AppModule.provideDigiIdManager(shared, mockk(relaxed = true), mockk(relaxed = true))
        val pinner = clientOf(manager, "httpClient").certificatePinner
        for ((host, pins) in listOf(DigistampIdPins.HOST to DigistampIdPins.PINS, DigistampPins.HOST to DigistampPins.PINS)) {
            assertEquals("the Digi-ID manager's pins for $host",
                pins.toSet(), pinner.findMatchingPins(host).map { "sha256/" + it.hash.base64() }.toSet())
        }
        // A site that is neither DigiStamp nor DigiScope is not pinned: ordinary TLS validation.
        assertTrue(pinner.findMatchingPins("example.org").isEmpty())
        assertTrue("a subdomain of the sign-in host is pinned as if it were the host",
            pinner.findMatchingPins("x.id.digistamp.co").isEmpty())
    }

    @Test fun `the reconcile client derives its clients from a pinned one`() {
        val node = AppModule.provideDgbNodeClient(context(), shared)
        assertPinsDigiScope("the reconcile client (default endpoint)", clientOf(node, "pinnedClient"))
        assertPinsDigiScope("the reconcile client (own endpoint)", clientOf(node, "unpinnedClient"))
    }

    /**
     * Every endpoint of the asset metadata chain, as AppModule builds it, reaches the DigiScope host
     * only through its pins. A client that sets a pinner of its own replaces the shared one, so a
     * pinner holding another host's pins alone would leave the DigiScope host unpinned on that client
     * (reachable by a redirect).
     */
    @Test fun `every asset chain endpoint reaches the DigiScope host only through its pins`() {
        val chain = AppModule.provideAssetNetworkClient(shared) as MultiEndpointAssetClient
        val endpoints = chain.javaClass.getDeclaredField("endpoints").apply { isAccessible = true }.get(chain) as List<*>
        assertTrue("the asset chain has no endpoints", endpoints.isNotEmpty())
        for (endpoint in endpoints) {
            assertPinsDigiScope("the asset endpoint ${endpoint!!.javaClass.simpleName}", clientOf(endpoint, "client"))
        }
    }

    @Test fun `the asset stack lookup reaches the DigiScope host only through its pins`() {
        val source = app.aroundtheblock.wallet.core.asset.network.DigiScopeAssetClient(baseClient = shared)
        assertPinsDigiScope("the asset stack lookup", clientOf(source, "stackClient"))
    }

    /** GUARD: the update check is made on the shared client itself. */
    @Test fun `the update check is made on a pinned client`() {
        assertPinsDigiScope("the update checker", clientOf(UpdateChecker(shared, "owner/releases"), "client"))
    }

    /** GUARD: a client that sets its own pinner sets the same pins, so nothing conflicts. */
    @Test fun `clients that set their own pinner set the same pins`() {
        val node = DgbNodeClient(context(), shared)
        assertEquals(shared.certificatePinner.findMatchingPins(DigiScopePins.HOST).toSet(),
            clientOf(node, "pinnedClient").certificatePinner.findMatchingPins(DigiScopePins.HOST).toSet())
        assertEquals(DigiScopePins.certificatePinner().pins,
            app.aroundtheblock.wallet.service.SeederClient(shared).client.certificatePinner.pins)
    }
}
