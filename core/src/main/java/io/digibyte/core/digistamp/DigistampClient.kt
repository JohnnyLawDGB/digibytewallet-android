package io.digibyte.core.digistamp

import io.digibyte.core.asset.network.endpointOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to digistamp's auth API on the wallet's own terms — pinned, and never through the
 * WebView.
 *
 * The wallet fetches the Digi-ID challenge itself rather than scraping it out of the page,
 * because the page is third-party content and the URI it hands over is the thing the wallet's
 * key is about to sign.
 */
class DigistampClient(
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(io.digibyte.core.network.DigistampPins.certificatePinner())
        .build()

    /** A fresh Digi-ID challenge, or null if the site is unreachable or answered with anything
     *  that isn't a challenge about digistamp. */
    suspend fun challenge(): DigistampChallenge? = withContext(Dispatchers.IO) {
        val body = getBody(DigistampUris.CHALLENGE_URL) ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        DigistampUris.parseChallenge(json)
    }

    /** Whether the site currently considers this client signed in. */
    suspend fun isAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        val body = getBody(DigistampUris.SESSION_URL) ?: return@withContext false
        runCatching { JSONObject(body).optBoolean("authenticated", false) }.getOrDefault(false)
    }

    private fun getBody(url: String): String? = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w("DigistampClient", "GET ${endpointOf(url)} → HTTP ${resp.code}")
                null
            } else {
                resp.body?.string()
            }
        }
    } catch (e: Exception) {
        // Never swallow silently — a blank digistamp screen with no log line is undebuggable.
        android.util.Log.w("DigistampClient", "GET ${endpointOf(url)} failed", e)
        null
    }
}
