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

    // getAddressHoldings was REMOVED, deliberately, rather than left unused.
    //
    // It sent one of the wallet's addresses to a third party to ask what it holds — exactly
    // what compact-filters-only sync exists to withhold. It had zero callers, because holdings
    // are computed natively from the CF scan and always have been. An unused method that leaks
    // is one call site away from leaking, and "we don't call it" is a weaker guarantee than
    // "it doesn't exist".
    //
    // A marketplace wanting to show "your holdings" must render the WALLET's own view beside
    // the catalogue, never ask a server what the user owns.

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

    /** Fetch the raw serialized bytes of a transaction by its display-order
     *  txid hex. Used by M3 parent-walk resolution — given a DigiAsset
     *  transfer's input txid, we need the parent transaction's OP_RETURN
     *  and inputs to continue walking toward the issuance.
     *
     *  Note this is equivalent to a full node's `getrawtransaction <txid>`
     *  call; a thin server endpoint can simply forward the JSON-RPC. Full
     *  SPV `getdata` is a future implementation of the same primitive.
     *
     *  Returns raw tx bytes on success, null if the endpoint doesn't know
     *  this txid or the request failed. */
    suspend fun getRawTransaction(txHashHex: String): ByteArray?
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
