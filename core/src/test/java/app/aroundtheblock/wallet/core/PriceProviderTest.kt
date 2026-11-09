package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.db.dao.PriceCacheDao
import app.aroundtheblock.wallet.core.db.entity.PriceCacheEntity
import io.mockk.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PriceProviderTest {

    private lateinit var mockDao: PriceCacheDao
    private lateinit var priceProvider: PriceProvider

    private val coinGeckoJson =
        """{"digibyte":{"usd":0.01234,"usd_24h_change":-2.5}}"""

    private val binanceJson =
        """{"lastPrice":"0.01234","priceChangePercent":"-2.5","symbol":"DGBUSDT"}"""

    private val coinGeckoUrl = "https://api.coingecko.com"
    private val binanceUrl   = "https://api.binance.com"

    @Before
    fun setUp() {
        mockDao = mockk(relaxed = true)
    }

    private val now = 1_700_000_000_000L

    private fun makeProvider(fetcher: HttpFetcher, enabled: () -> Boolean = { true }, nowMs: Long = now) =
        PriceProvider(mockDao, fetcher, UnconfinedTestDispatcher(), enabled = enabled, nowMs = { nowMs })

    private fun cachedAt(updatedAt: Long, source: String = "coingecko") = PriceCacheEntity(
        currency = "USD", pricePerDgb = 0.00999, change24h = 1.1, source = source, updatedAt = updatedAt,
    )

    /** Counts what it was asked; answers with [answer]. */
    private class CountingFetcher(private val answer: (String) -> String = { "" }) : HttpFetcher {
        val urls = mutableListOf<String>()
        override fun fetch(url: String): String { urls += url; return answer(url) }
    }

    // ------------------------------------------------------------------
    // Scenario 1: CoinGecko succeeds
    // ------------------------------------------------------------------

    @Test
    fun `fetchPrice returns CoinGecko price on success`() = runTest {
        val fetcher = HttpFetcher { coinGeckoJson } // always returns CoinGecko JSON
        priceProvider = makeProvider(fetcher)
        coEvery { mockDao.insert(any()) } just Runs

        val result = priceProvider.fetchPrice("USD")

        assertEquals(0.01234, result.priceUsd, 0.00001)
        assertEquals(-2.5, result.change24h, 0.001)
        assertEquals("coingecko", result.source)
        coVerify(exactly = 1) { mockDao.insert(any()) }
    }

    // ------------------------------------------------------------------
    // Scenario 2: CoinGecko fails, Binance succeeds
    // ------------------------------------------------------------------

    @Test
    fun `fetchPrice falls back to Binance when CoinGecko fails`() = runTest {
        val fetcher = HttpFetcher { url ->
            if (url.contains("coingecko")) throw Exception("CoinGecko unreachable")
            binanceJson
        }
        priceProvider = makeProvider(fetcher)
        coEvery { mockDao.insert(any()) } just Runs

        val result = priceProvider.fetchPrice("USD")

        assertEquals(0.01234, result.priceUsd, 0.00001)
        assertEquals(-2.5, result.change24h, 0.001)
        assertEquals("binance", result.source)
        coVerify(exactly = 1) { mockDao.insert(any()) }
    }

    // ------------------------------------------------------------------
    // Scenario 3: Both APIs fail — returns cached value
    // ------------------------------------------------------------------

    @Test
    fun `fetchPrice returns cached when both APIs fail`() = runTest {
        val fetcher = HttpFetcher { throw Exception("Network unavailable") }
        // Half an hour after the cached price: inside the maximum age.
        priceProvider = makeProvider(fetcher, nowMs = 1_700_000_000_000L + 30 * 60 * 1000L)

        val cachedEntity = PriceCacheEntity(
            currency = "USD",
            pricePerDgb = 0.00999,
            change24h = 1.1,
            source = "coingecko",
            updatedAt = 1_700_000_000_000L
        )
        coEvery { mockDao.getPrice("USD") } returns cachedEntity

        val result = priceProvider.fetchPrice("USD")

        assertEquals(0.00999, result.priceUsd, 0.00001)
        assertEquals(1.1, result.change24h, 0.001)
        assertTrue("Source should indicate cached data", result.source.contains("cached"))
        assertEquals(1_700_000_000_000L, result.updatedAt)
        coVerify(exactly = 0) { mockDao.insert(any()) }
    }

    // ------------------------------------------------------------------
    // Extra: Both APIs fail AND no cache — returns "unavailable"
    // ------------------------------------------------------------------

    @Test
    fun `fetchPrice returns unavailable when both APIs fail and no cache`() = runTest {
        val fetcher = HttpFetcher { throw Exception("Network unavailable") }
        priceProvider = makeProvider(fetcher)
        coEvery { mockDao.getPrice("USD") } returns null

        val result = priceProvider.fetchPrice("USD")

        assertEquals(0.0, result.priceUsd, 0.0)
        assertEquals(0.0, result.change24h, 0.0)
        assertEquals("unavailable", result.source)
    }

    // ------------------------------------------------------------------
    // Extra: Cached entity is stored with correct fields
    // ------------------------------------------------------------------

    @Test
    fun `fetchPrice caches result with correct currency key`() = runTest {
        val fetcher = HttpFetcher { coinGeckoJson }
        priceProvider = makeProvider(fetcher)

        val insertSlot = slot<PriceCacheEntity>()
        coEvery { mockDao.insert(capture(insertSlot)) } just Runs

        priceProvider.fetchPrice("USD")

        assertEquals("USD", insertSlot.captured.currency)
        assertEquals("coingecko", insertSlot.captured.source)
        assertEquals(0.01234, insertSlot.captured.pricePerDgb, 0.00001)
    }

    // ------------------------------------------------------------------
    // Owner decisions 2026-11-09 (both apps): maximum age, off switch, reuse
    // ------------------------------------------------------------------

    @Test
    fun `a cached price older than an hour is unavailable`() = runTest {
        val fetcher = HttpFetcher { throw Exception("Network unavailable") }
        coEvery { mockDao.getPrice("USD") } returns cachedAt(now)

        val exactlyAnHour = makeProvider(fetcher, nowMs = now + PriceProvider.MAX_AGE_MS).fetchPrice("USD")
        assertEquals("an hour old is still usable", "coingecko (cached)", exactlyAnHour.source)

        val older = makeProvider(fetcher, nowMs = now + PriceProvider.MAX_AGE_MS + 1).fetchPrice("USD")
        assertEquals("unavailable", older.source)
        assertEquals(0.0, older.priceUsd, 0.0)
        assertEquals(3_600_000L, PriceProvider.MAX_AGE_MS)
    }

    @Test
    fun `a cached price dated in the future is ignored`() = runTest {
        val fetcher = HttpFetcher { throw Exception("Network unavailable") }
        coEvery { mockDao.getPrice("USD") } returns cachedAt(now + 1)

        val result = makeProvider(fetcher).fetchPrice("USD")
        assertEquals("a price from the future (the clock was set back) is not trusted", "unavailable", result.source)

        val reused = makeProvider(CountingFetcher { coinGeckoJson }).currentPrice("USD")
        assertEquals("nor reused", "coingecko", reused.source)
        assertEquals(0.01234, reused.priceUsd, 0.00001)
    }

    @Test
    fun `off fetches nothing, caches nothing and answers unavailable`() = runTest {
        val fetcher = CountingFetcher { coinGeckoJson }
        coEvery { mockDao.getPrice("USD") } returns cachedAt(now)
        val provider = makeProvider(fetcher, enabled = { false })

        val sources = listOf(
            provider.fetchPrice("USD"),
            provider.currentPrice("USD"),
            provider.fetchPrice("USD", app.aroundtheblock.wallet.core.model.PriceSource.API),
            provider.fetchPrice("USD", app.aroundtheblock.wallet.core.model.PriceSource.CACHED),
        ).map { it.source }

        assertEquals(List(4) { "unavailable" }, sources)
        assertEquals("off: no price request at all", emptyList<String>(), fetcher.urls)
        coVerify(exactly = 0) { mockDao.insert(any()) }
    }

    @Test
    fun `turned off while a request is out, a fetch neither moves on nor writes the cache`() = runTest {
        var on = true
        val fetcher = CountingFetcher { url ->
            on = false // the switch goes off while CoinGecko's request is out
            if (url.contains("coingecko")) throw Exception("CoinGecko unreachable") else binanceJson
        }
        coEvery { mockDao.getPrice("USD") } returns cachedAt(now)

        val result = makeProvider(fetcher, enabled = { on }).fetchPrice("USD")

        assertEquals("unavailable", result.source)
        assertEquals("no fall-through to Binance", 1, fetcher.urls.size)
        coVerify(exactly = 0) { mockDao.insert(any()) }

        on = true
        val late = CountingFetcher { on = false; coinGeckoJson }
        makeProvider(late, enabled = { on }).fetchPrice("USD")
        coVerify(exactly = 0) { mockDao.insert(any()) }
    }

    @Test
    fun `a price fetched within a minute is reused instead of fetched again`() = runTest {
        val fetcher = CountingFetcher { coinGeckoJson }
        coEvery { mockDao.getPrice("USD") } returns cachedAt(now - PriceProvider.REUSE_MS, source = "binance")

        val reused = makeProvider(fetcher).currentPrice("USD")
        assertEquals(0.00999, reused.priceUsd, 0.00001)
        assertEquals("binance", reused.source)
        assertEquals("a price a minute old is reused", emptyList<String>(), fetcher.urls)

        val fetched = makeProvider(fetcher, nowMs = now + 1).currentPrice("USD")
        assertEquals("older than the window: fetched again", "coingecko", fetched.source)
        assertEquals(1, fetcher.urls.size)
        assertEquals(60_000L, PriceProvider.REUSE_MS)
    }

    @Test
    fun `a price held in memory goes stale after an hour`() {
        val p = PriceData(0.01, 0.0, "coingecko", now)
        assertTrue(PriceProvider.isFresh(p, now))
        assertTrue(PriceProvider.isFresh(p, now + PriceProvider.MAX_AGE_MS))
        assertFalse(PriceProvider.isFresh(p, now + PriceProvider.MAX_AGE_MS + 1))
        assertFalse("dated in the future", PriceProvider.isFresh(p, now - 1))
        assertFalse(PriceProvider.isFresh(PriceData(0.0, 0.0, "unavailable", now), now))
        assertFalse(PriceProvider.isFresh(p.copy(priceUsd = 0.0), now))
    }
}
