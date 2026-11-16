package app.aroundtheblock.wallet.core.reconcile

import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wipeTestContext
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A lookup the user addressed to DigiScope goes to DigiScope, whatever own node is configured, and
 * a lookup addressed to the own node never falls back to DigiScope unnoticed: [DgbNodeClient.customEndpoint]
 * says whether one is set at all.
 */
class DgbNodeClientDigiScopeOnlyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val prefs = InMemoryPrefsFactory()
    private val ctx by lazy { wipeTestContext(prefs, tmp.root) }
    private val client = OkHttpClient()

    private fun setEndpoint(value: String?) {
        val p = prefs.prefsFor(WalletStores.prefsName("dgb_reconcile", WalletScope.legacy(false)))
        p.edit().apply { if (value == null) remove("custom_rpc_endpoint") else putString("custom_rpc_endpoint", value) }.commit()
    }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    /** A client that answers every request 503 and records where each one went. */
    private fun recording(urls: MutableList<String>) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        urls += chain.request().url.toString()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("down")
            .body("".toResponseBody()).build()
    }).build()

    @Test fun `own node only never falls back to digiscope when the setting is cleared after selection`() {
        setEndpoint("https://node.example")
        val urls = mutableListOf<String>()
        val node = DgbNodeClient.ownNodeOnly(ctx, recording(urls), DgbNodeClient(ctx, client).customEndpoint()!!)
        setEndpoint(null)   // cleared mid-flow

        assertEquals("https://node.example", node.endpoint())
        val answer = runBlocking { node.reconcileAddresses(List(600) { "addr$it" }) }

        assertNull("an unanswered own node must read as failed", answer)
        assertTrue("scanner is blind: no request made", urls.isNotEmpty())
        assertTrue("a request reached DigiScope: $urls", urls.none { it.startsWith(DgbNodeClient.DEFAULT_BASE_URL) })
        assertTrue(urls.all { it.startsWith("https://node.example/") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `own node only refuses a blank endpoint`() {
        DgbNodeClient.ownNodeOnly(ctx, client, "  / ")
    }

    @Test fun `digiscope only ignores the custom endpoint`() {
        setEndpoint("https://node.example")
        assertEquals(DgbNodeClient.DEFAULT_BASE_URL, DgbNodeClient.digiScopeOnly(ctx, client).endpoint())
        assertEquals("https://node.example", DgbNodeClient(ctx, client).customEndpoint())
        assertEquals("https://node.example", DgbNodeClient(ctx, client).endpoint())
    }

    @Test fun `no custom endpoint reads as none`() {
        setEndpoint(null)
        assertNull(DgbNodeClient(ctx, client).customEndpoint())
        setEndpoint("   ")
        assertNull(DgbNodeClient(ctx, client).customEndpoint())
        setEndpoint(" https://node.example/ ")
        assertEquals("https://node.example", DgbNodeClient(ctx, client).customEndpoint())
        assertEquals(DgbNodeClient.DEFAULT_BASE_URL, DgbNodeClient.digiScopeOnly(ctx, client).endpoint())
    }
}
