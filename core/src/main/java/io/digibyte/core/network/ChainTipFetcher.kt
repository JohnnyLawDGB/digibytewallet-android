package io.digibyte.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches the authoritative DigiByte chain tip from api.digiscope.me so
 * the wallet's sync-progress UI can anchor its denominator to a stable
 * value instead of the peer-quorum `estimated_height` that SPV negotiates
 * on the fly — that value churns every time a peer joins with a different
 * tip claim, causing the progress percent to jump back and forth mid-sync
 * ("40% → 99% → 60% → 99%").
 *
 * No credentials, no wallet state — the request carries only a GET. A
 * failure returns 0 so callers can fall back to their local estimate.
 */
object ChainTipFetcher {
    private const val URL = "https://api.digiscope.me/api/chain/tip"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** Returns the current tip height, or 0 on any failure. Callers should
     *  treat 0 as "unknown" and fall back to their local estimate. */
    suspend fun fetch(): Long = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0L
                val body = resp.body?.string() ?: return@withContext 0L
                JSONObject(body).optLong("height", 0L)
            }
        } catch (_: Throwable) {
            0L
        }
    }
}
