package io.digibyte.core.asset.network

import io.digibyte.core.asset.send.StackEntry
import io.digibyte.core.asset.send.StackLookup
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
            rules = json.optJSONObject("rules")?.let { toMap(it) },
        )
    }

    /**
     * The body of `GET /digiassets/txout/:txid/:vout` to a [StackLookup]. The reply must name the
     * output that was asked for, and every entry must carry an asset id and a whole, non-negative
     * count; anything else is [StackLookup.Unavailable] (no answer), never an empty stack, which
     * would read as "no assets here".
     */
    fun txOutStack(json: JSONObject, txid: String, vout: Int): StackLookup {
        if (json.has("error")) return StackLookup.Unavailable
        if (!json.optString("txid").equals(txid, ignoreCase = true)) return StackLookup.Unavailable
        if (!json.has("vout") || json.optInt("vout", -1) != vout) return StackLookup.Unavailable
        val arr = json.optJSONArray("assets") ?: return StackLookup.Unavailable
        val entries = ArrayList<StackEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: return StackLookup.Unavailable
            val id = a.optString("assetId").takeIf { it.isNotEmpty() && it != "null" }
                ?: return StackLookup.Unavailable
            val raw = a.opt("count")
            val count = when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> return StackLookup.Unavailable
            }
            if (count < 0L) return StackLookup.Unavailable
            entries += StackEntry(id, count)
        }
        return StackLookup.Found(entries)
    }

    private fun toMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.opt(k)
        }
        return out
    }
}
