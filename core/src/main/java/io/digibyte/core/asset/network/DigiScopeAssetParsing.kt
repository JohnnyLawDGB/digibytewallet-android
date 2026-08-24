package io.digibyte.core.asset.network

import org.json.JSONObject

/**
 * Response parsing for the digiscope DigiAsset routes, kept apart from the HTTP client so the
 * shapes can be pinned against real captured payloads instead of only being exercised by a live
 * server. See DigiScopeAssetParsingTest for those payloads.
 *
 * An `error` key means no data, whatever the status code — the backend returns it with 404 for a
 * missing route and with 500 for its own RPC failures, and both must read the same way. Parsing
 * an error body positionally would produce an asset with a default in every field, which is how
 * a phantom row gets written.
 */
internal object DigiScopeAssetParsing {



    /** Asset data to [AssetDataResponse]; null when the body carries an error instead. */
    fun assetData(json: JSONObject, fallbackAssetId: String): AssetDataResponse? {
        if (json.has("error")) return null
        return AssetDataResponse(
            assetId = json.optString("assetId", fallbackAssetId),
            cid = json.optString("cid").takeIf { it.isNotEmpty() },
            issuer = json.optString("issuer").takeIf { it.isNotEmpty() },
            count = json.optLong("count", 0L),
            decimals = json.optInt("decimals", 0),
            ipfs = null,
        )
    }
}
