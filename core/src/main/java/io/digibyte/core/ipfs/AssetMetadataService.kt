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
        // Always check the local cache first — CIDs are immutable, cache is permanent
        val cached = assetMetadataDao.getMetadata(assetId)
        if (cached != null) return cached.toModel()

        return withContext(Dispatchers.IO) {
            // Pass 1: IPFS via CID (if we have one)
            if (metadataCid != null) {
                val bytes = ipfsClient.fetchVerified(metadataCid)
                if (bytes != null) {
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (json != null) {
                        return@withContext storeFromJson(assetId, metadataCid, json)
                    }
                }
            }

            // Pass 2: digiasset_core network fallback
            val netClient = assetNetworkClient ?: return@withContext null
            val remote = runCatching { netClient.getAssetData(assetId) }.getOrNull() ?: return@withContext null
            val ipfsMap = remote.ipfs ?: return@withContext null
            val json = JSONObject(ipfsMap)
            storeFromJson(assetId, metadataCid ?: remote.cid, json)
        }
    }

    private suspend fun storeFromJson(assetId: String, cid: String?, json: JSONObject): AssetMetadata {
        // Accept both flat `issuerAddress` and nested `issuer.address` shapes.
        // digiassetX-style metadata nests; older DigiAsset tools used the flat form.
        val issuer = json.optString("issuerAddress").takeIf { it.isNotEmpty() }
            ?: json.optJSONObject("issuer")?.optString("address")?.takeIf { it.isNotEmpty() }

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
            name = json.optString("name").takeIf { it.isNotEmpty() },
            symbol = json.optString("symbol").takeIf { it.isNotEmpty() }
                ?: json.optString("ticker").takeIf { it.isNotEmpty() },
            description = json.optString("description").takeIf { it.isNotEmpty() },
            decimals = json.optInt("decimals", 0),
            totalSupply = json.optLong("totalSupply", 0L),
            issuerAddress = issuer,
            metadataCid = cid,
            imageUrl = imageUrl,
            cachedAt = System.currentTimeMillis(),
        )
        assetMetadataDao.insert(entity)
        return entity.toModel()
    }

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
