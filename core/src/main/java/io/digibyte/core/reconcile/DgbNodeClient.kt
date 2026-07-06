package io.digibyte.core.reconcile

import android.content.Context
import io.digibyte.core.networkSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC-over-HTTPS client for querying authoritative chain state from
 * a DigiByte full node. Used by ChainReconciliationService when the SPV
 * bloom scan misses UTXOs on known wallet addresses.
 *
 * Default endpoint: api.digiscope.me (cert-pinned). Users can override
 * via Settings to point at their own node (sovereignty path).
 *
 * The endpoint wraps two DGB RPC calls server-side:
 *  - scantxoutset / getaddressesutxos — returns UTXOs for an address list
 *  - getrawtransaction — returns raw tx hex for each UTXO's parent tx
 *
 * We request both in a single round-trip to minimize client→server latency.
 */
class DgbNodeClient(
    private val context: Context,
    private val baseClient: OkHttpClient = OkHttpClient(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.digiscope.me/api"
        private const val PREFS_NAME = "dgb_reconcile"
        private const val KEY_CUSTOM_ENDPOINT = "custom_rpc_endpoint"
        private const val MAX_ADDRS_PER_REQUEST = 500 // server-side cap
        private const val JSON = "application/json; charset=utf-8"
    }

    /** OkHttp client with cert pinning for the default digiscope.me endpoint.
     *  Cert-pinning is skipped for custom endpoints (user's own node). */
    private val pinnedClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // scantxoutset takes 20–60s on the server
        .certificatePinner(
            CertificatePinner.Builder()
                .add("api.digiscope.me", "sha256/VDo86Ks/QFE3kVoOXkmNVWTovKKNMFQsBd4KGvoP8OU=")
                .add("api.digiscope.me", "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=")
                .build()
        )
        .build()

    private val unpinnedClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME + networkSuffix(context), Context.MODE_PRIVATE)

    /** The active endpoint. Defaults to digiscope.me; can be user-overridden. */
    fun endpoint(): String =
        prefs.getString(KEY_CUSTOM_ENDPOINT, null)?.trimEnd('/') ?: DEFAULT_BASE_URL

    fun setCustomEndpoint(url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove(KEY_CUSTOM_ENDPOINT)
            else putString(KEY_CUSTOM_ENDPOINT, url.trim())
            apply()
        }
    }

    /** Select the right OkHttp client for the current endpoint — we only
     *  cert-pin the default (digiscope.me). A user running their own node
     *  gets standard TLS (their choice of CA). */
    private fun client(url: String): OkHttpClient =
        if (url.startsWith(DEFAULT_BASE_URL)) pinnedClient else unpinnedClient

    /**
     * Ask the node to enumerate UTXOs on this address list AND return raw
     * parent-tx hex for each UTXO in one round-trip.
     *
     * Returns null on any failure (network, auth, parse, empty).
     *
     * Batches addresses at 500/request to stay under server-side limits.
     */
    suspend fun reconcileAddresses(addresses: List<String>): ReconcileResult? =
        withContext(Dispatchers.IO) {
            if (addresses.isEmpty()) return@withContext ReconcileResult(emptyList(), emptyMap(), 0)

            val allUtxos = mutableListOf<UtxoEntry>()
            val allRawTxs = mutableMapOf<String, RawTxEntry>()
            var chainHeight = 0L

            for (batch in addresses.chunked(MAX_ADDRS_PER_REQUEST)) {
                val partial = requestBatch(batch) ?: return@withContext null
                allUtxos += partial.utxos
                allRawTxs.putAll(partial.rawTxs)
                if (partial.chainHeight > chainHeight) chainHeight = partial.chainHeight
            }
            ReconcileResult(allUtxos, allRawTxs, chainHeight)
        }

    private fun requestBatch(addresses: List<String>): ReconcileResult? {
        val url = "${endpoint()}/wallet/reconcile"
        val payload = JSONObject().apply {
            put("addresses", JSONArray(addresses))
        }
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()

        return try {
            client(url).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w(
                        "DgbNodeClient",
                        "reconcile $url returned HTTP ${resp.code} ${resp.message}"
                    )
                    return null
                }
                val body = resp.body?.string()
                if (body == null) {
                    android.util.Log.w("DgbNodeClient", "reconcile $url empty body")
                    return null
                }
                parseReconcileResponse(body)
            }
        } catch (t: Throwable) {
            android.util.Log.w(
                "DgbNodeClient",
                "reconcile $url threw ${t::class.java.simpleName}: ${t.message}",
                t
            )
            null
        }
    }

    private fun parseReconcileResponse(body: String): ReconcileResult? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val utxosJson = root.optJSONArray("utxos") ?: return null
        val rawTxsJson = root.optJSONObject("rawTxs") ?: JSONObject()
        val height = root.optLong("height", 0L)

        val utxos = mutableListOf<UtxoEntry>()
        for (i in 0 until utxosJson.length()) {
            val u = utxosJson.getJSONObject(i)
            utxos += UtxoEntry(
                txid = u.getString("txid"),
                vout = u.getInt("vout"),
                amountSatoshi = u.getLong("amountSatoshi"),
                address = u.getString("address"),
                blockHeight = u.getLong("height"),
                scriptPubKeyHex = u.optString("scriptPubKey", "").ifEmpty { null },
            )
        }

        val rawTxs = mutableMapOf<String, RawTxEntry>()
        val keys = rawTxsJson.keys()
        while (keys.hasNext()) {
            val txid = keys.next()
            val entry = rawTxsJson.getJSONObject(txid)
            rawTxs[txid] = RawTxEntry(
                hex = entry.getString("hex"),
                blockHeight = entry.getLong("height"),
                blockTime = entry.getLong("time"),
            )
        }
        return ReconcileResult(utxos, rawTxs, height)
    }
}

/** One UTXO from the node's view. */
data class UtxoEntry(
    val txid: String,
    val vout: Int,
    val amountSatoshi: Long,
    val address: String,
    val blockHeight: Long,
    /** Hex-encoded scriptPubKey, needed by the legacy-path sweeper to
     *  tell BRTransaction how each input is locked. Null for older
     *  backend responses; falls back to null-safe paths. */
    val scriptPubKeyHex: String? = null,
)

/** Raw parent-tx entry — hex + block metadata needed for registration. */
data class RawTxEntry(
    val hex: String,
    val blockHeight: Long,
    val blockTime: Long,
)

data class ReconcileResult(
    val utxos: List<UtxoEntry>,
    val rawTxs: Map<String, RawTxEntry>,
    val chainHeight: Long,
)
