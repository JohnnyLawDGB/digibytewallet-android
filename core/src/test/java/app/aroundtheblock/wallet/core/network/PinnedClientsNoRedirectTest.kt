package app.aroundtheblock.wallet.core.network

import android.content.Context
import app.aroundtheblock.wallet.core.asset.network.DigiScopeAssetClient
import app.aroundtheblock.wallet.core.asset.network.DigistampAssetClient
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.digistamp.DigistampClient
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 4.1.2 row 30 (C18; kahalihan6 1a14aa19): a pinned client never follows a redirect. A 3xx from a pinned
 * host would carry the request -- and its token, for the Hub -- to a host the pins do not cover.
 */
class PinnedClientsNoRedirectTest {

    private val base = OkHttpClient()

    private fun clientOf(owner: Any): OkHttpClient =
        owner.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(owner) as OkHttpClient

    @Test fun `no pinned client follows a redirect`() {
        io.mockk.mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any<String>()) } returns 0
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns app.aroundtheblock.wallet.core.InMemoryPrefs()
        val clients = mapOf(
            "DigistampAssetClient" to clientOf(DigistampAssetClient(base)),
            "DigistampClient" to clientOf(DigistampClient(base)),
            "DigiScopeAssetClient" to clientOf(DigiScopeAssetClient(base)),
            "DigiScopeClient" to clientOf(DigiScopeClient(base, ctx)),
        )
        for ((name, c) in clients) {
            assertFalse("$name follows redirects", c.followRedirects)
            assertFalse("$name follows https->http redirects", c.followSslRedirects)
        }
        io.mockk.unmockkStatic(android.util.Log::class)
    }
}
