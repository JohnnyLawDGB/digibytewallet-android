package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult
import io.digibyte.core.reconcile.UtxoEntry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun classify_emptyUtxos_noFindings() = runBlocking {
        // Reachable backend, but the profile's address holds no UTXOs.
        val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val source = FakeUtxoSource(emptyMap()) // reachable defaults true; addr absent -> empty
        val service = RecoveryScanService(source)

        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0))),
        )

        // Nothing is sweepable and the total is zero...
        assertTrue(done.nonNativeWithFunds.isEmpty())
        assertEquals(0L, done.totalBalanceSat)
        // ...but "reachable, empty" must NOT masquerade as "backend down".
        assertFalse(done.allBackendUnreachable)
        assertEquals(1, done.results.size)
        assertTrue(done.results[0].reachableBackend)
    }

    @Test
    fun classify_multipleAddresses_sumsBalance() = runBlocking {
        // Three funded addresses under ONE profile -> one ProfileResult whose
        // totalSat is the exact sum (1.0 + 2.5 + 0.49 DGB = 3.99 DGB).
        val a1 = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val a2 = "DPhx7bckLtP2RVpEwUJvkFktjhLfMKz9aB"
        val a3 = "DQm5RhTZ4pW8sN3vXyC1gLbA9dK6eF2uHt"
        val u1 = UtxoEntry("aa".repeat(32), 0, 100_000_000L, a1, 100L, "76a914aa88ac")
        val u2 = UtxoEntry("bb".repeat(32), 1, 250_000_000L, a2, 101L, "76a914bb88ac")
        val u3 = UtxoEntry("cc".repeat(32), 0, 49_000_000L, a3, 102L, "76a914cc88ac")
        val source = FakeUtxoSource(
            mapOf(
                a1 to ReconcileResult(listOf(u1), emptyMap(), 200L),
                a2 to ReconcileResult(listOf(u2), emptyMap(), 200L),
                a3 to ReconcileResult(listOf(u3), emptyMap(), 200L),
            ),
        )
        val service = RecoveryScanService(source)

        val done = service.classifyDerived(
            mapOf(
                legacyProfile to listOf(
                    DerivedAddress(a1, chain = 0, index = 0),
                    DerivedAddress(a2, chain = 0, index = 1),
                    DerivedAddress(a3, chain = 0, index = 2),
                ),
            ),
        )

        assertEquals(1, done.results.size)
        assertEquals(3, done.results[0].utxos.size)
        assertEquals(399_000_000L, done.results[0].totalSat)
        assertEquals(399_000_000L, done.totalBalanceSat)
        assertEquals(1, done.nonNativeWithFunds.size)
    }

    @Test
    fun bip49Profile_isNotSweepable() = runBlocking {
        // A BIP49 (P2SH-P2WPKH, addressFormat==2) profile that DOES hold funds.
        val bip49 = DerivationProfile.BUILT_INS.first { it.addressFormat == 2 }
        val addr = "SXBip49TestKeyDoNotSendRealFunds123"
        val utxo = UtxoEntry("dd".repeat(32), 0, 12_345_000L, addr, 300L, "a914dd87")
        val source = FakeUtxoSource(
            mapOf(addr to ReconcileResult(listOf(utxo), emptyMap(), 400L)),
        )
        val service = RecoveryScanService(source)

        // classify: the funds are DETECTED, never silently dropped.
        val done = service.classifyDerived(
            mapOf(bip49 to listOf(DerivedAddress(addr, chain = 0, index = 0))),
        )
        assertEquals(1, done.results.size)
        assertEquals(12_345_000L, done.results[0].totalSat)
        assertEquals(1, done.nonNativeWithFunds.size)
        assertEquals(2, done.nonNativeWithFunds[0].profile.addressFormat)

        // sweep: BIP49 is deferred to manual recovery -> no tx built, no txid,
        // nothing swept, and the reason names manual recovery. It is NEVER a
        // silent skip or a success. Durability collaborators (outgoingTxStore,
        // walletTxPersister) are relaxed mocks -- the BIP49 branch
        // short-circuits before ever touching them, and seedBytes is unused
        // too (short-circuits before any JNI), so a zero buffer is fine.
        val result = LegacySweepService(mockk(relaxed = true), mockk(relaxed = true)).sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = done.nonNativeWithFunds,
            destAddress = "dgb1qdummydestinationplaceholderaddr",
        )
        assertEquals(1, result.outcomes.size)
        val outcome = result.outcomes[0]
        assertNull(outcome.txHex)
        assertNull(outcome.txid)
        assertEquals(0L, outcome.sweptSat)
        assertTrue(outcome.failureReason!!.contains("manual recovery", ignoreCase = true))
        assertFalse(result.allSubmitted)
    }
}
