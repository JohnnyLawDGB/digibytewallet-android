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
    fun classify_partialOutage_notCached_retryReQueries() = runBlocking {
        // Finding 3: one profile reconciles, another's backend call fails.
        // allBackendUnreachable is false (not a TOTAL outage), but the result
        // must NOT be cached — a retry has to re-query so the profile that
        // failed the first time can still surface funds.
        val goodAddr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val poisonAddr = "DPhx7bckLtP2RVpEwUJvkFktjhLfMKz9aB"
        val utxo = UtxoEntry("aa".repeat(32), 0, 100_000L, goodAddr, 100L, "76a914aa88ac")

        // Source that returns null (unreachable) whenever poisonAddr is queried,
        // otherwise a normal result.
        val source = object : UtxoSource {
            var fetchCount = 0
                private set
            override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? {
                fetchCount++
                if (poisonAddr in addresses) return null
                val utxos = addresses.mapNotNull { if (it == goodAddr) utxo else null }
                return ReconcileResult(utxos, emptyMap(), 200L)
            }
        }
        val profiles = DerivationProfile.BUILT_INS
        val pGood = profiles[0]
        val pBad = profiles.first { it != pGood }
        val service = RecoveryScanService(source)

        val derived = mapOf(
            pGood to listOf(DerivedAddress(goodAddr, chain = 0, index = 0)),
            pBad to listOf(DerivedAddress(poisonAddr, chain = 0, index = 0)),
        )

        val first = service.classifyDerived(derived)
        assertFalse(first.allBackendUnreachable) // partial, not total
        val afterFirst = source.fetchCount

        // Second, structurally-identical classify: because the first was a
        // partial outage it was NOT cached, so this re-queries the backend.
        service.classifyDerived(derived)
        assertTrue("partial-outage result must not be cached", source.fetchCount > afterFirst)
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
    fun bip49Profile_isDetectedAndNoLongerRefused() = runBlocking {
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

        // sweep: BIP49 used to be short-circuited into "manual recovery required" BEFORE any
        // signing was attempted — it never touched JNI, which is the only reason this test
        // could drive sweepFromSeed on a JVM at all. BRTransactionSign has since grown a
        // P2SH-P2WPKH branch (proven in bip49_sign_kat, where the bytes are), so the profile
        // now takes the ORDINARY path.
        //
        // The seam that proves it: reaching the native signer without a library loaded raises
        // UnsatisfiedLinkError. Getting that error is the positive observation — it can only
        // happen if the pre-JNI refusal is gone. A returned "manual recovery" outcome, or any
        // outcome at all, would mean the short-circuit is still there.
        val sweeper = LegacySweepService(mockk(relaxed = true), mockk(relaxed = true), ForeignUtxoAssetClassifier(
            // These tests are not about assets; a classifier that answers "plain, and I
            // could tell" keeps them testing what they test rather than the new guard.
            fetchRawTx = { byteArrayOf(1) },
            isAssetTx = { false },
        ))
        var reachedNative = false
        try {
            sweeper.sweepFromSeed(
                seedBytes = ByteArray(64),
                nonNativeResults = done.nonNativeWithFunds,
                destAddress = "dgb1qdummydestinationplaceholderaddr",
            )
        } catch (e: UnsatisfiedLinkError) {
            reachedNative = true
        } catch (e: NoClassDefFoundError) {
            // Same seam, later in the JVM: once any earlier test has tripped NativeBridge's
            // static initializer (System.loadLibrary with no library), the class is marked
            // erroneous and every later touch throws NoClassDefFoundError with
            // ExceptionInInitializerError as its cause — not UnsatisfiedLinkError. Test order
            // differs between Gradle workers, which is why this passed locally and failed on
            // CI (develop @ 01e6c9a5). Either error is the native signer being reached.
            reachedNative = true
        } catch (e: ExceptionInInitializerError) {
            reachedNative = true
        }
        assertTrue(
            "BIP49 must reach the signer instead of being turned away before it is attempted",
            reachedNative,
        )
    }

    @Test
    fun allWithFunds_includesNativeAndNonNative_excludesEmpty() {
        // Two funded profiles (one native BIP84, one legacy) + one empty.
        val native = RecoveryScanService.ProfileResult(
            profile = DerivationProfile.BUILT_INS.first { it.isNative },
            addresses = listOf("dgb1qnative"),
            derivedAddresses = emptyList(),
            utxos = listOf(utxo("dgb1qnative", 300_000_000L)),
            rawTxs = emptyMap(),
        )
        val legacy = RecoveryScanService.ProfileResult(
            profile = DerivationProfile.BUILT_INS.first { !it.isNative && it.addressFormat == 0 },
            addresses = listOf("DLegacy"),
            derivedAddresses = emptyList(),
            utxos = listOf(utxo("DLegacy", 100_000_000L)),
            rawTxs = emptyMap(),
        )
        val empty = RecoveryScanService.ProfileResult(
            profile = DerivationProfile.BUILT_INS.first { !it.isNative && it.label.contains("BIP44 DGB") },
            addresses = listOf("DEmpty"),
            derivedAddresses = emptyList(),
            utxos = emptyList(),
            rawTxs = emptyMap(),
        )
        val done = RecoveryScanService.State.Done(listOf(native, legacy, empty))

        assertEquals(2, done.allWithFunds.size)
        assertTrue(done.allWithFunds.any { it.profile.isNative })          // native INCLUDED
        assertTrue(done.allWithFunds.any { !it.profile.isNative })
        assertFalse(done.allWithFunds.any { it.utxos.isEmpty() })          // empty EXCLUDED
        // Regression: nonNativeWithFunds still excludes native.
        assertTrue(done.nonNativeWithFunds.none { it.profile.isNative })
    }

    private fun utxo(addr: String, sat: Long) = UtxoEntry(
        txid = "00", vout = 0, amountSatoshi = sat, address = addr,
        blockHeight = 0L, scriptPubKeyHex = "76a90088ac",
    )
}
