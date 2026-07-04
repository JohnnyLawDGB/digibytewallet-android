package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [parseSeederServicesHex] — the seeder `services_hex`
 * parser threaded through injectPeerByIp so BIP157/158 filter peers are tagged
 * with SERVICES_NODE_COMPACT_FILTERS (0x40) and the wallet holds >=2 of them.
 *
 * Regression guard for the filter-continuity wedge: the parsers used to discard
 * services_hex, so no injected peer ever carried 0x40 and the native filter-first
 * selection could not fire — the wallet held ~1 filter peer by chance.
 */
class PeerServicesPolicyTest {

    @Test
    fun `filter peer 0x44d yields the compact-filter bit`() {
        val services = parseSeederServicesHex("0x44d")
        assertEquals(0x44dL, services)
        assertTrue(
            "0x44d must carry SERVICES_NODE_COMPACT_FILTERS (0x40)",
            services and SERVICES_NODE_COMPACT_FILTERS == SERVICES_NODE_COMPACT_FILTERS,
        )
    }

    @Test
    fun `bare hex without 0x prefix parses identically`() {
        assertEquals(0x44dL, parseSeederServicesHex("44d"))
    }

    @Test
    fun `uppercase prefix and surrounding whitespace are tolerated`() {
        assertEquals(0x44dL, parseSeederServicesHex("  0X44D  "))
    }

    @Test
    fun `bloom-only peer 0x40d does not carry the filter bit`() {
        // NODE_NETWORK|NODE_BLOOM|NODE_WITNESS|NODE_NETWORK_LIMITED, no 0x40.
        val services = parseSeederServicesHex("0x40d")
        assertEquals(0x40dL, services)
        assertEquals(0L, services and SERVICES_NODE_COMPACT_FILTERS)
    }

    @Test
    fun `absent or malformed services_hex falls back to 0 (native bloom default)`() {
        assertEquals(0L, parseSeederServicesHex(null))
        assertEquals(0L, parseSeederServicesHex(""))
        assertEquals(0L, parseSeederServicesHex("   "))
        assertEquals(0L, parseSeederServicesHex("not-hex"))
        assertEquals(0L, parseSeederServicesHex("0x"))
    }
}
