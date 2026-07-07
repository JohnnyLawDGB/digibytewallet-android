package io.digibyte.core.digidollar

import io.digibyte.core.HttpFetcher
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #5 acceptance criteria, client half (the digiscope.me endpoint is the
 * server half of the same issue):
 *
 * - mocked-HTTP parsing to domain values; malformed/error → safe defaults
 *   (gate closed, Mint blocked)
 * - divergence-guard decision table as pure logic
 * - gate behavior incl. last-known-state on API outage with staleness
 * - endpoint override honored
 * - no wallet-derived data in any request (recorded URLs)
 */
class DigiDollarStatusTest {

    private fun fetcherReturning(body: String, urls: MutableList<String> = mutableListOf()) =
        HttpFetcher { url ->
            urls.add(url)
            body
        }

    private val healthy = """
        {"deployment":"active","priceMicroUsd":13420,
         "priceUpdatedAt":1730000000,"dcaMultiplierBps":10000}
    """.trimIndent()

    // ---- parsing ----

    @Test
    fun `well-formed response parses to domain values`() = runTest {
        val client = DigiDollarStatusClient(fetcherReturning(healthy))

        val status = client.fetchStatus(testnet = true)!!

        assertEquals(DigiDollarDeployment.ACTIVE, status.deployment)
        assertEquals(13_420L, status.priceMicroUsd)
        assertEquals(1_730_000_000_000L, status.priceUpdatedAtMs)
        assertEquals(10_000L, status.dcaMultiplierBps)
    }

    @Test
    fun `non-active and unknown deployment states parse closed`() = runTest {
        for (raw in listOf("started", "locked_in", "defined", "failed", "garbage")) {
            val body = healthy.replace("active", raw)
            val status = DigiDollarStatusClient(fetcherReturning(body)).fetchStatus(true)!!
            assertEquals(raw, DigiDollarDeployment.INACTIVE, status.deployment)
        }
    }

    @Test
    fun `malformed and error responses degrade to null`() = runTest {
        assertNull(DigiDollarStatusClient(fetcherReturning("not json")).fetchStatus(true))
        assertNull(DigiDollarStatusClient(fetcherReturning("{}")).fetchStatus(true))
        assertNull(
            DigiDollarStatusClient(HttpFetcher { throw IOException("HTTP 500") })
                .fetchStatus(true),
        )
        // Negative price is not a usable oracle value.
        assertNull(
            DigiDollarStatusClient(
                fetcherReturning(healthy.replace("13420", "-1")),
            ).fetchStatus(true),
        )
    }

    // ---- endpoint override + request hygiene ----

    @Test
    fun `endpoint override is honored and requests carry no wallet data`() = runTest {
        val urls = mutableListOf<String>()
        val client = DigiDollarStatusClient(
            fetcherReturning(healthy, urls),
            baseUrl = "https://my-node.example:8443",
        )

        client.fetchStatus(testnet = true)
        client.fetchStatus(testnet = false)

        assertEquals(
            listOf(
                "https://my-node.example:8443/api/digidollar/status?network=testnet",
                "https://my-node.example:8443/api/digidollar/status?network=mainnet",
            ),
            urls,
        )
    }

    // ---- divergence guard decision table ----

    @Test
    fun `divergence guard decision table`() {
        val oracle = 13_420L // micro-USD = $0.01342
        fun check(independentUsd: Double?) =
            DivergenceGuard.check(oracleMicroUsd = oracle, independentUsd = independentUsd)

        // Identical prices → allow.
        assertTrue(check(0.01342) is DivergenceGuard.Decision.Allow)
        // Within the 10% default (9% above).
        assertTrue(check(0.01342 * 1.09) is DivergenceGuard.Decision.Allow)
        // Beyond threshold in either direction → block with explanation.
        // (Divergence is measured relative to the independent price.)
        val high = check(0.01342 * 1.15)
        assertTrue(high is DivergenceGuard.Decision.Block)
        assertTrue((high as DivergenceGuard.Decision.Block).reason.contains("%"))
        assertTrue(check(0.01342 * 0.85) is DivergenceGuard.Decision.Block)
        // Missing either price → block.
        assertTrue(check(null) is DivergenceGuard.Decision.Block)
        assertTrue(check(0.0) is DivergenceGuard.Decision.Block)
        assertTrue(
            DivergenceGuard.check(oracleMicroUsd = null, independentUsd = 0.01342)
                is DivergenceGuard.Decision.Block,
        )
    }

    // ---- softfork gate ----

    @Test
    fun `gate opens only on active status and survives outages within the TTL`() {
        val store = InMemoryGateStore()
        val gate = DigiDollarGate(store)
        val t0 = 1_000_000_000_000L

        // Never seen active → closed, even before any fetch.
        assertFalse(gate.isOpen(nowMs = t0))

        // Inactive result → closed.
        gate.record(DigiDollarDeployment.INACTIVE, nowMs = t0)
        assertFalse(gate.isOpen(nowMs = t0))

        // Active result → open, and last-known state persists across an outage.
        gate.record(DigiDollarDeployment.ACTIVE, nowMs = t0)
        assertTrue(gate.isOpen(nowMs = t0))
        assertTrue(gate.isOpen(nowMs = t0 + DigiDollarGate.LAST_KNOWN_TTL_MS - 1))

        // Stale beyond the TTL with no fresh confirmation → closed again.
        assertFalse(gate.isOpen(nowMs = t0 + DigiDollarGate.LAST_KNOWN_TTL_MS + 1))

        // A softfork cannot deactivate: an explicit INACTIVE after ACTIVE is
        // an API anomaly, but the gate follows the API (safe direction).
        gate.record(DigiDollarDeployment.INACTIVE, nowMs = t0)
        assertFalse(gate.isOpen(nowMs = t0))
    }

    @Test
    fun `gate state round-trips through the store`() {
        val store = InMemoryGateStore()
        val t0 = 1_000_000_000_000L
        DigiDollarGate(store).record(DigiDollarDeployment.ACTIVE, nowMs = t0)

        // A fresh gate over the same store (process restart) sees the cache.
        assertTrue(DigiDollarGate(store).isOpen(nowMs = t0 + 1))
    }

    // ---- OraclePriceProvider bridge ----

    @Test
    fun `oracle price provider exposes the price in USD for the divergence cross-check`() = runTest {
        val provider = DigiDollarStatusClient(fetcherReturning(healthy))

        val price = provider.fetchOraclePrice()!!

        assertEquals(0.01342, price.priceUsd, 1e-9)
        assertEquals("oracle", price.source)
        assertEquals(1_730_000_000_000L, price.updatedAt)
    }

    private class InMemoryGateStore : DigiDollarGate.Store {
        private val map = mutableMapOf<String, Long>()
        override fun get(key: String): Long? = map[key]
        override fun put(key: String, value: Long) { map[key] = value }
    }
}
