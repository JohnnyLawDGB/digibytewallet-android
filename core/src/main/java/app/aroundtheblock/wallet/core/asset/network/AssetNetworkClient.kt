package app.aroundtheblock.wallet.core.asset.network

/**
 * Small slice of the DigiAsset RPC surface that the wallet actually needs —
 * each concrete [AssetNetworkClient] (the digiscope proxy, digistamp's indexer)
 * implements this shape on top of whatever REST/RPC dialect its upstream speaks.
 *
 * Methods return null on failure so [MultiEndpointAssetClient] can cleanly
 * fall through to the next endpoint in its rotation without exceptions
 * propagating into UI code.
 */
interface AssetNetworkClient {
    /** Human-readable endpoint identifier, used for logging + UI transparency. */
    val endpointLabel: String

    /** Return canonical asset metadata (name, symbol, supply, CID, rules).
     *  @param assetId the DigiAsset identifier, e.g. `La2ih1bm2u4d…`. */
    suspend fun getAssetData(assetId: String): AssetDataResponse?

    /**
     * [getAssetData], telling "this endpoint refused the reference" apart from "no answer". An
     * endpoint that does not make the distinction answers [AssetDataLookup.NoAnswer] for both.
     */
    suspend fun lookupAssetData(assetId: String): AssetDataLookup =
        getAssetData(assetId)?.let { AssetDataLookup.Found(it) } ?: AssetDataLookup.NoAnswer

    // getAddressHoldings was REMOVED, deliberately, rather than left unused.
    //
    // It sent one of the wallet's addresses to a third party to ask what it holds — exactly
    // what compact-filters-only sync exists to withhold. It had zero callers, because holdings
    // are computed natively from the CF scan and always have been. An unused method that leaks
    // is one call site away from leaking, and "we don't call it" is a weaker guarantee than
    // "it doesn't exist".
    //
    // A marketplace wanting to show "your holdings" must render the WALLET's own view beside
    // the catalogue, never ask a server what the user owns.

    /** Return ordered txids (most recent first) that touched [address] for
     *  asset operations. Used for per-address DigiAsset history recovery. */
    suspend fun getAddressHistory(
        address: String,
        limit: Int? = null,
    ): List<String>?

    /** Health snapshot of the remote node. `sync < -120` means it's too far
     *  behind to answer reliably; callers should treat that as "unavailable"
     *  and rotate. */
    suspend fun getSyncState(): SyncStateResponse?

    /** Fetch the raw serialized bytes of a transaction by its display-order
     *  txid hex. Used by M3 parent-walk resolution — given a DigiAsset
     *  transfer's input txid, we need the parent transaction's OP_RETURN
     *  and inputs to continue walking toward the issuance.
     *
     *  Note this is equivalent to a full node's `getrawtransaction <txid>`
     *  call; a thin server endpoint can simply forward the JSON-RPC. Full
     *  SPV `getdata` is a future implementation of the same primitive.
     *
     *  Returns raw tx bytes on success, null if the endpoint doesn't know
     *  this txid or the request failed. */
    suspend fun getRawTransaction(txHashHex: String): ByteArray?
}


/** What one endpoint says when asked for an asset's data. */
sealed interface AssetDataLookup {
    data class Found(val data: AssetDataResponse) : AssetDataLookup
    /** The endpoint answered that the reference is not one it takes (its 400), or the wallet could
     *  tell it would and did not ask. The endpoint is working; this request is simply not valid
     *  there. Nothing shown changes, and the same request is not repeated. */
    data object Refused : AssetDataLookup
    /** No usable answer: unreachable, an error status, a body that could not be read. */
    data object NoAnswer : AssetDataLookup
}

/**
 * The references `GET /digiassets/asset/:ref` (and `/holders/:ref`) on digiscope takes: an asset
 * index of 1 to 10 digits, or a 38-character base58 DigiAsset id (they start with L or U).
 * Anything else is answered 400, so the wallet does not send it. In particular a row's
 * `unresolved:<txid>` placeholder is not a reference.
 */
object DigiScopeAssetRef {
    private val INDEX = Regex("^[0-9]{1,10}$")
    private val ASSET_ID = Regex("^[LU][1-9A-HJ-NP-Za-km-z]{37}$")

    fun isValid(ref: String): Boolean = INDEX.matches(ref) || ASSET_ID.matches(ref)
}

/** Minimal projection of the `getassetdata` response — richer fields can be
 *  added lazily as the UI exposes them. */
data class AssetDataResponse(
    val assetId: String,
    val cid: String?,
    val issuer: String?,
    val count: Long,
    val decimals: Int,
    /** Raw IPFS-resolved metadata JSON object. Null when `excludeIPFS=true`
     *  or the CID isn't pinned. Kept as a nullable map rather than parsed
     *  eagerly so new fields surface automatically. */
    val ipfs: Map<String, Any?>?,
    /** DigiAsset Core's `rules` object (royalty/deflation/expiry/geofence/vote/signers/…),
     *  which it emits only when the asset actually has rules. Null when absent from the
     *  response. Consumed by the transfer-rule gate as a signal that can only tighten. */
    val rules: Map<String, Any?>? = null,
)

data class SyncStateResponse(
    /** Local node's current block height. */
    val count: Long,
    /** Blocks-behind; 0 == at tip, negative == behind. */
    val sync: Long,
)

/**
 * The most an asset indexer's answer may be, per route. The same for every indexer, since they
 * answer the same shapes. An answer over its cap is no answer, as from an indexer that cannot be
 * reached, and is never held whole.
 */
internal object AssetAnswerCaps {
    /** One asset's data, with any metadata the indexer inlines. */
    const val ASSET_DATA = 1L * 1024 * 1024

    /** One raw transaction, as hex. */
    const val RAW_TRANSACTION = 4L * 1024 * 1024

    /** An address's asset history: a list of txids. */
    const val ADDRESS_HISTORY = 4L * 1024 * 1024

    /** The indexer's sync state. */
    const val SYNC_STATE = 64L * 1024

    /** The assets on one unspent output (or the indexer's not-found answer for it): a few entries in
     *  practice, and 64 KiB is room for hundreds. */
    const val OUTPUT_STACK = 64L * 1024
}

/**
 * The URL to name in a failure log. The address-history route embeds the wallet's own address
 * in the path, and every non-2xx / thrown request used to log the raw URL — so a flaky endpoint
 * wrote the user's address to logcat on each retry. Only the route survives.
 */
internal fun endpointOf(url: String): String =
    url.replace(Regex("""/history/[^/?]+"""), "/history/<addr>")
