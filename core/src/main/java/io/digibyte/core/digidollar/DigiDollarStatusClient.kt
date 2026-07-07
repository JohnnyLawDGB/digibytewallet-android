package io.digibyte.core.digidollar

import io.digibyte.core.HttpFetcher
import io.digibyte.core.PriceData
import io.digibyte.core.model.OraclePriceProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** DigiDollar softfork deployment as the wallet acts on it: anything the API
 *  does not positively report as "active" is INACTIVE (gate closed). */
enum class DigiDollarDeployment { ACTIVE, INACTIVE }

/** One snapshot of the oracle/status endpoint. */
data class DigiDollarStatus(
    val deployment: DigiDollarDeployment,
    val priceMicroUsd: Long,
    val priceUpdatedAtMs: Long,
    val dcaMultiplierBps: Long,
)

/**
 * Read-only DigiDollar price/status client (issue #5, ADR-0002). Fetches the
 * Oracle price (micro-USD per DGB), DCA multiplier (basis points), and the
 * softfork deployment status for the requested network from the digiscope
 * backend, which proxies a v9.26.4 node's `getoracleprice` /
 * `getdcamultiplier` / `getdigidollardeploymentinfo`.
 *
 * Requests carry only the network name — nothing wallet-derived. The default
 * endpoint rides the app's cert-pinned client; a user-supplied [baseUrl]
 * (self-hosted node front) uses whatever fetcher the caller wires, matching
 * the existing override pattern.
 *
 * Also the production [OraclePriceProvider]: PriceProvider's ORACLE source
 * and the Mint divergence cross-check both read the same fetch.
 */
class DigiDollarStatusClient(
    private val fetcher: HttpFetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** The wallet's persisted network selection ([io.digibyte.core.isTestnet]),
     *  wired at construction — the client itself never touches Android state. */
    private val testnet: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OraclePriceProvider {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.digiscope.me"
    }

    /** Latest status for the network, or null when the endpoint is
     *  unreachable or the response is unusable — callers treat null as
     *  gate-closed / Mint-blocked. */
    suspend fun fetchStatus(testnet: Boolean): DigiDollarStatus? = withContext(ioDispatcher) {
        val network = if (testnet) "testnet" else "mainnet"
        try {
            val json = JSONObject(fetcher.fetch("$baseUrl/api/digidollar/status?network=$network"))
            val priceMicroUsd = json.getLong("priceMicroUsd")
            val dcaBps = json.getLong("dcaMultiplierBps")
            if (priceMicroUsd <= 0 || dcaBps <= 0) return@withContext null
            DigiDollarStatus(
                deployment = if (json.getString("deployment") == "active") {
                    DigiDollarDeployment.ACTIVE
                } else {
                    DigiDollarDeployment.INACTIVE
                },
                priceMicroUsd = priceMicroUsd,
                priceUpdatedAtMs = json.getLong("priceUpdatedAt") * 1000,
                dcaMultiplierBps = dcaBps,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- OraclePriceProvider ----

    override fun isAvailable(): Boolean = true

    override suspend fun fetchOraclePrice(): PriceData? {
        val status = fetchStatus(testnet) ?: return null
        return PriceData(
            priceUsd = status.priceMicroUsd / 1_000_000.0,
            change24h = 0.0,
            source = "oracle",
            updatedAt = status.priceUpdatedAtMs,
        )
    }
}
