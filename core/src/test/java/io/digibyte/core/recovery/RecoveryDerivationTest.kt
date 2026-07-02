package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryDerivationTest {

    /** #3: dropping an empty middle slot must NOT shift a surviving address's
     *  (chain,index). The old code filtered empties, then indexed by position —
     *  so "addrE2" would have mapped to index 1 and "addrI0" to (chain 0, 2). */
    @Test
    fun mapDerived_droppedMiddleSlot_keepsTrueIndices() {
        // gapExternal = 3 → raw positions 0..2 are external, 3+ internal.
        val raw = arrayOf("addrE0", "", "addrE2", "addrI0")

        val derived = mapDerived(raw, gapExternal = 3)

        assertEquals(3, derived.size) // empty slot dropped
        assertEquals(DerivedAddress("addrE0", chain = 0, index = 0), derived[0])
        assertEquals(DerivedAddress("addrE2", chain = 0, index = 2), derived[1])
        assertEquals(DerivedAddress("addrI0", chain = 1, index = 0), derived[2])
    }
}
