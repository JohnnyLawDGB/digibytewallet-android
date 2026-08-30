package io.digibyte.core.asset.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DigiAsset data from `assets.digistamp.co`.
 *
 * WHY IT WAS ADDED. An asset whose issuance carries no metadata hash can only get a name from
 * [getAssetData], and on device that fallback had nowhere to go: digiscope answers its asset
 * route with `500 getassetdata error: Invalid params`, and this provider — which answers
 * correctly — was not in the rotation. The symptom was an asset rendering as a bare
 * `La4WAqZf…` with supply and divisibility (read from the on-chain header) but no name.
 *
 * Shapes are the ones the wallet already parses; digistamp implemented them to match
 * deliberately, so this is a thin client rather than a new dialect.
 *
 * Pinned to [io.digibyte.core.network.DigistampPins] — a third-party host, so a chain change can
 * arrive without warning; the rotation degrades to the next provider rather than failing hard.
 */
class DigistampAssetClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AssetNetworkClient {

    override val endpointLabel: String = "assets.digistamp.co"

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .certificatePinner(io.digibyte.core.network.DigistampPins.certificatePinner())
        .build()

    override suspend fun getAssetData(assetId: String): AssetDataResponse? =
        withContext(Dispatchers.IO) {
            val json = getJson("$baseUrl/assets/$assetId") ?: return@withContext null
            DigiScopeAssetParsing.assetData(json, fallbackAssetId = assetId)
        }

    /**
     * Thin passthrough of `getrawtransaction`, which the DigiAsset parent-walk needs at every
     * hop. Having a SECOND host for this is the point: one host serving every asset name and
     * image in the wallet is one host too few.
     */
    override suspend fun getRawTransaction(txHashHex: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val json = getJson("$baseUrl/tx/raw/$txHashHex") ?: return@withContext null
            val hex = json.optString("hex").takeIf { it.isNotEmpty() } ?: return@withContext null
            runCatching { hex.hexToByteArray() }.getOrNull()
        }

    override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$baseUrl/assets/history/$address")
                if (limit != null) append("?limit=$limit")
            }
            val body = getBody(url) ?: return@withContext null
            runCatching {
                val arr = org.json.JSONArray(body)
                List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
            }.getOrNull()
        }

    override suspend fun getSyncState(): SyncStateResponse? = withContext(Dispatchers.IO) {
        val json = getJson("$baseUrl/syncstate") ?: return@withContext null
        // NOTE the sign convention, confirmed by digistamp: 0 at the tip, NEGATIVE for blocks
        // behind. Small POSITIVES are states, not distances (1 stopped, 2 initializing,
        // 3 rewinding, 4 optimizing) — do not read this as "blocks behind" without that in mind.
        SyncStateResponse(
            count = json.optLong("count", 0L),
            sync = json.optLong("sync", -9_999L),
        )
    }

    private fun getJson(url: String): JSONObject? =
        getBody(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun getBody(url: String): String? = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Never swallow silently — a nameless asset with no log line is undebuggable,
                // which is exactly how the broken digiscope route went unnoticed.
                android.util.Log.w("DigistampAssetClient", "GET ${endpointOf(url)} → HTTP ${resp.code}")
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        android.util.Log.w("DigistampAssetClient", "GET ${endpointOf(url)} failed", e)
        null
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "odd hex length" }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://assets.digistamp.co/api"
    }
}
