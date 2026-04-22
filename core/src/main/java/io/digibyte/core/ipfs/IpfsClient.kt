package io.digibyte.core.ipfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches IPFS content from public gateways and verifies the payload against the CID.
 *
 * Security guarantees:
 *  - Every response is hashed and compared to the CID digest before it is returned.
 *    A malicious or misconfigured gateway cannot inject fake metadata.
 *  - Responses larger than [MAX_RESPONSE_SIZE] are rejected to prevent OOM.
 *  - Gateways are tried in order; failures (network or hash mismatch) cause a
 *    transparent fall-through to the next gateway.
 *
 * No IPFS daemon or libp2p dependency is required.
 */
class IpfsClient(
    private val httpClient: OkHttpClient,
    private val cidVerifier: CidVerifier = CidVerifier(),
    private val gateways: List<String> = DEFAULT_GATEWAYS
) {
    companion object {
        /** Ordered list of IPFS gateways to try. Our own digiscope.me proxy
         *  is tried first — it resolves anything pinned on the local IPFS
         *  node (including freshly-minted test assets) without depending on
         *  DHT propagation to public gateways, which can take minutes or
         *  never happen at all for small pins. Public trustless gateways
         *  follow as the sovereign fallback. */
        val DEFAULT_GATEWAYS = listOf(
            "https://api.digiscope.me/api/ipfs/{cid}",  // local pin proxy, serves raw bytes
            "https://trustless-gateway.link/ipfs/{cid}?format=raw",
            "https://dweb.link/ipfs/{cid}?format=raw",
            "https://ipfs.io/ipfs/{cid}?format=raw"
        )

        /** Maximum allowed response body size (5 MiB). */
        const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L // 5 MB as Long for contentLength check

        /** Per-request timeout in milliseconds (used by the shared OkHttpClient). */
        const val TIMEOUT_MS = 15_000L

        private const val TAG = "IpfsClient"
    }

    /**
     * Fetch content identified by [cid] from IPFS and verify its hash.
     *
     * Tries each gateway in [gateways] in order.  Returns the raw bytes on
     * the first gateway that returns a body whose hash matches the CID.
     * Returns `null` if all gateways fail or return unverifiable content.
     */
    suspend fun fetchVerified(cid: String): ByteArray? = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "fetchVerified: cid=$cid")
        for (gateway in gateways) {
            val result = tryGateway(gateway, cid)
            if (result != null) {
                android.util.Log.i(TAG, "fetchVerified: OK via $gateway (${result.size}b)")
                return@withContext result
            }
        }
        android.util.Log.w(TAG, "fetchVerified: all gateways failed for $cid")
        null
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun tryGateway(gateway: String, cid: String): ByteArray? {
        return try {
            // Gateway template may contain `{cid}` placeholder. Legacy entries
            // without one get the traditional `/ipfs/<cid>?format=raw` suffix.
            val url = if (gateway.contains("{cid}")) {
                gateway.replace("{cid}", cid)
            } else {
                "$gateway/ipfs/$cid?format=raw"
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.ipld.raw")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.d(TAG, "tryGateway: $gateway → HTTP ${response.code} for $cid")
                    return null
                }

                val body = response.body ?: return null

                val contentLength = body.contentLength()
                if (contentLength > MAX_RESPONSE_SIZE) {
                    android.util.Log.w(TAG, "tryGateway: $gateway → oversized ($contentLength b) for $cid")
                    return null
                }

                val bytes = body.bytes()
                if (bytes.size.toLong() > MAX_RESPONSE_SIZE) return null

                if (cidVerifier.verify(cid, bytes)) {
                    bytes
                } else {
                    android.util.Log.w(TAG, "tryGateway: $gateway → hash MISMATCH for $cid (${bytes.size}b)")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.d(TAG, "tryGateway: $gateway threw for $cid — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

}
