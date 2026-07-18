package io.digibyte.core.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure decision at the heart of the sovereign asset spent-reconcile
 * ([reconcileAssetSpentFromNative]): map the native outpoint spent-state
 * tri-state (0 SPENT / 1 HELD / -1 UNDETECTED) to a new spent flag, WITHOUT
 * ever marking a not-yet-detected holding spent.
 */
class AssetSpentReconcileTest {

    @Test
    fun spent_marksSpent() {
        assertEquals(true, decideAssetSpent(0))
    }

    @Test
    fun held_marksUnspent() {
        assertEquals(false, decideAssetSpent(1))
    }

    @Test
    fun undetected_leavesRowUnchanged() {
        // Backend surfaced a row the SPV sync hasn't reached — must NOT be
        // marked spent, or a real holding would flicker out mid-sync.
        assertNull(decideAssetSpent(-1))
    }

    @Test
    fun unknownState_leavesRowUnchanged() {
        // Defensive: any unexpected value is treated as "leave alone".
        assertNull(decideAssetSpent(99))
    }
}
