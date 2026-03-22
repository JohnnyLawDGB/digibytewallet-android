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
        /** Ordered list of trustless IPFS gateways to try. */
        val DEFAULT_GATEWAYS = listOf(
            "https://trustless-gateway.link",
            "https://dweb.link",
            "https://ipfs.io"
        )

        /** Maximum allowed response body size (5 MiB). */
        const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L // 5 MB as Long for contentLength check

        /** Per-request timeout in milliseconds (used by the shared OkHttpClient). */
        const val TIMEOUT_MS = 15_000L
    }

    /**
     * Fetch content identified by [cid] from IPFS and verify its hash.
     *
     * Tries each gateway in [gateways] in order.  Returns the raw bytes on
     * the first gateway that returns a body whose hash matches the CID.
     * Returns `null` if all gateways fail or return unverifiable content.
     */
    suspend fun fetchVerified(cid: String): ByteArray? = withContext(Dispatchers.IO) {
        for (gateway in gateways) {
            val result = tryGateway(gateway, cid)
            if (result != null) return@withContext result
        }
        null  // all gateways failed or returned bad data
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun tryGateway(gateway: String, cid: String): ByteArray? {
        return try {
            val url = "$gateway/ipfs/$cid?format=raw"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.ipld.raw")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body ?: return null

                // Reject oversized content before buffering
                val contentLength = body.contentLength()
                if (contentLength > MAX_RESPONSE_SIZE) return null

                val bytes = body.bytes()
                if (bytes.size.toLong() > MAX_RESPONSE_SIZE) return null

                // Critical: verify the hash matches the CID before trusting the bytes
                if (cidVerifier.verify(cid, bytes)) bytes else null
            }
        } catch (_: Exception) {
            null  // network error — try next gateway
        }
    }
}
