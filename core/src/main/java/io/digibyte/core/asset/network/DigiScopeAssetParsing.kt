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

    /**
     * Address holdings to `{assetId: quantity}`.
     *
     * Quantities only. The response also carries `name`, `decimals` and `mediaUrl`, and the
     * wallet deliberately ignores them: names and artwork come from IPFS content verified
     * against its CID, so a compromised backend cannot rename someone's assets.
     *
     * An address holding nothing yields an empty map rather than null — empty is an answer, and
     * null would count as an endpoint failure toward the rotation's circuit breaker.
     */
    fun holdings(json: JSONObject): Map<String, Long>? {
        if (json.has("error")) return null
        val assets = json.optJSONArray("assets") ?: return null

        val out = mutableMapOf<String, Long>()
        for (i in 0 until assets.length()) {
            val row = assets.optJSONObject(i) ?: continue
            val id = row.optString("assetId").takeIf { it.isNotEmpty() } ?: continue
            out[id] = (out[id] ?: 0L) + row.optLong("quantity", 0L)
        }
        return out
    }

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
