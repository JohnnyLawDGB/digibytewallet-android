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
 * The plain-DGB recovery scan's loop around the core's gap rule (4.1.3 G1): it asks the rule with
 * each chain's highest used index (counting an address paid but since emptied), derives a path
 * again at the windows the rule gives while the rule says to ask again, keeps the answer finished
 * or capped as the rule says, and reads an outcome it does not know as capped.
 *
 * The rule itself (how far a window grows, when the cap stops it, the round bound) is the core's,
 * BRRecoveryGapWindow.h; its values, including every case this test held while the rule was
 * Kotlin, are checked by the host KAT recovery_gap_window_kat. NativeBridge cannot load on the host
 * JVM, so here the rule is [Scripted]: a stand-in whose answers each test chooses, deliberately not
 * the core's arithmetic.
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

    /** A rule whose answers the test scripts, recording what the scan asked it. */
    private class Scripted(
        val bound: Int = 20,
        val nextFn: (chain: Int, count: Int, used: Int?) -> Int = { _, count, _ -> count },
        val outcomeFn: (grew: Boolean, round: Int, bound: Int) -> Int =
            { grew, round, b -> if (grew && round < b) RecoveryGapRule.ASK_AGAIN else RecoveryGapRule.FINISHED },
    ) : RecoveryGapRule {
        val nextCalls = mutableListOf<Triple<Int, Int, Int?>>()
        val outcomeCalls = mutableListOf<List<Int?>>()
        var boundAskedWith: Pair<Int, Int>? = null

        override fun next(chain: Int, count: Int, highestUsed: Int?): Int {
            nextCalls += Triple(chain, count, highestUsed)
            return nextFn.invoke(chain, count, highestUsed)
        }

        override fun outcome(
            externalCount: Int, internalCount: Int, nextExternal: Int, nextInternal: Int,
            usedExternal: Int?, usedInternal: Int?, round: Int, bound: Int,
        ): Int {
            outcomeCalls += listOf(externalCount, internalCount, nextExternal, nextInternal, usedExternal, usedInternal, round, bound)
            return outcomeFn.invoke(nextExternal != externalCount || nextInternal != internalCount, round, bound)
        }

        override fun roundBound(startExternal: Int, startInternal: Int): Int {
            boundAskedWith = startExternal to startInternal
            return bound
        }
    }

    private fun service(byAddress: Map<String, ReconcileResult>, rule: RecoveryGapRule) =
        FakeUtxoSource(byAddress).let { it to RecoveryScanService(it, listOf(profile), rule) }

    @Test fun aCoinPastTheFirstWindowIsFoundOnceTheRuleWidensIt() = runBlocking {
        // 150 is inside the 200 window, 250 is past it. The rule widens external to 400 once.
        val rule = Scripted(nextFn = { chain, count, used -> if (chain == 0 && count == 200 && used != null) 400 else count })
        val (src, svc) = service(mapOf(coinAt(0, 150), coinAt(0, 250)), rule)
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertEquals(2_000L, done.totalBalanceSat)
        assertFalse(done.results.single().capped)
        assertTrue(done.cappedProfileLabels.isEmpty())
        assertEquals(2, src.fetchCount)
        // Asked with each chain's highest used index: 150 in the first window, 250 in the second;
        // nothing on the internal chain.
        assertEquals(listOf(Triple(0, 200, 150), Triple(1, 100, null), Triple(0, 400, 250), Triple(1, 100, null)), rule.nextCalls)
        assertEquals(200 to 100, rule.boundAskedWith)
    }

    @Test fun anEmptyPathIsAskedOnce() = runBlocking {
        val rule = Scripted()
        val (src, svc) = service(emptyMap(), rule)
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertEquals(1, src.fetchCount)
        assertEquals(0L, done.totalBalanceSat)
        assertFalse(done.results.single().capped)
        assertEquals(listOf(listOf<Int?>(200, 100, 200, 100, null, null, 1, 20)), rule.outcomeCalls)
    }

    @Test fun aPathTheRuleCallsCappedIsMarkedIncomplete() = runBlocking {
        val rule = Scripted(outcomeFn = { _, _, _ -> 2 /* BR_RECOVERY_GAP_CAPPED */ })
        val (_, svc) = service(mapOf(coinAt(0, 10)), rule)
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertTrue(done.results.single().capped)
        assertEquals(listOf(profile.label), done.cappedProfileLabels)
        assertEquals(1_000L, done.totalBalanceSat)
    }

    @Test fun anOutcomeTheScanDoesNotKnowIsReadAsCapped() = runBlocking {
        val rule = Scripted(outcomeFn = { _, _, _ -> 7 })
        val (_, svc) = service(emptyMap(), rule)
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertTrue(done.results.single().capped)
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
        // The rule widens only when something at 150 or above is used: 10 alone would not.
        val rule = Scripted(nextFn = { chain, count, used -> if (chain == 0 && count == 200 && (used ?: 0) >= 150) 300 else count })
        val (_, svc) = service(withTx, rule)
        val done = svc.classifyWidening(
            ::derive,
            outputScripts = { r -> if (r.rawTxs.isNotEmpty()) setOf("script-$paid") else emptySet() },
            scriptOf = { a -> "script-$a" },
        )
        assertEquals(Triple(0, 200, 190), rule.nextCalls.first())
        assertEquals(1_500L, done.totalBalanceSat)
    }

    @Test fun theRoundBoundComesFromTheRuleAndEachRoundIsNumbered() = runBlocking {
        // A rule that always grows: the scan asks again until the rule's bound, then keeps the
        // answer the rule gives at the bound (capped).
        val rule = Scripted(
            bound = 3,
            nextFn = { _, count, _ -> count + 10 },
            outcomeFn = { grew, round, b -> if (grew && round < b) RecoveryGapRule.ASK_AGAIN else 2 },
        )
        val (src, svc) = service(emptyMap(), rule)
        val done = svc.classifyWidening(::derive, outputScripts = { emptySet() }, scriptOf = { null })
        assertEquals(3, src.fetchCount)
        assertEquals(listOf(1, 2, 3), rule.outcomeCalls.map { it[6] })
        assertTrue(rule.outcomeCalls.all { it[7] == 3 })
        // Each round derives at the windows the previous round's rule answer gave.
        assertEquals(listOf(200, 210, 220), rule.outcomeCalls.map { it[0] })
        assertEquals(listOf(100, 110, 120), rule.outcomeCalls.map { it[1] })
        assertTrue(done.results.single().capped)
        assertEquals(220, done.results.single().derivedAddresses.count { it.chain == 0 })
    }

    @Test fun highestUsedIsPerChain() {
        val svc = RecoveryScanService(FakeUtxoSource(emptyMap()), listOf(profile), Scripted())
        assertNull(svc.highestUsed(listOf(DerivedAddress("a", 0, 3)), setOf("a"), 1))
        assertEquals(7, svc.highestUsed(listOf(DerivedAddress("a", 0, 3), DerivedAddress("b", 0, 7), DerivedAddress("c", 1, 9)), setOf("a", "b", "c"), 0))
    }
}
