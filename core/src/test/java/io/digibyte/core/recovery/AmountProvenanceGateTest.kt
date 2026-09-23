package io.digibyte.core.recovery

import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.reconcile.UtxoEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    // ---- the parent transaction states the amount and the script --------------------------------
    //
    // These drive the real sweepFromSeed with only the native touchpoints replaced: the parent
    // parser (a TxBook), the signer, the broadcaster and the log. The signer below builds what the
    // native one builds: every input it is given, one output of their total less the fee.

    private val book = TxBook()
    private val signedWith = mutableListOf<SweepInputs>()
    private val recordedFee = slot<Long>()
    private val outgoing: OutgoingTxStore = mockk(relaxed = true) {
        every { record(any(), any(), capture(recordedFee), any(), any()) } returns Unit
    }
    private val sweepFee = 52_400L
    private val p2pkh = "76a914${"11".repeat(20)}88ac"
    private val derived = listOf(DerivedAddress(addr, chain = 0, index = 0))

    private fun sweeper() = LegacySweepService(
        outgoingTxStore = outgoing,
        walletTxPersister = mockk(relaxed = true),
        assetClassifier = ForeignUtxoAssetClassifier(fetchRawTx = { byteArrayOf(1) }, isAssetTx = { false }),
        parents = book.binding,
        signSweep = { _, _, inputs, _, _ ->
            signedWith += inputs
            val id = "signed-${signedWith.size}"
            book.add(
                id,
                outputs = listOf(RawTxBinding.Output(0, inputs.totalIn - sweepFee, "0014${"22".repeat(20)}")),
                inputs = inputs.txids.zip(inputs.vouts) { t, v -> "$t:$v" },
            )
            book.hex(id)
        },
        broadcast = { "sweep-txid" },
        log = {},
    )

    private fun coin(txid: String, sats: Long, vout: Int = 0, script: String? = p2pkh) =
        UtxoEntry(txid, vout, sats, addr, 100L, script)

    private fun profile(utxos: List<UtxoEntry>, parents: Map<String, io.digibyte.core.reconcile.RawTxEntry>) =
        RecoveryScanService.ProfileResult(
            profile = legacyProfile,
            addresses = listOf(addr),
            derivedAddresses = derived,
            utxos = utxos,
            rawTxs = parents,
            reachableBackend = true,
        )

    private fun sweepOne(result: RecoveryScanService.ProfileResult) = runBlocking {
        sweeper().sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = listOf(result),
            destAddress = "dgb1qdestplaceholder",
        ).outcomes.single()
    }

    private fun assertRefusedUnsigned(outcome: LegacySweepService.SweepOutcome) {
        assertTrue("nothing may be signed, but the signer was given $signedWith", signedWith.isEmpty())
        assertEquals(LegacySweepService.BroadcastState.FAILED, outcome.broadcastState)
        assertNull(outcome.txid)
        assertNotNull(outcome.failureReason)
    }

    /**
     * A parent that disagrees refuses the whole profile: the honest [sibling] beside it is not
     * signed either, and the reason given is the disagreement, not an unread parent.
     */
    private fun assertProfileRefused(outcome: LegacySweepService.SweepOutcome, sibling: UtxoEntry) {
        assertRefusedUnsigned(outcome)
        assertTrue(
            "the honest sibling ${sibling.txid} must not be signed either",
            signedWith.none { sibling.txid in it.txids },
        )
        assertTrue(
            "the reason names the disagreement, got ${outcome.failureReason}",
            outcome.failureReason!!.contains("does not state"),
        )
    }

    @Test
    fun `an amount lower than the parent states refuses the profile unsigned`() {
        val a = coin("a1".repeat(32), 500_000L)
        val b = coin("b1".repeat(32), 300_000L)
        book.honest(a, b)
        val reportedLow = a.copy(amountSatoshi = 100_000L)
        assertProfileRefused(sweepOne(profile(listOf(reportedLow, b), book.rawTxs(listOf(a, b)))), b)
    }

    @Test
    fun `an amount higher than the parent states refuses the profile unsigned`() {
        val a = coin("a2".repeat(32), 500_000L)
        val sibling = coin("b2".repeat(32), 300_000L)
        book.honest(a, sibling)
        val reportedHigh = a.copy(amountSatoshi = 900_000L)
        assertProfileRefused(
            sweepOne(profile(listOf(sibling, reportedHigh), book.rawTxs(listOf(a, sibling)))),
            sibling,
        )
    }

    @Test
    fun `a parent that is another transaction refuses the profile unsigned`() {
        val a = coin("a3".repeat(32), 500_000L)
        val other = coin("c3".repeat(32), 500_000L)
        val sibling = coin("b3".repeat(32), 300_000L)
        book.honest(a, other, sibling)
        // Filed under a's txid, but the bytes are the other transaction.
        val parents = mapOf(a.txid to book.entry(other.txid)) + book.rawTxs(listOf(sibling))
        assertProfileRefused(sweepOne(profile(listOf(sibling, a), parents)), sibling)
    }

    @Test
    fun `an output index the parent does not have refuses the profile unsigned`() {
        val a = coin("a4".repeat(32), 500_000L)
        val sibling = coin("b4".repeat(32), 300_000L)
        book.honest(a, sibling)
        val wrongIndex = a.copy(vout = 3)
        assertProfileRefused(
            sweepOne(profile(listOf(sibling, wrongIndex), book.rawTxs(listOf(a, sibling)))),
            sibling,
        )
    }

    @Test
    fun `a script other than the parent's refuses the profile unsigned`() {
        val a = coin("a5".repeat(32), 500_000L)
        val sibling = coin("b5".repeat(32), 300_000L)
        book.honest(a, sibling)
        val otherScript = a.copy(scriptPubKeyHex = "76a914${"99".repeat(20)}88ac")
        assertProfileRefused(
            sweepOne(profile(listOf(sibling, otherScript), book.rawTxs(listOf(a, sibling)))),
            sibling,
        )
    }

    @Test
    fun `an input without a parent is held back and reported, the rest is swept`() {
        val proven = coin("a6".repeat(32), 500_000L)
        val noParent = coin("b6".repeat(32), 300_000L)
        book.honest(proven, noParent)
        val outcome = sweepOne(profile(listOf(proven, noParent), book.rawTxs(listOf(proven))))

        assertEquals("only the proven input is signed", listOf(proven.txid), signedWith.single().txids)
        assertTrue(
            "the held input is reported, got ${outcome.heldBackUnknown}",
            "${noParent.txid}:0" in outcome.heldBackUnknown,
        )
        assertEquals(500_000L, outcome.sweptSat)
    }

    @Test
    fun `an unreadable parent holds that input back rather than refusing the profile`() {
        val proven = coin("a7".repeat(32), 500_000L)
        val unreadable = coin("b7".repeat(32), 300_000L)
        book.honest(proven, unreadable)
        val parents = book.rawTxs(listOf(proven)) +
            (unreadable.txid to io.digibyte.core.reconcile.RawTxEntry("zz", 1L, 1L))
        val outcome = sweepOne(profile(listOf(proven, unreadable), parents))

        assertEquals(listOf(proven.txid), signedWith.single().txids)
        assertTrue("${unreadable.txid}:0" in outcome.heldBackUnknown)
    }

    /**
     * The honest case. What reaches the signer is what the parents state, and every satoshi the
     * inputs hold is accounted for: what the destination receives plus the fee the signed
     * transaction pays. The fee recorded for the activity list is that same figure.
     */
    @Test
    fun `honest parents sign their own amounts, and the inputs equal destination plus fee`() {
        val a = coin("a8".repeat(32), 400_000L)
        val b = coin("b8".repeat(32), 300_000L, vout = 1)
        val c = coin("c8".repeat(32), 200_000L)
        book.honest(a, b, c)
        val outcome = sweepOne(profile(listOf(a, b, c), book.rawTxs(listOf(a, b, c))))

        val signed = signedWith.single()
        assertEquals(listOf(400_000L, 300_000L, 200_000L), signed.amounts)
        assertEquals(listOf(p2pkh, p2pkh, p2pkh), signed.scripts)
        assertEquals("sweep-txid", outcome.txid)
        assertEquals(LegacySweepService.BroadcastState.PENDING, outcome.broadcastState)

        val destination = signed.totalIn - sweepFee
        assertEquals("the outcome reports the fee the signed transaction pays", sweepFee, outcome.feeSat)
        assertEquals("the fee recorded is the same figure", sweepFee, recordedFee.captured)
        assertEquals(900_000L, destination + outcome.feeSat!!)
    }

    @Test
    fun `a signed transaction that does not spend exactly its proven inputs is not sent`() {
        val a = coin("aa".repeat(32), 400_000L)
        book.honest(a)
        var sent = 0
        val sweeper = LegacySweepService(
            outgoingTxStore = outgoing,
            walletTxPersister = mockk(relaxed = true),
            assetClassifier = ForeignUtxoAssetClassifier(fetchRawTx = { byteArrayOf(1) }, isAssetTx = { false }),
            parents = book.binding,
            signSweep = { _, _, inputs, _, _ ->
                book.add(
                    "signed-extra",
                    outputs = listOf(RawTxBinding.Output(0, inputs.totalIn, p2pkh)),
                    inputs = listOf("${a.txid}:0", "${"ee".repeat(32)}:0"),
                )
                book.hex("signed-extra")
            },
            broadcast = { sent++; "x" },
            log = {},
        )
        val outcome = runBlocking {
            sweeper.sweepFromSeed(ByteArray(64), listOf(profile(listOf(a), book.rawTxs(listOf(a)))), "dgb1qdest")
        }.outcomes.single()
        assertEquals(0, sent)
        assertEquals(LegacySweepService.BroadcastState.FAILED, outcome.broadcastState)
        assertNull(outcome.feeSat)
    }

    /** GUARD: the non-positive rule stays in front of the parent check. */
    @Test
    fun `a zero amount is still refused even when a parent states zero`() {
        val a = coin("a9".repeat(32), 0L)
        book.honest(a)
        val outcome = sweepOne(profile(listOf(a), book.rawTxs(listOf(a))))
        assertRefusedUnsigned(outcome)
        assertTrue(outcome.failureReason!!.contains("non-positive"))
        assertFalse(signedWith.isNotEmpty())
    }
}
