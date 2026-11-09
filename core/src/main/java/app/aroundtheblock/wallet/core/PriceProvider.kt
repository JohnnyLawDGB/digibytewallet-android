package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.db.dao.PriceCacheDao
import app.aroundtheblock.wallet.core.db.entity.PriceCacheEntity
import app.aroundtheblock.wallet.core.model.OraclePriceProvider
import app.aroundtheblock.wallet.core.model.PriceSource
import app.aroundtheblock.wallet.core.network.BoundedBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    // Added when the two currency pickers were unified: the Settings list offered these three
    // while this one did not, so choosing one stored a currency no fetch ever carried and the
    // balance read "--" for good. Anything the picker can show has to be fetched here.
    "chf", "sek", "nok",
)

/**
 * Abstraction over HTTP GET so tests can inject a fake without mocking final OkHttp classes.
 * Returns the response body string, or throws on network/HTTP error.
 */
fun interface HttpFetcher {
    fun fetch(url: String): String
}

/**
 * The most a price answer may be: 64 KiB. CoinGecko's answer for every supported currency is about
 * 1.5 KB, Binance's ticker under 1 KB.
 */
private const val MAX_PRICE_BODY_BYTES = 64L * 1024

/**
 * Production [HttpFetcher] backed by OkHttp, on the client it is given — in the app the shared one,
 * which carries the Tor routing. An answer over [MAX_PRICE_BODY_BYTES] is refused unread.
 */
fun okHttpFetcher(client: OkHttpClient): HttpFetcher = HttpFetcher { url ->
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        BoundedBody.text(response.body, MAX_PRICE_BODY_BYTES)
            ?: throw IOException("No body, or a body over $MAX_PRICE_BODY_BYTES bytes")
    }
}

/**
 * Fetches the DGB/USD price from CoinGecko (primary) or Binance (fallback),
 * caches the result in Room, and returns the cached value when both sources are offline.
 *
 * Source attribution is preserved in [PriceData.source]:
 * - "coingecko" — live CoinGecko data
 * - "binance"   — live Binance data
 * - "<source> (cached)" — Room-cached data no older than [MAX_AGE_MS]
 * - "unavailable" — no data at all
 *
 * Owner decisions for both apps (2026-11-09; iOS's PriceProvider has the same rules):
 * - **A maximum age.** A cached price older than [MAX_AGE_MS] (one hour), or dated in the future
 *   (a clock set back), is not used: the answer is "unavailable" and no dollar figure is shown
 *   from it. DGB entry keeps working.
 * - **An off switch** ([enabled], Settings → Display → Show prices, default on). Off, nothing is
 *   fetched, nothing is cached, and every answer is "unavailable".
 * - **A reuse window.** [currentPrice] answers from a price fetched less than [REUSE_MS] ago
 *   instead of fetching again, for the send screen, which asks on every keystroke.
 */
