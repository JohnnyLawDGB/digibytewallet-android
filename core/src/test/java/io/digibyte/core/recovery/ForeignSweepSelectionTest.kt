package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignSweepSelectionTest {
    private fun res(isNative: Boolean, fmt: Int, sat: Long) =
        RecoveryScanService.ProfileResult(
            profile = DerivationProfile.BUILT_INS.first { it.isNative == isNative && it.addressFormat == fmt },
            addresses = listOf("a"), derivedAddresses = emptyList(),
            utxos = if (sat > 0) listOf(UtxoEntry("00", 0, sat, "a", 0L, "76a90088ac")) else emptyList(),
            rawTxs = emptyMap(),
        )

    @Test
    fun foreign_includesNative_own_excludesNative() {
        val done = RecoveryScanService.State.Done(
            listOf(res(true, 1, 200_000_000L), res(false, 0, 100_000_000L))
        )
        // Foreign: native + legacy both swept.
        assertEquals(2, sweepSet(done, isForeign = true).size)
        assertTrue(sweepSet(done, isForeign = true).any { it.profile.isNative })
        // Own: native left in place.
        assertEquals(1, sweepSet(done, isForeign = false).size)
        assertTrue(sweepSet(done, isForeign = false).none { it.profile.isNative })
    }
}
