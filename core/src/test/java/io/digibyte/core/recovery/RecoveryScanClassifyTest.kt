package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryScanClassifyTest {
    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }

    @Test
    fun classify_marksNonNativeWithFunds() = runBlocking {
        val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val utxo = UtxoEntry("aa".repeat(32), 0, 424_797_024L, addr, 100L, "76a914aa88ac")
        val source = FakeUtxoSource(mapOf(addr to ReconcileResult(listOf(utxo), emptyMap(), 200L)))
        val service = RecoveryScanService(source)

        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0))),
        )
        assertEquals(1, done.nonNativeWithFunds.size)
        assertEquals(424_797_024L, done.nonNativeWithFunds[0].totalSat)
    }

    @Test
    fun classify_backendDown_flagsUnreachable() = runBlocking {
        val source = FakeUtxoSource(emptyMap(), reachable = false)
        val service = RecoveryScanService(source)
        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(DerivedAddress("DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk", chain = 0, index = 0))),
        )
        assertTrue(done.allBackendUnreachable)
    }

    @Test
    fun classify_repeatSameDerivedSet_hitsBackendOnce() = runBlocking {
        val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val utxo = UtxoEntry("aa".repeat(32), 0, 100_000L, addr, 100L, "76a914aa88ac")
        val source = FakeUtxoSource(mapOf(addr to ReconcileResult(listOf(utxo), emptyMap(), 200L)))
        val service = RecoveryScanService(source)

        // Two structurally-equal derived sets — the second models
        // RecoverFundsViewModel.classify re-running after the onboarding scan.
        val d1 = mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0)))
        val d2 = mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0)))

        val first = service.classifyDerived(d1)
        val second = service.classifyDerived(d2)

        assertEquals(1, source.fetchCount) // second call served from cache
        assertEquals(first.totalBalanceSat, second.totalBalanceSat)
    }
}
