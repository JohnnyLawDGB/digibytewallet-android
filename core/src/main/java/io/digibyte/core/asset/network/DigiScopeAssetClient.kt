package io.digibyte.core.asset.network

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
        // Belt-and-suspenders: digiasset_core's listunspent + getrawtransaction
        // RPCs can run 20-40s under load even with the watchdog in place.
        // Per backend dev's note 2026-04-25.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Shared pin set — see io.digibyte.core.network.DigiScopePins. A stale
        // pin here previously killed the M3 asset parent-walk → "metadata offline".
        .certificatePinner(io.digibyte.core.network.DigiScopePins.certificatePinner())
        .build()

    override suspend fun getAssetData(assetId: String): AssetDataResponse? {
        val json = getJson("$baseUrl/digiassets/asset/$assetId") as? JSONObject ?: return null
        val parsed = DigiScopeAssetParsing.assetData(json, fallbackAssetId = assetId)
            ?: return null
        // The ipfs blob, when the backend inlines one, is richer than the parser's remit.
        return parsed.copy(ipfs = json.optJSONObject("ipfs")?.let { ipfsJsonToMap(it) })
    }

    override suspend fun getAddressHoldings(address: String): Map<String, Long>? {
        val json = getJson("$baseUrl/digiassets/address/$address") as? JSONObject ?: return null
        return DigiScopeAssetParsing.holdings(json)
    }

    /**
     * NO BACKEND ROUTE EXISTS for this yet (probed 2026-08-23: 404 under every prefix tried),
     * and nothing in the wallet calls it — so this is a shape waiting for a server, not a
     * working call. The path below is a guess at the prefix its siblings use; verify it against
     * a live route before relying on it.
     */
    override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? {
        val url = buildString {
            append("$baseUrl/digiassets/history/$address")
            if (limit != null) append("?limit=$limit")
        }
        val json = getJson(url) ?: return null
        val arr = json as? JSONArray ?: return null
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    /**
     * NO BACKEND ROUTE EXISTS for this yet (probed 2026-08-23: 404), and nothing in the wallet
     * calls it. Left in place because the rotation's health story will want it, but do not read
     * a null here as "the node is behind" — it means the route is absent.
     */
    override suspend fun getSyncState(): SyncStateResponse? {
        val json = getJson("$baseUrl/syncstate") as? JSONObject ?: return null
        if (json.has("error")) return null
        return SyncStateResponse(
            count = json.optLong("count", 0L),
            sync = json.optLong("sync", -9_999L),
        )
    }

    override suspend fun getRawTransaction(txHashHex: String): ByteArray? {
        // Backend exposes `GET /tx/raw/:txid` which proxies `getrawtransaction`
        // on the underlying digibyted RPC. Returns either hex in a JSON field
        // or a 404 if the txid isn't known. We accept both wrappers defensively
        // because the server's been reshuffled a couple of times.
        val json = getJson("$baseUrl/tx/raw/$txHashHex") as? JSONObject ?: return null
        if (json.has("error")) return null
        val hex = json.optString("hex").takeIf { it.isNotEmpty() }
            ?: json.optString("rawtx").takeIf { it.isNotEmpty() }
            ?: return null
        return runCatching { hex.hexToByteArray() }.getOrNull()
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "odd hex length" }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun postJson(url: String, bodyJson: String): Any? = try {
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MT))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w(
                    "DigiScopeAssetClient",
                    "POST $url returned HTTP ${resp.code} ${resp.message}"
                )
                return null
            }
            val body = resp.body?.string()
            if (body == null) {
                android.util.Log.w("DigiScopeAssetClient", "POST $url empty body")
                return null
            }
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    } catch (t: Throwable) {
        android.util.Log.w(
            "DigiScopeAssetClient",
            "POST $url threw ${t::class.java.simpleName}: ${t.message}",
            t
        )
        null
    }


    private fun getJson(url: String): Any? = try {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w(
                    "DigiScopeAssetClient",
                    "GET $url returned HTTP ${resp.code} ${resp.message}"
                )
                return null
            }
            val body = resp.body?.string()
            if (body == null) {
                android.util.Log.w("DigiScopeAssetClient", "GET $url empty body")
                return null
            }
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    } catch (t: Throwable) {
        android.util.Log.w(
            "DigiScopeAssetClient",
            "GET $url threw ${t::class.java.simpleName}: ${t.message}",
            t
        )
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
