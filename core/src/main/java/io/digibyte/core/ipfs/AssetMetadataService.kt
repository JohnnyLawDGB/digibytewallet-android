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
    private val assetMetadataDao: AssetMetadataDao
) {
    /**
     * Return metadata for [assetId], fetching from IPFS via [metadataCid] if not cached.
     *
     * @param assetId     The DigiAsset identifier.
     * @param metadataCid Optional IPFS CID of the JSON metadata document.
     * @return [AssetMetadata] on success, `null` if no CID is provided or all gateways fail.
     */
    suspend fun getMetadata(assetId: String, metadataCid: String?): AssetMetadata? {
        // Always check the local cache first — CIDs are immutable, cache is permanent
        val cached = assetMetadataDao.getMetadata(assetId)
        if (cached != null) return cached.toModel()

        // Nothing to fetch without a CID
        if (metadataCid == null) return null

        return withContext(Dispatchers.IO) {
            // Fetch from IPFS and verify hash
            val bytes = ipfsClient.fetchVerified(metadataCid) ?: return@withContext null

            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return@withContext null  // not valid JSON
            }

            val entity = AssetMetadataEntity(
                assetId = assetId,
                name = json.optString("name").takeIf { it.isNotEmpty() },
                symbol = json.optString("symbol").takeIf { it.isNotEmpty() },
                description = json.optString("description").takeIf { it.isNotEmpty() },
                decimals = json.optInt("decimals", 0),
                totalSupply = json.optLong("totalSupply", 0L),
                issuerAddress = json.optString("issuerAddress").takeIf { it.isNotEmpty() },
                metadataCid = metadataCid,
                imageUrl = json.optString("image").takeIf { it.isNotEmpty() },
                cachedAt = System.currentTimeMillis()
            )

            assetMetadataDao.insert(entity)
            entity.toModel()
        }
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
