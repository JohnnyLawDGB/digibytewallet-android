package io.digibyte.core.asset.network

import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DigiAsset network client backed by our own `digiscope.me` proxy, which
 * passes through to a locally-hosted `digiasset_core` instance. Cert-pinned
 * to the same certificates [io.digibyte.core.reconcile.DgbNodeClient] uses
 * for the reconcile endpoint.
 *
 * See `/opt/digiscope-backend/src/server.js` for the 6 proxied routes.
 */
class DigiScopeAssetClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AssetNetworkClient {

    override val endpointLabel: String = "digiscope.me"

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(
            CertificatePinner.Builder()
                .add("api.digiscope.me", "sha256/VDo86Ks/QFE3kVoOXkmNVWTovKKNMFQsBd4KGvoP8OU=")
                .add("api.digiscope.me", "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=")
                .build()
        )
        .build()

    override suspend fun getAssetData(assetId: String): AssetDataResponse? {
        val json = getJson("$baseUrl/assets/$assetId") as? JSONObject ?: return null
        if (json.has("error")) return null
        return AssetDataResponse(
            assetId = json.optString("assetId", assetId),
            cid = json.optString("cid").takeIf { it.isNotEmpty() },
            issuer = json.optString("issuer").takeIf { it.isNotEmpty() },
            count = json.optLong("count", 0L),
            decimals = json.optInt("decimals", 0),
            ipfs = json.optJSONObject("ipfs")?.let { ipfsJsonToMap(it) },
        )
    }

    override suspend fun getAddressHoldings(address: String): Map<String, Long>? {
        val json = getJson("$baseUrl/assets/holdings/$address") as? JSONObject ?: return null
        if (json.has("error")) return null
        val result = mutableMapOf<String, Long>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            result[k] = json.optLong(k, 0L)
        }
        return result
    }

    override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? {
        val url = buildString {
            append("$baseUrl/assets/history/$address")
            if (limit != null) append("?limit=$limit")
        }
        val json = getJson(url) ?: return null
        val arr = json as? JSONArray ?: return null
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    override suspend fun getSyncState(): SyncStateResponse? {
        val json = getJson("$baseUrl/syncstate") as? JSONObject ?: return null
        if (json.has("error")) return null
        return SyncStateResponse(
            count = json.optLong("count", 0L),
            sync = json.optLong("sync", -9_999L),
        )
    }

    override suspend fun getAssetUtxos(addresses: List<String>): List<AssetUtxoResponse>? {
        if (addresses.isEmpty()) return emptyList()
        val body = JSONObject().apply {
            put("addresses", JSONArray(addresses))
        }.toString()
        val json = postJson("$baseUrl/assets/unspent", body) as? JSONObject ?: return null
        if (json.has("error")) return null
        val utxosArr = json.optJSONArray("utxos") ?: return emptyList()
        val out = mutableListOf<AssetUtxoResponse>()
        for (i in 0 until utxosArr.length()) {
            val u = utxosArr.optJSONObject(i) ?: continue
            val assetsArr = u.optJSONArray("assets") ?: continue
            val assets = mutableListOf<AssetUtxoResponse.AssetCarried>()
            for (j in 0 until assetsArr.length()) {
                val a = assetsArr.optJSONObject(j) ?: continue
                assets += AssetUtxoResponse.AssetCarried(
                    assetId = a.optString("assetId"),
                    assetIndex = a.optLong("assetIndex", 0L),
                    count = a.optLong("count", 0L),
                    decimals = a.optInt("decimals", 0),
                    issuerAddress = a.optJSONObject("issuer")?.optString("address")?.takeIf { it.isNotEmpty() },
                    metadataCid = a.optString("cid").takeIf { it.isNotEmpty() },
                )
            }
            if (assets.isEmpty()) continue
            out += AssetUtxoResponse(
                address = u.optString("address"),
                txid = u.optString("txid"),
                vout = u.optInt("vout", 0),
                satoshis = u.optLong("digibyte", 0L),
                confirmedHeight = assets.firstOrNull()?.let { a ->
                    u.optJSONArray("assets")?.optJSONObject(0)?.optLong("height", 0L) ?: 0L
                } ?: 0L,
                assets = assets,
            )
        }
        return out
    }

    private fun postJson(url: String, bodyJson: String): Any? = try {
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MT))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    } catch (_: Throwable) {
        null
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

    private fun ipfsJsonToMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.opt(k)
        }
        return out
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.digiscope.me/api"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
