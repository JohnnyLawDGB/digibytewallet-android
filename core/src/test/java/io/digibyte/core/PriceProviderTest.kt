package io.digibyte.core

import io.digibyte.core.db.dao.PriceCacheDao
import io.digibyte.core.db.entity.PriceCacheEntity
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

    private fun makeProvider(fetcher: HttpFetcher) =
        PriceProvider(mockDao, fetcher, UnconfinedTestDispatcher())

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
        priceProvider = makeProvider(fetcher)

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
}
