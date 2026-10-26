package app.aroundtheblock.wallet.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetMaintenanceGateTest {
    private val tip = 24_200_000L
    private val atTip = tip + 1 // the scan frontier is the lowest height still needed

    @Test fun open_only_when_all_conditions_hold() {
        assertTrue(assetPruneGateOpen(true, 1, 1.0f, true, scanFrontier = atTip, headerTip = tip))
        assertTrue(assetPruneGateOpen(true, 1, 1.0f, true, scanFrontier = tip - 100, headerTip = tip))
    }
    @Test fun closed_if_any_condition_fails() {
        assertFalse(assetPruneGateOpen(false, 1, 1.0f, true, scanFrontier = atTip, headerTip = tip))
        assertFalse(assetPruneGateOpen(true, 0, 1.0f, true, scanFrontier = atTip, headerTip = tip))
        assertFalse(assetPruneGateOpen(true, 1, 0.99f, true, scanFrontier = atTip, headerTip = tip))
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, false, scanFrontier = atTip, headerTip = tip))
    }

    /** Headers at the tip and a session that once reported synced say nothing about the filter
     *  scan: during a rescan from a floor it is far behind, and native does not yet hold the
     *  history a prune would judge rows against. */
    @Test fun closed_while_the_filter_scan_is_behind_the_header_tip() {
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, true, scanFrontier = 19_000_000L, headerTip = tip))
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, true, scanFrontier = tip - 101, headerTip = tip))
    }

    @Test fun closed_when_the_scan_frontier_or_tip_is_unknown() {
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, true, scanFrontier = 0L, headerTip = tip))
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, true, scanFrontier = atTip, headerTip = 0L))
    }
}