class PriceProvider(
    private val priceCacheDao: PriceCacheDao,
    private val fetcher: HttpFetcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val oracleProvider: OraclePriceProvider? = null,
    /** Settings → Display → Show prices. Read on every call, so turning it off takes effect at once. */
    private val enabled: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** A cached price older than this is not shown (owner decision, 2026-11-09). */
        const val MAX_AGE_MS: Long = 60 * 60 * 1000L
        /** [currentPrice] reuses a price fetched no longer ago than this (iOS's window). */
        const val REUSE_MS: Long = 60 * 1000L

        /**
         * Fetched no more than [MAX_AGE_MS] before [nowMs], and not after it (a clock set back). Checked
         * wherever a price is shown or converts an amount, not only at fetch: a price held in
         * memory ages too.
         */
        fun isFreshAt(updatedAt: Long, nowMs: Long): Boolean = (nowMs - updatedAt) in 0..MAX_AGE_MS

        /** A price to show or convert at, at [nowMs]: a real one ([isFreshAt]), not "unavailable". */
        fun isFresh(price: PriceData, nowMs: Long): Boolean =
            price.source != "unavailable" && price.priceUsd > 0.0 && !price.priceUsd.isInfinite() &&
                isFreshAt(price.updatedAt, nowMs)
    }

    /**
     * The price for a screen that asks often (the send screen, per keystroke): the cached price when
     * it was fetched no more than [REUSE_MS] ago, else [fetchPrice]. USD only: the cache row carries
     * no [PriceData.rates].
     */
    suspend fun currentPrice(currency: String = "USD"): PriceData = withContext(ioDispatcher) {
        if (!enabled()) return@withContext unavailable()
        val cached = priceCacheDao.getPrice(currency)
        if (cached != null && isWithin(cached, REUSE_MS) && cached.pricePerDgb > 0.0 && cached.pricePerDgb.isFinite()) {
            return@withContext PriceData(
                priceUsd = cached.pricePerDgb,
                change24h = cached.change24h,
                source = cached.source,
                updatedAt = cached.updatedAt
            )
        }
        fetchPrice(currency)
    }

    /** Drops the cached price (Show prices turned off). */
    suspend fun clearCache() = withContext(ioDispatcher) { priceCacheDao.clear() }

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
        if (!enabled()) return@withContext unavailable()
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
            PriceSource.CACHED -> return@withContext cachedOrUnavailable(currency)
            PriceSource.API -> fetchPrice(currency)
        }
    }

    suspend fun fetchPrice(currency: String = "USD"): PriceData = withContext(ioDispatcher) {
        // Off: no request at all.
        if (!enabled()) return@withContext unavailable()

        // CoinGecko first, then Binance
        val live = fromLiveSource(currency) { fetchFromCoinGecko() }
            ?: fromLiveSource(currency) { fetchFromBinance() }
        if (live != null) return@withContext live

        // Both failed — the cached price, while it is under an hour old; else nothing
        ensureActive()
        if (!enabled()) return@withContext unavailable()
        cachedOrUnavailable(currency)
    }

    /**
     * One live source, cached on success; null when it failed. A fetch cancelled, or turned off,
     * while the request was out stops here: it neither moves on to the next source nor writes the
     * cache that turning prices off has just cleared.
     */
    private suspend fun fromLiveSource(currency: String, fetch: () -> PriceData): PriceData? {
        currentCoroutineContext().ensureActive()
        if (!enabled()) return null
        val price = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        currentCoroutineContext().ensureActive()
        if (!enabled()) return null
        cachePrice(price, currency)
        // Off by the time it is written: not shown either (cachePrice has cleared it again).
        return if (enabled()) price else null
    }

    private suspend fun cachedOrUnavailable(currency: String): PriceData {
        val cached = priceCacheDao.getPrice(currency)
        if (cached != null && isWithin(cached, MAX_AGE_MS) && cached.pricePerDgb > 0.0 && cached.pricePerDgb.isFinite()) {
            return PriceData(
                priceUsd = cached.pricePerDgb,
                change24h = cached.change24h,
                source = cached.source + " (cached)",
                updatedAt = cached.updatedAt
            )
        }
        return unavailable()
    }

    private fun unavailable() = PriceData(0.0, 0.0, "unavailable", nowMs())

    /** Fetched no more than [ms] ago, and not in the future (a clock set back). */
    private fun isWithin(cached: PriceCacheEntity, ms: Long): Boolean =
        (nowMs() - cached.updatedAt) in 0..ms

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
            priceUsd = usablePrice(json.getDouble("usd")),
            change24h = json.optDouble("usd_24h_change", 0.0),
            source = "coingecko",
            updatedAt = nowMs(),
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
            priceUsd = usablePrice(json.getString("lastPrice").toDouble()),
            change24h = json.getString("priceChangePercent").toDouble(),
            source = "binance",
            updatedAt = nowMs()
        )
    }

    /**
     * A price that is not a positive finite number fails its source, which moves on to the next one
     * (as iOS's PriceProvider does), instead of being cached and shown as no price at all.
     */
    private fun usablePrice(v: Double): Double =
        if (v.isFinite() && v > 0.0) v else throw IOException("Not a usable price")

    private suspend fun cachePrice(price: PriceData, currency: String) {
        // Turned off while the request was out: the cleared cache stays clear.
        if (!enabled()) return
        priceCacheDao.insert(
            PriceCacheEntity(
                currency = currency,
                pricePerDgb = price.priceUsd,
                change24h = price.change24h,
                source = price.source,
                updatedAt = price.updatedAt
            )
        )
        // Turned off between that check and the insert: the switch is set before the cache is
        // cleared, so seeing it off here means the clear may already have run. Clear again.
        if (!enabled()) priceCacheDao.clear()
    }
}
