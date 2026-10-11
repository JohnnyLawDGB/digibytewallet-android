package app.aroundtheblock.wallet.service

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The one way the wallet reads the seeder (`api.digiscope.me/api/peers`): a GET whose answer is
 * the peer list JSON the callers parse. Shared by the foreground sync service and the background
 * catch-up worker so the two never drift in how they reach the seeder.
 *
 * The seeder list decides which nodes the wallet asks for headers and filters, so the request is
 * made like every other DigiScope call — to a server that proves the pinned identity — and its
 * answer is read only up to [MAX_BODY_BYTES]: a larger one is refused unread rather than held.
 * Either way a refused answer is null, which is what the callers fall back from (their cached list).
 *
 * The client is derived from the shared one, so the Tor proxy, DNS and the whole-call timeout the
 * shared client carries apply to the seeder request too.
 */
class SeederClient(baseClient: OkHttpClient, private val bodyCap: Long = MAX_BODY_BYTES) {

    // Shared pin set — see app.aroundtheblock.wallet.core.network.DigiScopePins. Derived the same way as
    // DigiScopeClient, DigiScopeAssetClient and DgbNodeClient derive theirs, so replacing all of
    // them with one shared pinned client is a change to this one line. Redirects are not followed:
    // a pinner checks only the hosts it holds pins for, so a redirect to any other host would be
    // served with no identity check — the list must come from the pinned server itself.
    /** The client every seeder request is made with. */
    val client: OkHttpClient = baseClient.newBuilder()
        .certificatePinner(app.aroundtheblock.wallet.core.network.DigiScopePins.certificatePinner())
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * GET [url]. Returns the body text on a successful answer that fits in [bodyCap], or null when
     * the request fails, the answer is not a success, the body is larger than the bound, or [url]
     * names a host the client holds no pins for (a pinner checks only the hosts it has pins for, so
     * such a request would be made with no identity check); [onFailure] is told why in one line.
     */
    fun fetch(url: String, onFailure: (String) -> Unit = {}): String? {
        return try {
            val host = url.toHttpUrl().host
            if (client.certificatePinner.findMatchingPins(host).isEmpty()) {
                onFailure("Seeder URL names $host, a host the client holds no pins for")
                return null
            }
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onFailure("Seeder API returned ${response.code}")
                    return null
                }
                val body = response.body
                if (body == null) {
                    onFailure("Seeder API answered without a body")
                    return null
                }
                if (body.contentLength() > bodyCap) {
                    onFailure("Seeder API body of ${body.contentLength()} bytes is over the $bodyCap-byte bound")
                    return null
                }
                // request(n) buffers until n bytes are held or the source ends, one segment at a
                // time, so at most the bound plus one segment is ever held; when it can be filled
                // past the bound the answer is over it and is refused without reading the rest.
                val source = body.source()
                if (source.request(bodyCap + 1)) {
                    onFailure("Seeder API body is over the $bodyCap-byte bound")
                    return null
                }
                source.readUtf8()
            }
        } catch (e: Exception) {
            onFailure("Seeder API unreachable: ${e.message}")
            null
        }
    }

    companion object {
        /** The most a seeder answer may be: 256 KiB. A thousand-peer list with services is about 100 KB. */
        const val MAX_BODY_BYTES = 256L * 1024
    }
}
