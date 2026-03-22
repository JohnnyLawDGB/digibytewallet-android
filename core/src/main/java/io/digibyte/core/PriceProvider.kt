package io.digibyte.core

import io.digibyte.core.db.dao.PriceCacheDao
import io.digibyte.core.db.entity.PriceCacheEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PriceData(
    val priceUsd: Double,
    val change24h: Double,
    val source: String,
    val updatedAt: Long
)

/**
 * Abstraction over HTTP GET so tests can inject a fake without mocking final OkHttp classes.
 * Returns the response body string, or throws on network/HTTP error.
 */
fun interface HttpFetcher {
    fun fetch(url: String): String
}

/** Production [HttpFetcher] backed by OkHttp. */
internal fun okHttpFetcher(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
): HttpFetcher = HttpFetcher { url ->
    val response = client.newCall(Request.Builder().url(url).build()).execute()
    response.body?.string() ?: throw Exception("Empty response from $url")
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
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
            "?ids=digibyte&vs_currencies=usd&include_24hr_change=true"
        )
        val json = JSONObject(body).getJSONObject("digibyte")
        return PriceData(
            priceUsd = json.getDouble("usd"),
            change24h = json.optDouble("usd_24h_change", 0.0),
            source = "coingecko",
            updatedAt = System.currentTimeMillis()
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
