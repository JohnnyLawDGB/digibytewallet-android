package io.digibyte.digidollar

import kotlin.test.assertEquals
import org.junit.Test

class LockTierTest {

    // Consensus table from DigiByte Core v9.26.4 (consensus/digidollar.h):
    // index = the tier index used on-wire (RPC lock_tier, mint OP_RETURN).
    @Test
    fun `tier table matches the ten consensus tiers`() {
        val expected = listOf(
            // lockBlocks, ratioPercent (BLOCKS_PER_DAY = 5760, 15s blocks)
            Triple(0, 240, 1000),
            Triple(1, 30 * 5760, 500),
            Triple(2, 90 * 5760, 400),
            Triple(3, 180 * 5760, 350),
            Triple(4, 365 * 5760, 300),
            Triple(5, 2 * 365 * 5760, 275),
            Triple(6, 3 * 365 * 5760, 250),
            Triple(7, 5 * 365 * 5760, 225),
            Triple(8, 7 * 365 * 5760, 212),
            Triple(9, 10 * 365 * 5760, 200),
        )
        assertEquals(10, LockTiers.ALL.size)
        for ((index, lockBlocks, ratioPercent) in expected) {
            val tier = LockTiers.byIndex(index)
            assertEquals(index, tier.index)
            assertEquals(lockBlocks, tier.lockBlocks)
            assertEquals(ratioPercent, tier.ratioPercent)
        }
    }
}
