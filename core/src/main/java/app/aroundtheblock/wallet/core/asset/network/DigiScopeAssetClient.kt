package app.aroundtheblock.wallet.core.asset.network

import app.aroundtheblock.wallet.core.LogSafe
import app.aroundtheblock.wallet.core.LogTag

import app.aroundtheblock.wallet.core.network.BoundedBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DigiAsset network client backed by our own `digiscope.me` proxy, which
 * passes through to a locally-hosted `digiasset_core` instance. Cert-pinned
 * to the same certificates [app.aroundtheblock.wallet.core.reconcile.DgbNodeClient] uses
 * for the reconcile endpoint.
 *
 * See `/opt/digiscope-backend/src/server.js` for the 6 proxied routes.
 */
class DigiScopeAssetClient(
    /** The client every request is derived from: in the app the shared one (Tor routing). No default —
     *  a client made here would carry no Tor routing. */
    baseClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AssetNetworkClient, app.aroundtheblock.wallet.core.asset.send.AssetStackSource {

    override val endpointLabel: String = "digiscope.me"

    private val client: OkHttpClient = baseClient.newBuilder()
        // Belt-and-suspenders: digiasset_core's listunspent + getrawtransaction
        // RPCs can run 20-40s under load even with the watchdog in place.
        // Per backend dev's note 2026-04-25.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Shared pin set — see app.aroundtheblock.wallet.core.network.DigiScopePins. A stale
        // pin here previously killed the M3 asset parent-walk → "metadata offline".
        .certificatePinner(app.aroundtheblock.wallet.core.network.DigiScopePins.certificatePinner())
        // A pinned host's 3xx is never followed to a host the pins do not cover (4.1.2 row 30, C18).
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * [client] for the indexer stack lookup only, with redirects off. Its answer decides whether
     * an asset send may spend an output and what a received output is credited with, so it must
     * come from the pinned endpoint that was asked, never from wherever a 3xx points. A redirect
     * is returned as a non-2xx response, which [readStack] reads as "no answer". The other calls
     * keep [client]'s behaviour.
     */
    private val stackClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * The DigiAsset stack on one unspent output, straight from the indexer
     * (`GET /digiassets/txout/:txid/:vout`). How each status reads:
     *  - 200: the stack ([StackLookup.Found]; an empty one is "no assets here");
     *  - 404 `{"unspent": false}`: [StackLookup.NotUnspent];
     *  - 503 `{"syncing": true, ...}` or with `confirmations`: [StackLookup.NotYet], the index has
     *    not read this far. Never an empty stack and never "not unspent";
     *  - 400 `{"error"}`: [StackLookup.Refused]. An outpoint that is not a 64-digit hex id and a
     *    non-negative index is refused here, without a request;
     *  - anything else, a redirect and a malformed body included: [StackLookup.Unavailable].
     */
    override suspend fun stackOf(txid: String, vout: Int): app.aroundtheblock.wallet.core.asset.send.StackLookup =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readStack(txid, vout) }

    /** Blocking; [stackOf] runs it on the IO dispatcher (its callers include the main thread). */
    private fun readStack(txid: String, vout: Int): app.aroundtheblock.wallet.core.asset.send.StackLookup = try {
        if (vout < 0 || !TXID_HEX.matches(txid.lowercase())) {
            android.util.Log.w("DigiScopeAssetClient", "txout: not an outpoint, not asked")
            return app.aroundtheblock.wallet.core.asset.send.StackLookup.Refused
        }
        val req = Request.Builder().url("$baseUrl/digiassets/txout/${txid.lowercase()}/$vout").get().build()
        stackClient.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> DigiScopeAssetParsing.notFound(BoundedBody.text(resp.body, AssetAnswerCaps.OUTPUT_STACK))
                resp.code == 503 -> DigiScopeAssetParsing.notReady(BoundedBody.text(resp.body, AssetAnswerCaps.OUTPUT_STACK)).also {
                    // Named in the log: a holding that does not appear needs its reason on record.
                    if (it is app.aroundtheblock.wallet.core.asset.send.StackLookup.NotYet) {
                        android.util.Log.w(
                            "DigiScopeAssetClient",
                            "txout ${LogTag.tx(txid)}:$vout: index cannot say yet " +
                                "(syncing=${LogSafe.text(it.indexSyncing)} " +
                                "blocksBehind=${LogSafe.text(it.blocksBehind)} " +
                                "confirmations=${LogSafe.text(it.confirmations)})",
                        )
                    } else {
                        android.util.Log.w("DigiScopeAssetClient", "txout ${LogTag.tx(txid)}:$vout returned HTTP 503")
                    }
                }
                resp.code == 400 -> {
                    android.util.Log.w("DigiScopeAssetClient", "txout ${LogTag.tx(txid)}:$vout returned HTTP 400")
                    if (DigiScopeAssetParsing.isRefusal(BoundedBody.text(resp.body, AssetAnswerCaps.OUTPUT_STACK))) {
                        app.aroundtheblock.wallet.core.asset.send.StackLookup.Refused
                    } else app.aroundtheblock.wallet.core.asset.send.StackLookup.Unavailable
                }
                !resp.isSuccessful -> {
                    android.util.Log.w("DigiScopeAssetClient", "txout ${LogTag.tx(txid)}:$vout returned HTTP ${resp.code}")
                    app.aroundtheblock.wallet.core.asset.send.StackLookup.Unavailable
                }
                else -> {
                    val body = BoundedBody.text(resp.body, AssetAnswerCaps.OUTPUT_STACK)
                    if (body.isNullOrEmpty()) app.aroundtheblock.wallet.core.asset.send.StackLookup.Unavailable
                    else DigiScopeAssetParsing.txOutStack(JSONObject(body), txid, vout)
                }
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w("DigiScopeAssetClient", "txout ${LogTag.tx(txid)}:$vout threw ${t::class.java.simpleName}")
        app.aroundtheblock.wallet.core.asset.send.StackLookup.Unavailable
    }

    override suspend fun getAssetData(assetId: String): AssetDataResponse? =
        (lookupAssetData(assetId) as? AssetDataLookup.Found)?.data

    /**
     * `GET /digiassets/asset/:ref`. A reference the route does not take ([DigiScopeAssetRef]) is
     * [AssetDataLookup.Refused] without a request; the route's own 400 reads the same. Either way
     * that is one request at most: nothing here asks twice.
     */
    override suspend fun lookupAssetData(assetId: String): AssetDataLookup {
        if (!DigiScopeAssetRef.isValid(assetId)) {
            android.util.Log.w("DigiScopeAssetClient", "asset: not an asset index or id, not asked")
            return AssetDataLookup.Refused
        }
        var refused = false
        val json = getJson("$baseUrl/digiassets/asset/$assetId", AssetAnswerCaps.ASSET_DATA) { code, body ->
            refused = code == 400 && DigiScopeAssetParsing.isRefusal(body)
        } as? JSONObject
        if (refused) return AssetDataLookup.Refused
        val parsed = json?.let { DigiScopeAssetParsing.assetData(it, fallbackAssetId = assetId) }
            ?: return AssetDataLookup.NoAnswer
        // The ipfs blob, when the backend inlines one, is richer than the parser's remit.
        return AssetDataLookup.Found(parsed.copy(ipfs = json.optJSONObject("ipfs")?.let { ipfsJsonToMap(it) }))
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
        val json = getJson(url, AssetAnswerCaps.ADDRESS_HISTORY) ?: return null
        val arr = json as? JSONArray ?: return null
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    /**
     * NO BACKEND ROUTE EXISTS for this yet (probed 2026-08-23: 404), and nothing in the wallet
     * calls it. Left in place because the rotation's health story will want it, but do not read
     * a null here as "the node is behind" — it means the route is absent.
     */
    override suspend fun getSyncState(): SyncStateResponse? {
        val json = getJson("$baseUrl/syncstate", AssetAnswerCaps.SYNC_STATE) as? JSONObject ?: return null
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
        val json = getJson("$baseUrl/tx/raw/$txHashHex", AssetAnswerCaps.RAW_TRANSACTION) as? JSONObject ?: return null
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

    /** GET [url] as JSON (an object, or an array), reading at most [cap] bytes of the answer.
     *  [onStatus] is handed a non-2xx status and its (capped) body. */
    private fun getJson(url: String, cap: Long, onStatus: ((Int, String?) -> Unit)? = null): Any? = try {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w(
                    "DigiScopeAssetClient",
                    "GET ${endpointOf(url)} returned HTTP ${resp.code} ${LogSafe.text(resp.message)}"
                )
                onStatus?.invoke(resp.code, BoundedBody.text(resp.body, ERROR_BODY_CAP))
                return null
            }
            val body = BoundedBody.text(resp.body, cap)
            if (body == null) {
                android.util.Log.w("DigiScopeAssetClient", "GET ${endpointOf(url)}: no body, or over $cap bytes")
                return null
            }
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    } catch (t: Throwable) {
        android.util.Log.w(
            "DigiScopeAssetClient",
            // ASSET-LOG-CRLF: not the throwable -- org.json quotes the whole input in its message,
            // and the stack trace prints that message raw.
            "GET ${endpointOf(url)} threw ${t::class.java.simpleName}: ${LogSafe.text(t.message)}"
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
        private val TXID_HEX = Regex("^[0-9a-f]{64}$")
        /** An error body is a short message; more than this is not read. */
        private const val ERROR_BODY_CAP = 16L * 1024
    }
}
