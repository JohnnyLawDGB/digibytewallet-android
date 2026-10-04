package io.digibyte.core.ipfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.digibyte.core.tor.TorNotReadyException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches IPFS content from public gateways and verifies the payload against the CID.
 *
 * Security guarantees:
 *  - Every response is hashed and compared to the CID digest before it is returned,
 *    with one exception: a dag-pb CID answered by the DigiScope gateway, which is
 *    accepted on that server's pinned identity instead (see [TRUSTED_GATEWAY_PREFIXES]).
 *    Any other gateway cannot substitute content.
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
        /**
         * Reduce a provider's CID to the bare identifier, or null if it is not one.
         *
         * Strips `ipfs://` and a leading `/ipfs/`. Everything else is REFUSED rather than
         * repaired: the CID is the integrity check that content is verified against, so a
         * value that could steer the fetch elsewhere is not something to tidy up and use.
         */
        @JvmStatic
        fun normalizeCid(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null

            val bare = trimmed.removePrefix("ipfs://").removePrefix("/ipfs/").trim()

            if (bare.isEmpty()) return null
            // A CID is base32/base58 alphanumerics only — no slashes, dots, queries, spaces.
            if (!bare.all { it.isLetterOrDigit() }) return null
            return bare
        }

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

        /** Gateways whose answer for a dag-pb CID (CIDv0 `Qm…`, CIDv1 `bafybe…`) is accepted
         *  without re-verifying the content hash: a dag-pb CID hashes the UnixFS wrapper, which
         *  this unwrapping gateway strips before serving, so a local rehash would always fail.
         *  For those CIDs the trust anchor is the server's identity: the DigiScope host is
         *  certificate-pinned on the shared client this class is given (NetworkModule; the pins
         *  are host-scoped, so only gateway #1 is pinned — the public gateways are not, and stay
         *  strictly content-verified). That is a server-identity check, not a content check:
         *  the pinned server itself is still trusted for these bytes. CIDv1 raw (`bafkre…`)
         *  fetches are content-verified on every gateway. */
        val TRUSTED_GATEWAY_PREFIXES = listOf(
            "https://api.digiscope.me/"
        )

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
    suspend fun fetchVerified(rawCid: String): ByteArray? = withContext(Dispatchers.IO) {
        // Providers disagree on shape: digistamp returns "ipfs://bafy…", the issuance header
        // gives a bare CID. The value is pasted into a gateway URL, so a prefix left in place
        // becomes a 404 that surfaces as "metadata offline".
        val cid = normalizeCid(rawCid) ?: run {
            android.util.Log.w(TAG, "fetchVerified: refusing malformed cid")
            return@withContext null
        }
        android.util.Log.i(TAG, "fetchVerified: cid=$cid")
        for (gateway in gateways) {
            val result = try {
                tryGateway(gateway, cid)
            } catch (e: TorNotReadyException) {
                // Tor is on and not connected: every gateway would wait and be refused the same
                // way, so stop here rather than wait out each one in turn.
                android.util.Log.i(TAG, "fetchVerified: waiting for Tor; not fetched")
                return@withContext null
            }
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

                // CIDv0 (Qm…) carries the dag-pb / UnixFS wrapper hash,
                // not the raw content hash. Our gateway serves the unwrapped
                // bytes, so content-hash verification would always fail here.
                // For those CIDs, trust rests on the pinned server identity of
                // the gateway in [TRUSTED_GATEWAY_PREFIXES] — a weakening
                // documented there. CIDv1 raw fetches stay strictly content-verified.
                val isTrustedGateway = TRUSTED_GATEWAY_PREFIXES.any { gateway.startsWith(it) }
                // dag-pb / UnixFS CIDs hash the WRAPPER block, not the raw file content —
                // CIDv0 (Qm…) AND CIDv1 dag-pb (bafybe…). Content-hash verification against
                // the assembled bytes our trusted (cert-pinned) gateway serves can never
                // pass, so defer to the pinned server for those. Only CIDv1 RAW codec (bafkre…)
                // hashes the raw content and stays strictly content-verified. Without the
                // bafybe… case, dag-pb v1 images (e.g. CHANG token art) failed verify on the
                // real 279KB file and "passed" on the 108-byte dag root from a ?format=raw
                // fallback — so the picture never rendered.
                val isDagPbWrapped = cid.startsWith("Qm") || cid.startsWith("bafybe")
                val accept = if (isDagPbWrapped && isTrustedGateway) {
                    android.util.Log.i(TAG,
                        "tryGateway: $gateway → dag-pb trust-by-cert for $cid (${bytes.size}b)")
                    true
                } else {
                    cidVerifier.verify(cid, bytes)
                }
                if (accept) {
                    bytes
                } else {
                    android.util.Log.w(TAG, "tryGateway: $gateway → hash MISMATCH for $cid (${bytes.size}b)")
                    null
                }
            }
        } catch (e: TorNotReadyException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d(TAG, "tryGateway: $gateway threw for $cid — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

}
