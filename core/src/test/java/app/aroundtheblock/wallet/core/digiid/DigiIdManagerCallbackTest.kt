package app.aroundtheblock.wallet.core.digiid

import android.util.Log
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import app.aroundtheblock.wallet.core.model.DigiIdResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Digi-ID callback is posted to the host the user approved and nowhere else: the manager
 * checks the parsed URL it will post to, and does not follow a redirect away from it.
 */
class DigiIdManagerCallbackTest {

    private var requestsMade = 0
    private val base = OkHttpClient.Builder()
        .addInterceptor { chain -> requestsMade++; error("no request expected: ${chain.request().url}") }
        .build()

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private fun manager() = DigiIdManager(base, mockk(relaxed = true), mockk(relaxed = true))

    private fun clientOf(manager: DigiIdManager): OkHttpClient =
        manager.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }.get(manager) as OkHttpClient

    @Test fun `the callback client does not follow redirects`() {
        assertTrue("the shared client is expected to follow redirects", base.followRedirects && base.followSslRedirects)
        val client = clientOf(manager())
        assertFalse("the Digi-ID client follows redirects", client.followRedirects)
        assertFalse("the Digi-ID client follows https redirects", client.followSslRedirects)
        assertEquals("the shared client's interceptors are carried over", base.interceptors, client.interceptors)
    }

    @Test fun `a domain that is not the callback host is refused before anything is signed or sent`() {
        val request = DigiIdRequest.parse("digiid://node.legit.com/cb?x=n")
        assertNotNull(request)
        val parent = request!!.copy(domain = "legit.com")

        val result = runBlocking { manager().authenticate(parent) }

        assertTrue(result is DigiIdResult.Error)
        assertEquals(1, (result as DigiIdResult.Error).code)
        assertEquals(0, requestsMade)
    }

    @Test fun `a callback URL carrying user info is refused before anything is signed or sent`() {
        val request = DigiIdRequest.parse("digiid://evil.com/cb?x=n")!!
        val withUser = request.copy(callbackHttpUrl = "https://legit.com:443@evil.com/cb".toHttpUrl())

        val result = runBlocking { manager().authenticate(withUser) }

        assertTrue(result is DigiIdResult.Error)
        assertEquals(0, requestsMade)
    }
}
