package io.digibyte.core

import io.digibyte.core.db.dao.PriceCacheDao
import io.digibyte.core.db.entity.PriceCacheEntity
import io.digibyte.core.model.OraclePriceProvider
import io.digibyte.core.model.PriceSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PriceData(
    val priceUsd: Double,
    val change24h: Double,
    val source: String,
    val updatedAt: Long,
    val pricePhp: Double = 0.0,
    /**
     * Every rate the last fetch returned, keyed by lower-case currency code ("usd", "jpy").
     *
     * [priceUsd] and [pricePhp] are kept as real fields rather than folded in here, so that the
     * Send screen, the price card and the Room cache — none of which care about the display
     * picker — carry on working untouched.
     */
    val rates: Map<String, Double> = emptyMap(),
) {
    /**
     * Rate for [code], or 0.0 when this fetch did not carry it.
     *
     * Falls back to the two legacy fields so a row cached BEFORE [rates] existed still answers
     * for USD and PHP instead of silently reading as "no rate" after an upgrade.
     */
    fun rateFor(code: String): Double {
        val key = code.trim().lowercase(java.util.Locale.US)
        rates[key]?.let { if (it > 0.0) return it }
        return when (key) {
            "usd" -> priceUsd
            "php" -> pricePhp
            else -> 0.0
        }
    }
}

/**
 * Currencies fetched on every price tick.
 *
 * CoinGecko takes them as one comma-separated request, so the whole set costs exactly what USD
 * alone used to — there is no per-currency price to pay for breadth here, and no reason to make
 * the user wait for a second call when they switch.
 *
 * Ordered roughly by the size of the DigiByte-using population rather than alphabetically; the
 * picker shows them in this order.
 */
val SUPPORTED_PRICE_CURRENCIES: List<String> = listOf(
    "usd", "btc", "eur", "inr", "cny", "jpy", "brl", "ngn", "php",
    "idr", "rub", "gbp", "krw", "vnd", "try", "mxn", "cad", "aud", "zar",
)

/**
 * Abstraction over HTTP GET so tests can inject a fake without mocking final OkHttp classes.
 * Returns the response body string, or throws on network/HTTP error.
 */
fun interface HttpFetcher {
    fun fetch(url: String): String
}

/** Production [HttpFetcher] backed by OkHttp. */
fun okHttpFetcher(client: OkHttpClient = OkHttpClient()): HttpFetcher = HttpFetcher { url ->
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.string() ?: throw IOException("Empty body")
    }
}

/**
 * Fetches the DGB/USD price from CoinGecko (primary) or Binance (fallback),
 * caches the result in Room, and returns the cached value when both sources are offline.
 *
 * Source attribution is preserved in [PriceData.source]:
 * - "coingecko" — live CoinGecko data
 * - "binance"   — live Binance data
 * - "<source> (cached)" — stale Room-cached data
 * - "unavailable" — no data at all
 */
class PriceProvider(
    private val priceCacheDao: PriceCacheDao,
    private val fetcher: HttpFetcher = okHttpFetcher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val oracleProvider: OraclePriceProvider? = null
) {
    /**
     * Fetch with a preferred [PriceSource].
     * - [PriceSource.ORACLE]: tries the oracle provider first; falls back to API if unavailable.
     * - [PriceSource.API]: goes directly to external APIs (CoinGecko → Binance).
     * - [PriceSource.CACHED]: returns only the Room-cached value (fast, no network).
     */
    suspend fun fetchPrice(
        currency: String = "USD",
        preferredSource: PriceSource
    ): PriceData = withContext(ioDispatcher) {
        when (preferredSource) {
            PriceSource.ORACLE -> {
                // Try oracle provider first; fall back to API path if not available
                val oracle = oracleProvider
                if (oracle != null && oracle.isAvailable()) {
                    try {
                        val price = oracle.fetchOraclePrice()
                        if (price != null) {
                            cachePrice(price, currency)
                            return@withContext price
                        }
                    } catch (_: Exception) { }
                }
                // Oracle unavailable — fall through to normal API path
                return@withContext fetchPrice(currency)
            }
            PriceSource.CACHED -> {
                val cached = priceCacheDao.getPrice(currency)
                return@withContext if (cached != null) {
                    PriceData(
                        priceUsd = cached.pricePerDgb,
                        change24h = cached.change24h,
                        source = cached.source + " (cached)",
                        updatedAt = cached.updatedAt
                    )
                } else {
                    PriceData(0.0, 0.0, "unavailable", System.currentTimeMillis())
                }
            }
            PriceSource.API -> fetchPrice(currency)
        }
    }

    suspend fun fetchPrice(currency: String = "USD"): PriceData = withContext(ioDispatcher) {
        // Try CoinGecko first
        try {
            val price = fetchFromCoinGecko()
            cachePrice(price, currency)
            return@withContext price
        } catch (_: Exception) { }

        // Fallback to Binance
        try {
            val price = fetchFromBinance()
            cachePrice(price, currency)
            return@withContext price
        } catch (_: Exception) { }

        // Both failed — return cached
        val cached = priceCacheDao.getPrice(currency)
        if (cached != null) {
            return@withContext PriceData(
                priceUsd = cached.pricePerDgb,
                change24h = cached.change24h,
                source = cached.source + " (cached)",
                updatedAt = cached.updatedAt
            )
        }

        // Nothing available
        PriceData(0.0, 0.0, "unavailable", System.currentTimeMillis())
    }

    private fun fetchFromCoinGecko(): PriceData {
        val body = fetcher.fetch(
            "https://api.coingecko.com/api/v3/simple/price" +
            "?ids=digibyte&vs_currencies=${SUPPORTED_PRICE_CURRENCIES.joinToString(",")}" +
            "&include_24hr_change=true"
        )
        val json = JSONObject(body).getJSONObject("digibyte")

        // Take whatever came back rather than a fixed set: a currency CoinGecko stops quoting
        // should read as "no rate" and show a dash, not as a stale number kept alive here.
        val rates = SUPPORTED_PRICE_CURRENCIES.mapNotNull { code ->
            val v = json.optDouble(code, 0.0)
            if (v > 0.0) code to v else null
        }.toMap()

        return PriceData(
            priceUsd = json.getDouble("usd"),
            change24h = json.optDouble("usd_24h_change", 0.0),
            source = "coingecko",
            updatedAt = System.currentTimeMillis(),
            pricePhp = json.optDouble("php", 0.0),
            rates = rates,
        )
    }

    private fun fetchFromBinance(): PriceData {
        val body = fetcher.fetch(
            "https://api.binance.com/api/v3/ticker/24hr?symbol=DGBUSDT"
        )
        val json = JSONObject(body)
        return PriceData(
            priceUsd = json.getString("lastPrice").toDouble(),
            change24h = json.getString("priceChangePercent").toDouble(),
            source = "binance",
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun cachePrice(price: PriceData, currency: String) {
        priceCacheDao.insert(
            PriceCacheEntity(
                currency = currency,
                pricePerDgb = price.priceUsd,
                change24h = price.change24h,
                source = price.source,
                updatedAt = price.updatedAt
            )
        )
    }
}
