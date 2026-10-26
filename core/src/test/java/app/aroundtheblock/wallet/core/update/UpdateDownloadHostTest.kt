package app.aroundtheblock.wallet.core.update

import android.util.Log
import app.aroundtheblock.wallet.core.UpdateChecker
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * GUARD (shipped in 4.0.89 with the URL check UpdateReleaseUrlTest covers; no red can exist on this
 * base): driven through [UpdateChecker.checkForUpdate] itself, an update download link outside this
 * app's release downloads is dropped. The dialog then offers the release page, which must itself be
 * this repository's, or no update is offered at all.
 *
 * The GitHub answer is served by an application interceptor; no socket is opened.
 */
class UpdateDownloadHostTest {

    private val repo = "JohnnyLawDGB/aroundtheblock-wallet-releases"
    private val page = "https://github.com/$repo/releases/tag/v9.9.9"
    private val apk = "https://github.com/$repo/releases/download/v9.9.9/aroundtheblock-wallet-v9.9.9.apk"

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    @Test fun `this repository's download link is offered`() {
        val update = check(release(page, apk))
        assertNotNull(update)
        assertEquals(apk, update!!.downloadUrl)
        assertEquals(page, update.htmlUrl)
    }

    @Test fun `a download link outside the release downloads is dropped for the release page`() {
        val elsewhere = listOf(
            "https://example.net/aroundtheblock-wallet-v9.9.9.apk",
            "http://github.com/$repo/releases/download/v9.9.9/a.apk",
            "https://github.com.example.net/$repo/releases/download/v9.9.9/a.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/a.apk",
            "https://github.com/someone-else/aroundtheblock-wallet-releases/releases/download/v9.9.9/a.apk",
            "https://github.com/$repo/archive/main.zip",
            "https://user@github.com/$repo/releases/download/v9.9.9/a.apk",
            "file:///sdcard/Download/a.apk",
        )
        for (url in elsewhere) {
            for (beta in listOf(false, true)) {
                val update = check(release(page, url), beta)
                assertNotNull("$url (beta=$beta)", update)
                assertEquals("$url was offered (beta=$beta)", page, update!!.downloadUrl)
            }
        }
    }

    @Test fun `a release page outside this repository offers no update`() {
        for (other in listOf(
            "https://example.net/$repo/releases/tag/v9.9.9",
            "https://github.com/someone-else/aroundtheblock-wallet-releases/releases/tag/v9.9.9",
        )) {
            assertNull(other, check(release(other, apk)))
            assertNull(other, check(release(other, apk), beta = true))
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun release(htmlUrl: String, apkUrl: String): String =
        """{"tag_name":"v9.9.9","html_url":"$htmlUrl","prerelease":false,"draft":false,"body":"notes",""" +
            """"assets":[{"name":"aroundtheblock-wallet-v9.9.9.apk","browser_download_url":"$apkUrl"}]}"""

    /** The update [UpdateChecker] offers a 0.0.1 install when GitHub answers [release] (as a list for the beta channel). */
    private fun check(release: String, beta: Boolean = false) = runBlocking {
        val answer = if (beta) "[$release]" else release
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(answer.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        UpdateChecker(client, repo).checkForUpdate("0.0.1", includePrereleases = beta)
    }
}
