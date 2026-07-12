package io.digibyte.core.settings

import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomNodeTest {
    @Test fun bareHostGetsDefaultPort() {
        assertEquals(CustomNode("node.example.com", 12024), CustomNode.parse("node.example.com", 12024))
    }
    @Test fun hostWithPortParses() {
        assertEquals(CustomNode("10.0.0.5", 12099), CustomNode.parse("  10.0.0.5:12099 ", 12024))
    }
    @Test fun blankIsNull() { assertNull(CustomNode.parse("   ", 12024)) }
    @Test fun emptyHostIsNull() { assertNull(CustomNode.parse(":12024", 12024)) }
    @Test fun badPortIsNull() { assertNull(CustomNode.parse("host:notaport", 12024)) }
    @Test fun outOfRangePortIsNull() {
        assertNull(CustomNode.parse("host:0", 12024))
        assertNull(CustomNode.parse("host:70000", 12024))
    }
    @Test fun schemePrefixRejected() { assertNull(CustomNode.parse("http://host:12024", 12024)) }
    @Test fun ipv6Rejected() { assertNull(CustomNode.parse("2001:db8::1", 12024)) }
    @Test fun asHostPortRoundTrips() {
        assertEquals("host:12024", CustomNode("host", 12024).asHostPort())
    }
    @Test fun customNodeForcesCompactFiltersOnly() {
        assertEquals(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY,
            syncModeFor(NativeBridge.SyncMode.BOTH, customNodeEnabled = true, isTestnet = false))
    }
    @Test fun testnetForcesCompactFiltersOnly() {
        assertEquals(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY,
            syncModeFor(NativeBridge.SyncMode.BLOOM_ONLY, customNodeEnabled = false, isTestnet = true))
    }
    @Test fun defaultIsAlsoCompactFiltersOnly() {
        // CF-only decision (v3.10.21 removed the Sync Mode toggle): syncModeFor
        // collapses EVERY case to COMPACT_FILTERS_ONLY — bloom is gone from the
        // UX. Even the plain BOTH pref, no custom node, mainnet resolves to CF-only.
        assertEquals(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY,
            syncModeFor(NativeBridge.SyncMode.BOTH, customNodeEnabled = false, isTestnet = false))
    }
}
