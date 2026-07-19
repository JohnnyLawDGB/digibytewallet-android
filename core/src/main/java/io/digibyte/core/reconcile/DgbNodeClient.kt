package io.digibyte.core.reconcile

import android.content.Context
import io.digibyte.core.networkSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // Shared pin set — see io.digibyte.core.network.DigiScopePins. Pinning the
        // LE intermediate (not just the leaf) survives routine ~90-day leaf renewals;
        // a stale leaf-only pin previously killed "Scan for missing funds" for everyone.
        .certificatePinner(io.digibyte.core.network.DigiScopePins.certificatePinner())
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

    /**
     * Look up a transaction's confirming block height + time via the explorer
     * endpoints ({endpoint}/explorer/tx/:txid → blockhash + confirmations, then
     * /explorer/block/:hash → height). Returns null if unknown, still
     * unconfirmed, or on any network/parse error.
     *
     * Used by the txid-driven confirmation-reconcile to promote a stuck-"Pending"
     * tx the address/UTXO reconcile can't surface — e.g. a 0-value DigiDollar
     * token output a dust filter omits from scantxoutset. Driven by the wallet's
     * own pending txids, not the node's UTXO set, so it never misses such a tx.
     */
    suspend fun txConfirmation(txid: String): TxConfirmation? = withContext(Dispatchers.IO) {
        val tx = httpGetJson("${endpoint()}/explorer/tx/$txid") ?: return@withContext null
        val blockhash = tx.optString("blockhash", "")
        val confirmations = tx.optLong("confirmations", 0L)
        if (blockhash.isEmpty() || confirmations <= 0L) return@withContext null // still unconfirmed
        val blocktime = tx.optLong("blocktime", tx.optLong("time", 0L))
        val block = httpGetJson("${endpoint()}/explorer/block/$blockhash") ?: return@withContext null
        val height = block.optLong("height", 0L)
        if (height <= 0L) return@withContext null
        TxConfirmation(height, blocktime)
    }

    /**
     * History of every confirmed tx touching [address], via the ElectrumX-backed
     * {endpoint}/rpc/address-history/:address endpoint. Unlike the UTXO-based
     * reconcile this returns 0-value (DigiDollar) and fully-spent (asset marker)
     * txs for all four address types — the taproot/DD case scantxoutset misses.
     * Returns null on network/parse failure.
     */
    suspend fun addressHistory(address: String): List<AddressTx>? = withContext(Dispatchers.IO) {
        val root = httpGetJson("${endpoint()}/rpc/address-history/$address") ?: return@withContext null
        parseAddressHistory(root)
    }

    /**
     * BATCH address history: POST the whole address list to
     * {endpoint}/rpc/address-history and get back the deduped (txid, height)
     * union in ONE round-trip per [MAX_ADDRS_PER_REQUEST]. Replaces the
     * per-address GET loop that fired ~1 request/address and blew past the
     * server's 500/15min limiter (429 storm). Returns null on any failure.
     */
    suspend fun addressHistoryBatch(addresses: List<String>): List<AddressTx>? =
        withContext(Dispatchers.IO) {
            if (addresses.isEmpty()) return@withContext emptyList()
            val out = ArrayList<AddressTx>()
            for (chunk in addresses.chunked(MAX_ADDRS_PER_REQUEST)) {
                val payload = JSONObject().apply { put("addresses", JSONArray(chunk)) }
                val root = httpPostJson("${endpoint()}/rpc/address-history", payload)
                    ?: return@withContext null
                out += parseAddressHistory(root)
            }
            out
        }

    /**
     * Fetch a tx's raw hex + block time from {endpoint}/explorer/tx/:txid, packed
     * with the [height] the caller already learned from address-history (the
     * explorer/tx body exposes blockhash+confirmations but no direct height).
     * The height satisfies BRWallet's dust-pending gate on 0-value DD credits.
     * Returns null on network/parse failure.
     */
    suspend fun fetchRawTx(txid: String, height: Long): RawTxEntry? = withContext(Dispatchers.IO) {
        val root = httpGetJson("${endpoint()}/explorer/tx/$txid") ?: return@withContext null
        parseRawTxResponse(root, height)
    }

    /** GET a URL and parse the body as a JSON object. Null on non-2xx, empty
     *  body, parse error, or network failure (logged). Uses the same cert-pinned
     *  client selection as the reconcile POST. */
    private fun httpGetJson(url: String): JSONObject? {
        val req = Request.Builder().url(url).get().build()
        return try {
            client(url).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("DgbNodeClient", "GET $url returned HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (t: Throwable) {
            android.util.Log.w("DgbNodeClient", "GET $url threw ${t::class.java.simpleName}: ${t.message}")
            null
        }
    }

    /** POST a JSON body and parse the response as a JSON object. Null on non-2xx,
     *  empty body, parse error, or network failure (logged). Same cert-pinned
     *  client selection as the GET/reconcile paths. */
    private fun httpPostJson(url: String, payload: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()
        return try {
            client(url).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("DgbNodeClient", "POST $url returned HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (t: Throwable) {
            android.util.Log.w("DgbNodeClient", "POST $url threw ${t::class.java.simpleName}: ${t.message}")
            null
        }
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

/** A transaction's confirming block height + block time, from
 *  [DgbNodeClient.txConfirmation]. */
data class TxConfirmation(val height: Long, val time: Long)

/** One confirmed tx touching an address, from the node's address-history. */
data class AddressTx(val txid: String, val height: Long)

/** Pure: extract (txid, height) from a `/rpc/address-history/:address` body. */
internal fun parseAddressHistory(root: JSONObject): List<AddressTx> {
    val txs = root.optJSONArray("transactions") ?: return emptyList()
    val out = ArrayList<AddressTx>(txs.length())
    for (i in 0 until txs.length()) {
        val t = txs.optJSONObject(i) ?: continue
        val txid = t.optString("txid", "")
        if (txid.isBlank()) continue
        out += AddressTx(txid, t.optLong("height", 0L))
    }
    return out
}

/** Pure: pack a `/explorer/tx/:txid` body (hex + block time) with the height
 *  the caller learned from address-history. Null if hex absent. */
internal fun parseRawTxResponse(root: JSONObject, height: Long): RawTxEntry? {
    val hex = root.optString("hex", "")
    if (hex.isBlank()) return null
    val time = root.optLong("blocktime", root.optLong("time", 0L))
    return RawTxEntry(hex = hex, blockHeight = height, blockTime = time)
}
