package io.digibyte.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetMaintenanceGateTest {
    @Test fun open_only_when_all_conditions_hold() {
        assertTrue(assetPruneGateOpen(true, 1, 1.0f, true))
    }
    @Test fun closed_if_any_condition_fails() {
        assertFalse(assetPruneGateOpen(false, 1, 1.0f, true))
        assertFalse(assetPruneGateOpen(true, 0, 1.0f, true))
        assertFalse(assetPruneGateOpen(true, 1, 0.99f, true))
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, false))
    }
}
