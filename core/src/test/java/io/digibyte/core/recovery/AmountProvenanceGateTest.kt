package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug #2 defense (a)+(b): the legacy P2PKH sighash is amount-blind, so a
 * stale/under-reported amount still signs valid and burns the remainder to
 * fee. amountProvenanceGate is a pure pre-sign gate that refuses the whole
 * profile-sweep on an unreachable backend or any non-positive UTXO amount.
 * No JNI — runs under ./gradlew :core:testMainnetDebugUnitTest.
 *
 * Task 1B made LegacySweepService take (outgoingTxStore, walletTxPersister);
 * the gate never touches either (it short-circuits before sweepOneProfile),
 * so relaxed mocks keep this a pure-JVM test with zero durability side effects.
 */
class AmountProvenanceGateTest {
    private val service = LegacySweepService(mockk(relaxed = true), mockk(relaxed = true), ForeignUtxoAssetClassifier(
            // These tests are not about assets; a classifier that answers "plain, and I
            // could tell" keeps them testing what they test rather than the new guard.
            fetchRawTx = { byteArrayOf(1) },
            isAssetTx = { false },
        ))
    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }
    private val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"

    private fun result(utxos: List<UtxoEntry>, reachable: Boolean = true) =
        RecoveryScanService.ProfileResult(
            profile = legacyProfile,
            addresses = listOf(addr),
            derivedAddresses = emptyList(),
            utxos = utxos,
            rawTxs = emptyMap(),
            reachableBackend = reachable,
        )

    private fun utxo(amount: Long) =
        UtxoEntry("aa".repeat(32), 0, amount, addr, 100L, "76a914${"11".repeat(20)}88ac")

    @Test
    fun gate_positiveAmounts_reachable_allows() {
        assertNull(service.amountProvenanceGate(result(listOf(utxo(100_000L), utxo(250_000L)))))
    }

    @Test
    fun gate_zeroAmount_refuses() {
        val reason = service.amountProvenanceGate(result(listOf(utxo(0L))))
        assertNotNull(reason)
        assertTrue(reason!!.contains("non-positive"))
    }

    @Test
    fun gate_negativeAmount_refuses() {
        assertNotNull(service.amountProvenanceGate(result(listOf(utxo(100_000L), utxo(-1L)))))
    }

    @Test
    fun gate_backendUnreachable_refuses() {
        val reason = service.amountProvenanceGate(result(listOf(utxo(100_000L)), reachable = false))
        assertNotNull(reason)
        assertTrue(reason!!.contains("unreachable"))
    }

    @Test
    fun sweepFromSeed_backendUnreachable_refusesWithoutSigning() = runBlocking {
        val res = service.sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = listOf(result(listOf(utxo(500_000L)), reachable = false)),
            destAddress = "dgb1qdestplaceholder",
        )
        assertNull(res.outcomes[0].txid)
        assertTrue(res.outcomes[0].failureReason!!.contains("unreachable"))
    }

    @Test
    fun sweepFromSeed_zeroAmountUtxo_refusesWithoutSigning() = runBlocking {
        val res = service.sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = listOf(result(listOf(utxo(0L)))),
            destAddress = "dgb1qdestplaceholder",
        )
        assertNull(res.outcomes[0].txid)
        assertTrue(res.outcomes[0].failureReason!!.contains("non-positive"))
    }
}
