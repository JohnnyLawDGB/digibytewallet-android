package io.digibyte.core.asset.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DigiAsset network client backed by `api.digiassets.net/v3`, the official
 * endpoint maintained by the DigiByte Core team (mctrivia/digiassetX).
 *
 * This is our second trust anchor in the sovereignty stack — used as a
 * fallback when our own digiscope.me proxy is unreachable.
 *
 * No cert pinning: this isn't our infra, and pinning would strand users if
 * upstream rotates certs. Standard TLS with CA validation is correct.
 */
class DigiAssetsNetClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AssetNetworkClient {

    override val endpointLabel: String = "digiassets.net"

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun getAssetData(assetId: String): AssetDataResponse? {
        // v3 endpoint: /v3/assetdata/<assetId>
        val json = getJson("$baseUrl/assetdata/$assetId") as? JSONObject ?: return null
        if (json.has("error")) return null
        // api.digiassets.net uses a slightly different field shape; map to
        // our common AssetDataResponse model. Unknown fields degrade gracefully.
        return AssetDataResponse(
            assetId = json.optString("assetId", assetId),
            cid = json.optString("cid").takeIf { it.isNotEmpty() }
                ?: json.optString("ipfsHash").takeIf { it.isNotEmpty() },
            issuer = json.optString("issuer").takeIf { it.isNotEmpty() }
                ?: json.optString("issuerAddress").takeIf { it.isNotEmpty() },
            count = json.optLong("totalSupply", json.optLong("count", 0L)),
            decimals = json.optInt("decimals", 0),
            ipfs = json.optJSONObject("metadata")?.let { jsonObjectToMap(it) }
                ?: json.optJSONObject("ipfs")?.let { jsonObjectToMap(it) },
        )
    }

    override suspend fun getAddressHoldings(address: String): Map<String, Long>? {
        val json = getJson("$baseUrl/addressinfo/$address") as? JSONObject ?: return null
        val assetsNode = json.optJSONObject("assets") ?: return emptyMap()
        val out = mutableMapOf<String, Long>()
        val keys = assetsNode.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = assetsNode.optLong(k, 0L)
        }
        return out
    }

    override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? {
        val json = getJson("$baseUrl/addresshistory/$address") ?: return null
        val arr = when (json) {
            is JSONArray -> json
            is JSONObject -> json.optJSONArray("history") ?: return null
            else -> return null
        }
        val out = mutableListOf<String>()
        val n = if (limit != null) minOf(limit, arr.length()) else arr.length()
        for (i in 0 until n) {
            val item = arr.opt(i)
            val txid = when (item) {
                is String -> item
                is JSONObject -> item.optString("txid").takeIf { it.isNotEmpty() }
                else -> null
            }
            if (txid != null) out.add(txid)
        }
        return out
    }

    override suspend fun getRawTransaction(txHashHex: String): ByteArray? {
        // api.digiassets.net doesn't expose a raw-tx endpoint either; same
        // fall-through semantics — return null so MultiEndpointAssetClient
        // tries the next endpoint.
        return null
    }

    override suspend fun getSyncState(): SyncStateResponse? {
        // digiassets.net exposes /status with a slightly different shape; if
        // the call or field names shift, we treat this client as unavailable
        // for health-check purposes and let the rotation fall through.
        val json = getJson("$baseUrl/status") as? JSONObject ?: return null
        return SyncStateResponse(
            count = json.optLong("height", json.optLong("count", 0L)),
            sync = json.optLong("sync", 0L),
        )
    }

    private fun getJson(url: String): Any? = try {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    } catch (_: Throwable) {
        null
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.opt(k)
        }
        return out
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.digiassets.net/v3"
    }
}
