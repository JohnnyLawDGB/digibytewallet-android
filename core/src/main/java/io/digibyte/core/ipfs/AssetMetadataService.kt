package io.digibyte.core.ipfs

import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.entity.AssetMetadataEntity
import io.digibyte.core.model.AssetMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches and caches DigiAsset metadata stored as JSON on IPFS.
 *
 * Cache policy:
 *  - If the asset's metadata is already in the Room database it is returned immediately,
 *    without any network request.  IPFS CIDs are content-addressed and immutable, so
 *    a cached entry is always valid.
 *  - If no cache entry exists and a [metadataCid] is provided, the JSON is fetched via
 *    [IpfsClient] (which verifies the content hash against the CID before returning it),
 *    parsed, persisted to Room, and then returned.
 *  - If [metadataCid] is null, returns null (no metadata to fetch).
 *
 * Expected JSON shape (all fields optional):
 * ```json
 * {
 *   "name": "My Asset",
 *   "symbol": "MYA",
 *   "description": "A demo DigiAsset",
 *   "decimals": 2,
 *   "totalSupply": 1000000,
 *   "issuerAddress": "dgb1q…",
 *   "image": "https://…"
 * }
 * ```
 */
class AssetMetadataService(
    private val ipfsClient: IpfsClient,
    private val assetMetadataDao: AssetMetadataDao,
    /** Optional fallback network client that queries digiasset_core instances
     *  (digiscope.me proxy, api.digiassets.net) when the IPFS fetch fails or
     *  no CID is available. Nullable so tests can construct without one. */
    private val assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient? = null,
) {
    /**
     * In-memory negative cache — CIDs for which every resolver failed
     * recently. Asset detection sweeps run every 30s; without this,
     * each sweep kicks off an IPFS fetch for every unresolved asset,
     * which spirals into a retry storm against every gateway when
     * they're all down. An entry lives [NEGATIVE_CACHE_TTL_MS] before
     * we try again. Cleared on process restart (intentional — gives
     * IPFS a chance to recover without the user having to do anything).
     */
    private val recentlyFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal companion object {
        /** 10 minutes. Long enough to stop hammering broken gateways;
         *  short enough that a proxy recovery is picked up naturally
         *  on the next sweep after the interval passes. */
        const val NEGATIVE_CACHE_TTL_MS = 10L * 60_000L

        /** Display-text caps. Generous enough for any legit asset's
         *  name / symbol / description; tight enough that a 10MB
         *  attacker-controlled string can't bloat the local DB. */
        const val MAX_SHORT_TEXT_LEN = 200
        const val MAX_LONG_TEXT_LEN = 2000
        const val MAX_IMAGE_URL_LEN = 2048

        /**
         * Strip control chars + RTL/BiDi overrides + cap length.
         *
         * Why:
         *  - U+202E LEFT-TO-RIGHT OVERRIDE (and friends U+202A..U+202E,
         *    U+2066..U+2069) is the classic homoglyph attack vector — they
         *    flip text direction so "MyEvil-\u202Egnp.token" renders as
         *    "MyEvil-token.png" in the UI. Strip them.
         *  - Control chars (C0 + DEL + C1) have no place in a display
         *    name and confuse logging / clipboard / TTY logs.
         *  - Length cap prevents DB bloat from intentional megabyte
         *    names — issuer-controlled IPFS metadata is fully attacker-
         *    controlled in the worst case.
         *
         * Internal so the security test suite can verify the predicate
         * directly without standing up Room + IPFS infrastructure.
         */
        internal fun sanitize(input: String?, maxLen: Int, allowNewlines: Boolean): String? {
            if (input.isNullOrBlank()) return null
            val cleaned = input.filter { ch -> isDisplaySafe(ch) || (allowNewlines && ch == '\n') }
            if (cleaned.isBlank()) return null
            return cleaned.take(maxLen)
        }

        internal fun isDisplaySafe(ch: Char): Boolean {
            val code = ch.code
            // C0 control range
            if (code < 0x20) return false
            // DEL + C1 control range
            if (code in 0x7F..0x9F) return false
            // BiDi overrides + isolates that flip text direction
            if (code in 0x202A..0x202E) return false  // LRE/RLE/PDF/LRO/RLO
            if (code in 0x2066..0x2069) return false  // LRI/RLI/FSI/PDI
            return true
        }
    }
    /**
     * Return metadata for [assetId], fetching from IPFS via [metadataCid] if not cached.
     *
     * Resolution order:
     *  1. Room cache (immutable — CID content-addressed).
     *  2. IPFS via [metadataCid] if supplied, verified against its multihash.
     *  3. [assetNetworkClient] — digiasset_core instances that already have
     *     the metadata pinned and parsed server-side. Survives the case where
     *     public IPFS gateways are slow/down AND the CID wasn't cached locally.
     *
     * @param assetId     The DigiAsset identifier.
     * @param metadataCid Optional IPFS CID of the JSON metadata document.
     * @return [AssetMetadata] on success, `null` if every resolver failed.
     */
    suspend fun getMetadata(assetId: String, metadataCid: String?): AssetMetadata? {
        // Cache-first for real (content-addressed) metadata — CIDs are
        // immutable, so a cached entry with a real name is always valid.
        // Bare placeholder rows (name==null) are treated as "not yet
        // fetched"; returning them here would short-circuit the IPFS pull
        // and leave the user staring at an anonymous colored-letter icon
        // forever. Refetch when we have a CID to actually fetch from.
        val cached = assetMetadataDao.getMetadata(assetId)
        if (cached != null && cached.name != null) return cached.toModel()
        if (cached != null && metadataCid == null) return cached.toModel()

        // Negative-cache short-circuit: if we tried this CID recently and every
        // gateway failed, don't hammer them again for NEGATIVE_CACHE_TTL_MS.
        val now = System.currentTimeMillis()
        val negativeKey = metadataCid ?: assetId
        val lastFail = recentlyFailed[negativeKey]
        if (lastFail != null && (now - lastFail) < NEGATIVE_CACHE_TTL_MS) {
            return null
        }

        return withContext(Dispatchers.IO) {
            // Pass 1: IPFS via CID (if we have one)
            if (metadataCid != null) {
                val bytes = ipfsClient.fetchVerified(metadataCid)
                if (bytes != null) {
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (json != null) {
                        recentlyFailed.remove(negativeKey)
                        return@withContext storeFromJson(assetId, metadataCid, json)
                    }
                }
            }

            // Pass 2: provider fallback, for assets whose issuance carried no metadata hash.
            val netClient = assetNetworkClient
            if (netClient != null) {
                val remote = runCatching { netClient.getAssetData(assetId) }.getOrNull()

                // 2a: some providers inline the metadata document.
                val ipfsMap = remote?.ipfs
                if (remote != null && ipfsMap != null) {
                    recentlyFailed.remove(negativeKey)
                    val json = JSONObject(ipfsMap)
                    return@withContext storeFromJson(assetId, metadataCid ?: remote.cid, json)
                }

                // 2b: most return a CID INSTEAD of the document — which is the thing we were
                // missing, so fetch it. Without this the fallback would retrieve a perfectly
                // good response, find no inline blob, and discard the very CID it just learned;
                // the asset stayed a bare id with supply and divisibility but no name.
                val remoteCid = remote?.cid
                if (remoteCid != null) {
                    val bytes = ipfsClient.fetchVerified(remoteCid)
                    val json = bytes?.let {
                        runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull()
                    }
                    if (json != null) {
                        recentlyFailed.remove(negativeKey)
                        return@withContext storeFromJson(assetId, remoteCid, json)
                    }
                }
            }

            // Everything failed — mark for suppression until TTL expires.
            recentlyFailed[negativeKey] = System.currentTimeMillis()
            null
        }
    }

    private suspend fun storeFromJson(assetId: String, cid: String?, jsonRaw: JSONObject): AssetMetadata {
        // Canonical DigiAsset metadata wraps everything in {"data": {...}}.
        // Some older tools produce the same fields at top-level. Unwrap if
        // present so downstream code only sees the asset-level shape.
        val json = jsonRaw.optJSONObject("data") ?: jsonRaw

        // Name field — canonical schema uses "assetName", older tools use "name".
        val name = sanitizeShortText(
            json.optString("assetName").takeIf { it.isNotEmpty() }
                ?: json.optString("name").takeIf { it.isNotEmpty() }
        )

        // Issuer — canonical schema has "issuer" as a string (the issuer's
        // name). Older tools used "issuerAddress" flat or "issuer.address"
        // nested. Store whichever we find; display code handles it.
        val issuer = sanitizeShortText(
            json.optString("issuerAddress").takeIf { it.isNotEmpty() }
                ?: json.optJSONObject("issuer")?.optString("address")?.takeIf { it.isNotEmpty() }
                ?: json.optString("issuer").takeIf { it.isNotEmpty() }
        )

        // Image may live under several keys depending on the issuer tool:
        //   "image" — canonical, what our AssetImageResolver expects
        //   "imageUrl" / "image_url" — common web-tool variants
        //   "icon" — Neblio-style
        //   "urls"[…]{"name":"icon",…} — DA3 urls array (take first icon entry)
        val imageUrl = json.optString("image").takeIf { it.isNotEmpty() }
            ?: json.optString("imageUrl").takeIf { it.isNotEmpty() }
            ?: json.optString("image_url").takeIf { it.isNotEmpty() }
            ?: json.optString("icon").takeIf { it.isNotEmpty() }
            ?: run {
                val urls = json.optJSONArray("urls") ?: return@run null
                var out: String? = null
                for (i in 0 until urls.length()) {
                    val u = urls.optJSONObject(i) ?: continue
                    val name = u.optString("name").lowercase()
                    if (name == "icon" || name == "image" || name.endsWith(".png") ||
                        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") ||
                        name.endsWith(".svg") || name.endsWith(".webp")) {
                        out = u.optString("url").takeIf { it.isNotEmpty() }
                        if (out != null) break
                    }
                }
                out
            }

        val entity = AssetMetadataEntity(
            assetId = assetId,
            name = name,
            symbol = sanitizeShortText(
                json.optString("symbol").takeIf { it.isNotEmpty() }
                    ?: json.optString("ticker").takeIf { it.isNotEmpty() }
            ),
            description = sanitizeLongText(
                json.optString("description").takeIf { it.isNotEmpty() }
            ),
            // Protocol caps divisibility at 0..7. Anything else is either
            // a buggy issuer or an attack — clamp to 0 rather than letting
            // negative or huge values flow into pow(10, decimals) downstream
            // (CostPreviewCard does this and would produce Infinity).
            decimals = json.optInt("decimals", 0).coerceIn(0, 7),
            // Negative totalSupply is meaningless and would break UI math.
            totalSupply = json.optLong("totalSupply", 0L).coerceAtLeast(0L),
            issuerAddress = issuer,
            metadataCid = cid,
            // imageUrl is bounds-checked at render time by AssetImageResolver
            // (refuses non-http(s)/ipfs schemes) but cap length here too so
            // a 10MB string doesn't end up in the row.
            imageUrl = imageUrl?.takeIf { it.length <= MAX_IMAGE_URL_LEN },
            cachedAt = System.currentTimeMillis(),
        )
        assetMetadataDao.insert(entity)
        return entity.toModel()
    }

    private fun sanitizeShortText(input: String?): String? = sanitize(input, MAX_SHORT_TEXT_LEN, allowNewlines = false)
    private fun sanitizeLongText(input: String?): String? = sanitize(input, MAX_LONG_TEXT_LEN, allowNewlines = true)

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private fun AssetMetadataEntity.toModel() = AssetMetadata(
        assetId = assetId,
        name = name,
        symbol = symbol,
        description = description,
        decimals = decimals,
        totalSupply = totalSupply,
        issuerAddress = issuerAddress,
        imageUrl = imageUrl
    )
}
