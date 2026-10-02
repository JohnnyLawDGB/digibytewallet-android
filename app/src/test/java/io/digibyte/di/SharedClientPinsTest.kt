package io.digibyte.di

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.network.DigiScopePins
import io.digibyte.core.reconcile.DgbNodeClient
import io.digibyte.core.tor.TorManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.EventListener
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
            "api.coingecko.com", "api.binance.com", "api.github.com", "assets.digistamp.co",
            "trustless-gateway.link", "dweb.link", "ipfs.io", "digiassets.net", "digiscope.me",
        )) {
            assertTrue("the shared client pins $host", shared.certificatePinner.findMatchingPins(host).isEmpty())
        }
    }

    @Test fun `the Hub WebSocket is made on a pinned client`() {
        val hub = AppModule.provideHubWebSocket(shared, mockk(relaxed = true))
        val client = clientOf(hub, "httpClient")
        assertPinsDigiScope("the Hub WebSocket's client", client)
        // OkHttp opens a WebSocket on a copy of the client it is given, changing only the event
        // listener and the protocol list; the pinner is carried over.
        val socketCopy = client.newBuilder().eventListener(EventListener.NONE).protocols(listOf(Protocol.HTTP_1_1)).build()
        assertPinsDigiScope("the WebSocket's copy of the client", socketCopy)
    }

    @Test fun `the IPFS gateway fetch is made on a pinned client`() {
        assertPinsDigiScope("the IPFS client", clientOf(AppModule.provideIpfsClient(shared), "httpClient"))
    }

    @Test fun `the Digi-ID callback is posted on a pinned client`() {
        val manager = AppModule.provideDigiIdManager(shared, mockk(relaxed = true), mockk(relaxed = true))
        assertPinsDigiScope("the Digi-ID manager's client", clientOf(manager, "httpClient"))
    }

    @Test fun `the reconcile client derives its clients from a pinned one`() {
        val node = AppModule.provideDgbNodeClient(context(), shared)
        assertPinsDigiScope("the reconcile client (default endpoint)", clientOf(node, "pinnedClient"))
        assertPinsDigiScope("the reconcile client (own endpoint)", clientOf(node, "unpinnedClient"))
    }

    /** GUARD: a client that sets its own pinner sets the same pins, so nothing conflicts. */
    @Test fun `clients that set their own pinner set the same pins`() {
        val node = DgbNodeClient(context(), shared)
        assertEquals(shared.certificatePinner.findMatchingPins(DigiScopePins.HOST).toSet(),
            clientOf(node, "pinnedClient").certificatePinner.findMatchingPins(DigiScopePins.HOST).toSet())
        assertEquals(DigiScopePins.certificatePinner().pins,
            io.digibyte.service.SeederClient(shared).client.certificatePinner.pins)
    }
}
