package io.digibyte.core.asset.network

/**
 * Small slice of the DigiAsset RPC surface that the wallet actually needs —
 * each concrete [AssetNetworkClient] (digiscope proxy, api.digiassets.net,
 * user-configured node) implements this shape on top of whatever REST/RPC
 * dialect its upstream speaks.
 *
 * Methods return null on failure so [MultiEndpointAssetClient] can cleanly
 * fall through to the next endpoint in its rotation without exceptions
 * propagating into UI code.
 */
interface AssetNetworkClient {
    /** Human-readable endpoint identifier, used for logging + UI transparency. */
    val endpointLabel: String

    /** Return canonical asset metadata (name, symbol, supply, CID, rules).
     *  @param assetId the DigiAsset identifier, e.g. `La2ih1bm2u4d…`. */
    suspend fun getAssetData(assetId: String): AssetDataResponse?

    /** Return `{assetId: heldQuantity}` for a single address — this is the
     *  sovereign answer to "what assets does this address hold right now",
     *  which SPV bloom filters can't deliver reliably. */
    suspend fun getAddressHoldings(address: String): Map<String, Long>?

    /** Return ordered txids (most recent first) that touched [address] for
     *  asset operations. Used for per-address DigiAsset history recovery. */
    suspend fun getAddressHistory(
        address: String,
        limit: Int? = null,
    ): List<String>?

    /** Health snapshot of the remote node. `sync < -120` means it's too far
     *  behind to answer reliably; callers should treat that as "unavailable"
     *  and rotate. */
    suspend fun getSyncState(): SyncStateResponse?
}

/** Minimal projection of the `getassetdata` response — richer fields can be
 *  added lazily as the UI exposes them. */
data class AssetDataResponse(
    val assetId: String,
    val cid: String?,
    val issuer: String?,
    val count: Long,
    val decimals: Int,
    /** Raw IPFS-resolved metadata JSON object. Null when `excludeIPFS=true`
     *  or the CID isn't pinned. Kept as a nullable map rather than parsed
     *  eagerly so new fields surface automatically. */
    val ipfs: Map<String, Any?>?,
)

data class SyncStateResponse(
    /** Local node's current block height. */
    val count: Long,
    /** Blocks-behind; 0 == at tip, negative == behind. */
    val sync: Long,
)
