package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.reconcile.RawTxEntry
import app.aroundtheblock.wallet.core.reconcile.ReconcileResult
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain-DGB recovery scan widens each path's window while its far end is in use, and says so
 * when its cap stops it (4.1.3 G1). A fixed 200/100 window lost every coin past it and still reported
 * a finished answer.
 */
class RecoveryGapWidenTest {
    private val profile = DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }
        .copy(gapExternal = 200, gapInternal = 100)

    private fun addr(chain: Int, i: Int) = "addr-$chain-$i"

    /** Derives synthetic addresses at each profile's own gaps, as deriveAllProfiles does. */
    private fun derive(ps: List<DerivationProfile>) = ps.associateWith { p ->
        (0 until p.gapExternal).map { DerivedAddress(addr(0, it), 0, it) } +
            (0 until p.gapInternal).map { DerivedAddress(addr(1, it), 1, it) }
    }

    private fun coinAt(chain: Int, i: Int, sat: Long = 1_000L): Pair<String, ReconcileResult> {
        val a = addr(chain, i)
        return a to ReconcileResult(listOf(UtxoEntry("%064x".format(chain * 10_000 + i), 0, sat, a, 100L, "00")), emptyMap(), 200L)
    }

    private fun service(byAddress: Map<String, ReconcileResult>) =
        FakeUtxoSource(byAddress).let { it to RecoveryScanService(it, listOf(profile)) }

    @Test fun aCoinPastTheFixedWindowIsFound() = runBlocking {
        // 150 is inside the old window, 250 is past it: the old scan reported 1 000 sat, complete.
        val (_, svc) = service(mapOf(coinAt(0, 150), coinAt(0, 250)))
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertEquals(2_000L, done.totalBalanceSat)
        assertFalse(done.results.single().capped)
        assertTrue(done.cappedProfileLabels.isEmpty())
    }

    @Test fun anEmptyPathIsAskedOnce() = runBlocking {
        val (src, svc) = service(emptyMap())
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertEquals(1, src.fetchCount)
        assertEquals(0L, done.totalBalanceSat)
        assertFalse(done.results.single().capped)
    }

    @Test fun aPathStillInUseAtTheCapIsMarkedIncomplete() = runBlocking {
        // A coin every 90 indices: each window's far end is always in use, up to the cap.
        val coins = (0 until RecoveryGapWindow.MAX_EXTERNAL step 90).associate { coinAt(0, it) }
        val (_, svc) = service(coins)
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertTrue(done.results.single().capped)
        assertEquals(listOf(profile.label), done.cappedProfileLabels)
        assertEquals(RecoveryGapWindow.MAX_EXTERNAL, done.results.single().derivedAddresses.count { it.chain == 0 })
    }

    @Test fun anAddressPaidButEmptiedCountsAsUsed() = runBlocking {
        // 190 holds nothing now, but a returned transaction paid it; 280 holds a coin past the window.
        val paid = addr(0, 190)
        val withTx = mapOf(
            addr(0, 10) to ReconcileResult(
                listOf(UtxoEntry("%064x".format(10), 0, 500L, addr(0, 10), 100L, "00")),
                mapOf("t1" to RawTxEntry(hex = "00", blockHeight = 100L, blockTime = 0L)),
                200L,
            ),
            coinAt(0, 280),
        )
        val (_, svc) = service(withTx)
        val done = svc.classifyWidening(
            ::derive,
            outputScripts = { r -> if (r.rawTxs.isNotEmpty()) setOf("script-$paid") else emptySet() },
            scriptOf = { a -> "script-$a" },
        )
        assertEquals(1_500L, done.totalBalanceSat)
    }

    @Test fun aLookupThatAlwaysAnswersUsedIsBoundedAndAskedOnlyForGrownPaths() = runBlocking {
        // Every address "used": the window must reach the cap in whole-gap steps, not one per address.
        val everything = object : UtxoSource {
            var calls = 0
            override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult {
                calls++
                return ReconcileResult(addresses.map { UtxoEntry("%064x".format(it.hashCode().toLong() and 0xffffffffL), 0, 1L, it, 100L, "00") }, emptyMap(), 200L)
            }
        }
        val svc = RecoveryScanService(everything, listOf(profile))
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertTrue(done.results.single().capped)
        assertTrue("rounds ${everything.calls}", everything.calls <= RecoveryGapWindow.roundBound(100, 50, 1000))
    }

    @Test fun gapWindowArithmetic() {
        assertEquals(200, RecoveryGapWindow.next(200, null, 100, 2000))
        assertEquals(200, RecoveryGapWindow.next(200, 99, 100, 2000))
        assertEquals(351, RecoveryGapWindow.next(200, 250, 100, 2000))
        // a grown window grows by at least a whole gap, even when the use sits right at its end
        assertEquals(300, RecoveryGapWindow.next(200, 100, 100, 2000))
        assertEquals(2000, RecoveryGapWindow.next(1950, 1990, 100, 2000))
        assertTrue(RecoveryGapWindow.capped(1990, 100, 2000))
        assertFalse(RecoveryGapWindow.capped(1899, 100, 2000))
        assertNull(RecoveryGapWindow.highestUsed(listOf(DerivedAddress("a", 0, 3)), setOf("a"), 1))
    }
}
