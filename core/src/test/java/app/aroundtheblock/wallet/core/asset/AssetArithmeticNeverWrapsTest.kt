package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.asset.send.AssetStackSource
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import app.aroundtheblock.wallet.core.db.dao.TransactionDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import app.aroundtheblock.wallet.core.model.AssetOperation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * **Credited units never exceed the units the inputs provably carry, and no unit arithmetic wraps.**
 *
 * Covers BB-2026-10-09-sendot-2, BB-2026-10-09-sendot-3, BB-2026-10-09-asumatane and
 * BB-2026-10-09-susanto-f5, through the same per-output path [AssetManager.processIncomingAssetTx]
 * runs (decode, [resolveInputAssetUnits], [AssetTxQuantity.forOutputTotal], [transferOutputCredits],
 * [AssetManager.persistDetectedAssetOutput], [AssetManager.settleFromIndexer]) and the balance the
 * Assets screen reads ([AssetManager.computeHeldAssetBalancesImpl]). The entry point itself loads
 * the native library and cannot run on the JVM; everything after the bridge reads is driven here.
 *
 * Every amount is a 64-bit count of units. The checks are on the arithmetic, so each vector is
 * one whose true value does not fit a signed 64-bit count somewhere along the way.
 */
class AssetArithmeticNeverWrapsTest {

    private val X = "La" + "x".repeat(36)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun decode(s: String) = requireNotNull(DigiAssetDecoder().decode(hex(s)))

    // ── vectors ──────────────────────────────────────────────────────────────────────────────

    /** BB-2026-10-09-sendot-2: fixed-precision amounts whose mantissa x 10^exponent is past
     *  Long.MAX_VALUE (one wraps positive, one negative). */
    private val decoderPastRangePositive = "6a0b4441031500bfffffffffff"
    private val decoderPastRangeNegative = "6a0944410315007fffffff"

    /** BB-2026-10-09-sendot-3: one range instruction (outputs 0..1) whose amount decodes inside
     *  the range but whose consumption (2 x amount) does not. */
    private val rangeConsumptionPastRange = "6a0c444103154001a5d21dba0007"

    /** BB-2026-10-09-sendot-3 as printed in the report's payload section: no "DA" tag. */
    private val untaggedVariant = "6a0a03154001a5d21dba0007"

    /** BB-2026-10-09-asumatane: one range instruction over outputs 0..512, 2^54-1 each; the
     *  transaction has 514 outputs, the last one the wallet's. */
    private val wideRange = "6a0d444103154200ffffffffffffff"

    // ── the decoder ──────────────────────────────────────────────────────────────────────────

    @Test fun a_fixed_precision_value_past_the_signed_range_is_not_read_as_a_count() {
        // 6-byte class: 42-bit mantissa 2^42-1, exponent 7.
        val bytes = hex("bfffffffffff")
        try {
            val v = BitReader(bytes).readFixedPrecision()
            fail("read $v from a value past Long.MAX_VALUE")
        } catch (expected: ArithmeticException) {
        }
    }

    @Test fun the_largest_values_that_fit_are_still_read_exactly() {
        // 7-byte class: 2^54-1, no exponent.
        assertEquals((1L shl 54) - 1, BitReader(hex("ffffffffffffff")).readFixedPrecision())
        // 6-byte class, exponent 7, the largest mantissa whose value fits.
        val m = Long.MAX_VALUE / 10_000_000L
        val w = BitWriter(8)
        w.writeBits(0b101L, 3); w.writeBits(m, 42); w.writeBits(7L, 3)
        assertEquals(m * 10_000_000L, BitReader(w.toByteArray()).readFixedPrecision())
    }

    /** A carrier whose amounts cannot be counted is unclassifiable: every owned output held,
     *  nothing credited, never a negative or wrapped count. */
    @Test fun a_transfer_whose_amount_cannot_be_counted_is_unclassifiable() {
        for (script in listOf(decoderPastRangePositive, decoderPastRangeNegative)) {
            val header = decode(script)
            assertEquals(script, AssetOperation.UNCLASSIFIABLE, header.operation)
            for (vout in 0..2) {
                assertEquals(script, 0L, AssetTxQuantity.forOutputTotal(header, vout, 0, 0L, 3))
                assertTrue(script, AssetTxQuantity.targetsOutput(header, vout, 0, 0L, 3))
            }
        }
    }

    @Test fun the_untagged_variant_is_not_a_digiasset_carrier() {
        assertNull(DigiAssetDecoder().decode(hex(untaggedVariant)))
    }

    // ── consumption and the remainder ────────────────────────────────────────────────────────

    @Test fun a_range_consumption_past_the_signed_range_is_unknown() {
        val header = decode(rangeConsumptionPastRange)
        assertEquals(AssetOperation.TRANSFER, header.operation)
        assertEquals(8_000_000_000_000_000_000L, header.transferInstructions.single().amount)
        assertNull(AssetTxQuantity.assignedUnits(header))
        assertNull(AssetTxQuantity.implicitChange(header, inputUnits = 0L, outputCount = 3))
        assertEquals(0L, AssetTxQuantity.forOutputTotal(header, 2, 0, inputUnits = 0L, outputCount = 3))

        val wide = decode(wideRange)
        assertNull(AssetTxQuantity.assignedUnits(wide))
        assertNull(AssetTxQuantity.implicitChange(wide, inputUnits = 0L, outputCount = 514))
        assertEquals(0L, AssetTxQuantity.forOutputTotal(wide, 513, 1, inputUnits = 0L, outputCount = 514))
    }

    /** The remainder is what the inputs carried minus what the instructions consume. Instructions
     *  that consume more than the inputs carried are void, and the last output then receives what
     *  the inputs carried: never more. */
    @Test fun a_remainder_is_never_more_than_the_inputs_carried() {
        val over = header(TransferInstruction(false, false, false, 0, 150L, false))
        assertEquals(100L, AssetTxQuantity.implicitChange(over, inputUnits = 100L, outputCount = 3))
        val exact = header(TransferInstruction(false, false, false, 0, 100L, false))
        assertEquals(0L, AssetTxQuantity.implicitChange(exact, inputUnits = 100L, outputCount = 3))
        assertNull(AssetTxQuantity.implicitChange(exact, inputUnits = -1L, outputCount = 3))
    }

    /** A negative amount or index (only a hand-built header can carry one) is not a count. */
    @Test fun a_negative_amount_is_not_a_count() {
        val neg = header(TransferInstruction(false, false, false, 0, -5L, false))
        assertNull(AssetTxQuantity.assignedUnits(neg))
        assertNull(AssetTxQuantity.implicitChange(neg, inputUnits = 1L, outputCount = 3))
        assertEquals(0L, AssetTxQuantity.forOutput(neg, 0, 0))
        assertEquals(0L, AssetTxQuantity.forOutputTotal(neg, 2, 0, inputUnits = 1L, outputCount = 3))
    }

    /** Two amounts to one output whose sum is past the signed range credit nothing. */
    @Test fun a_sum_of_amounts_past_the_signed_range_credits_nothing() {
        val big = Long.MAX_VALUE / 2 + 1
        val h = header(
            TransferInstruction(false, false, false, 0, big, false),
            TransferInstruction(false, false, false, 0, big, false),
        )
        assertEquals(0L, AssetTxQuantity.forOutput(h, 0, 0))
        assertNull(AssetTxQuantity.assignedUnits(h))
    }

    @Test fun an_input_total_past_the_signed_range_is_unknown() = runTest {
        val total = resolveInputAssetUnits(
            inputs = listOf("a".repeat(64) to 0, "b".repeat(64) to 0),
            rowQuantity = { _, _ -> Long.MAX_VALUE / 2 + 1 },
            isAssetTx = { false },
            rowIsTargeted = { _, _ -> true },
        )
        assertNull(total)
    }

    // ── the receive path, end to end ─────────────────────────────────────────────────────────

    private val ownedScript = byteArrayOf(0x51)
    private val ownedHex = "51"
    private val txid = "c".repeat(64)
    private val placeholder = "unresolved:$txid"
    private val rows = LinkedHashMap<String, UtxoEntity>()
    private fun key(t: String, v: Int) = "$t:$v"
    private val utxoDao = mockk<UtxoDao>(relaxed = true).also { dao ->
        coEvery { dao.getAssetUtxoAt(any(), any()) } answers { rows[key(firstArg(), secondArg())] }
        coEvery { dao.insertAll(any()) } answers { firstArg<List<UtxoEntity>>().forEach { rows[key(it.txid, it.vout)] = it } }
        coEvery { dao.settleAssetCredit(any(), any(), any(), any(), any()) } answers {
            val k = key(arg(0), arg(1))
            rows[k] = rows.getValue(k).copy(assetId = arg(2), assetQuantity = arg(3), assetCredit = arg(4))
        }
        coEvery { dao.updateAssetQuantity(any(), any(), any()) } answers {
            val k = key(arg(0), arg(1)); rows[k] = rows.getValue(k).copy(assetQuantity = arg(2))
        }
        coEvery { dao.markAssetSource(any(), any(), any()) } answers {
            val k = key(arg(0), arg(1)); rows[k] = rows.getValue(k).copy(assetSource = arg(2))
        }
        coEvery { dao.getAllAssetUtxosNow() } answers { rows.values.toList() }
        coEvery { dao.getAssetUtxosForTxNow(any()) } answers { rows.values.filter { it.txid == firstArg<String>() } }
    }
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val answers = HashMap<String, StackLookup>()
    private val indexer = AssetStackSource { t, v -> answers[key(t, v)] ?: StackLookup.Unavailable }
    private fun manager() =
        AssetManager(utxoDao, txDao, mockk(relaxed = true), mockk(relaxed = true), assetStackSource = indexer)

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private sealed interface In {
        /** No row, and the wallet does not hold the transaction that made it. */
        data object Foreign : In
        /** No row; the wallet holds the creating transaction and it has no data output. */
        data object Plain : In
        /** One of the wallet's own counted rows. */
        data class Own(val units: AssetUnits) : In
    }

    /**
     * Receive [txid] (OP_RETURN [script], [outputCount] outputs, the wallet owning [ownedVouts])
     * spending [inputs], the way processIncomingAssetTx does once the bridge reads are in.
     */
    private suspend fun receive(
        mgr: AssetManager,
        script: ByteArray,
        inputs: List<In>,
        ownedVouts: List<Int>,
        outputCount: Int,
        ruleFree: (String) -> Boolean = { true },
    ): Map<Int, OutputCredit> {
        val header = requireNotNull(DigiAssetDecoder().decode(script))
        val outpoints = inputs.indices.map { i -> ("%064x".format(i + 1)) to i }
        val plainParents = HashSet<String>()
        inputs.forEachIndexed { i, input ->
            val (t, v) = outpoints[i]
            when (input) {
                In.Foreign -> Unit
                In.Plain -> plainParents.add(t)
                is In.Own -> rows[key(t, v)] = UtxoEntity(
                    txid = t, vout = v, scriptPubKey = ownedScript, satoshis = 600, blockHeight = 1,
                    isAsset = true, assetId = input.units.assetId, assetQuantity = input.units.count,
                    spent = true, assetSource = AssetSource.NATIVE, assetCredit = AssetCredit.VERIFIED,
                )
            }
        }
        val inputUnits = resolveInputAssetUnits(
            inputs = outpoints,
            rowQuantity = { t, v -> rows[key(t, v)]?.assetQuantity },
            isAssetTx = { t -> if (t in plainParents) false else null },
            rowIsTargeted = { _, _ -> null },
        )
        if (header.operation == AssetOperation.UNCLASSIFIABLE) return emptyMap()   // no rows written
        val credits = transferOutputCredits(
            header = header,
            inputs = outpoints,
            outputCount = outputCount,
            vouts = ownedVouts,
            row = { t, v -> rows[key(t, v)] },
            parentHasDataOutput = { t -> if (t in plainParents) false else null },
            rowIsTargeted = { _, _ -> null },
            ruleFree = { ruleFree(it) },
            payloadExact = AssetPayloadCheck.readsExactly(script, header),
        ).credits
        for (vout in ownedVouts) {
            val credit = credits.getValue(vout)
            mgr.persistDetectedAssetOutput(
                txHashHex = txid, vout = vout, scriptPubKey = ownedScript, sats = 600, blockHeight = 1,
                placeholderAssetId = placeholder,
                computedQty = AssetTxQuantity.forOutputTotal(header, vout, 0, inputUnits, outputCount),
                credit = credit,
            )
            if (credit == OutputCredit.Unknown) {
                mgr.settleFromIndexer(txid, vout, placeholder,
                    maxUnits = AssetCreditRules.maxDeliverable(header, vout, outputCount), nowMs = 0L)
            }
        }
        return credits
    }

    private suspend fun balances(mgr: AssetManager): Map<String, Long> =
        mgr.computeHeldAssetBalancesImpl(setOf(ownedHex)) { _, _ -> AssetSpentState.HELD }!!
            .filterValues { it.quantity > 0L }.mapValues { it.value.quantity }

    /** No row of the receive stores a negative count, or more than [carried]. */
    private fun assertNoRowExceeds(label: String, carried: Long) {
        for (r in rows.values.filter { it.txid == txid }) {
            assertTrue("$label: ${r.vout} stores ${r.assetQuantity}", r.assetQuantity in 0L..carried)
        }
    }

    /** BB-2026-10-09-sendot-3 / -asumatane: the wallet's output is the last one and the inputs
     *  provably carry nothing. Nothing is credited, displayed or stored. */
    @Test fun a_range_consumption_past_the_signed_range_credits_nothing_on_receive() = runTest {
        for ((script, outputCount) in listOf(rangeConsumptionPastRange to 3, wideRange to 514)) {
            for (inputs in listOf(listOf<In>(In.Plain), listOf<In>(In.Foreign))) {
                rows.clear(); answers.clear()
                val mgr = manager()
                val last = outputCount - 1
                answers[key(txid, last)] = StackLookup.Found(emptyList())   // Core: nothing there
                receive(mgr, hex(script), inputs, listOf(last), outputCount)
                assertEquals("$script $inputs", emptyMap<String, Long>(), balances(mgr))
                assertNull("$script $inputs", mgr.receivedBackedUnits(rows.values.toList()))
                assertNoRowExceeds("$script $inputs", carried = 0L)
            }
        }
    }

    /** BB-2026-10-09-sendot-2: the uncountable carrier writes no row and credits nothing; the
     *  remainder variant (one real unit in) cannot inflate the last output either. */
    @Test fun an_uncountable_amount_credits_nothing_on_receive() = runTest {
        for (script in listOf(decoderPastRangePositive, decoderPastRangeNegative)) {
            for (inputs in listOf(listOf<In>(In.Plain), listOf<In>(In.Own(AssetUnits(X, 1))))) {
                rows.clear()
                val mgr = manager()
                val credits = receive(mgr, hex(script), inputs, listOf(0, 2), outputCount = 3)
                assertEquals(script, emptyMap<String, Long>(), balances(mgr))
                assertNoRowExceeds(script, carried = 1L)
                assertEquals(script, emptyMap<Int, OutputCredit>(), credits)
            }
        }
    }

    /** Instructions whose consumption cannot be counted: no indexer count for the transaction's
     *  outputs is credited. */
    @Test fun an_indexer_count_for_uncountable_instructions_is_not_believed() = runTest {
        val script = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(skip = false, range = true, percent = false,
                outputIndex = 512, amount = WRAPPING_RANGE_AMOUNT),
        ))
        val mgr = manager()
        answers[key(txid, 0)] = StackLookup.Found(listOf(StackEntry(X, WRAPPING_RANGE_AMOUNT)))
        receive(mgr, script, listOf(In.Foreign), listOf(0), outputCount = 514)
        assertEquals(emptyMap<String, Long>(), balances(mgr))
        assertTrue(rows.getValue(key(txid, 0)).assetCredit != AssetCredit.VERIFIED)
        assertNull(AssetTxQuantity.assignedUnits(decode(script.toHexString())))
    }

    /** BB-2026-10-09-susanto-f5: an instruction naming an output the transaction does not have
     *  voids every instruction; the inputs' units go to the last output and nowhere else. */
    @Test fun an_instruction_past_the_outputs_voids_the_transfer() = runTest {
        val script = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(false, false, false, 0, 10L),
            DigiAssetEncoder.TransferInstruction(false, false, false, 5, 5L),
        ))
        // What 4.0.88 credited output 0: the instruction's claim.
        assertEquals(10L, AssetTxQuantity.forOutput(decode(script.toHexString()), 0, 0))

        // Known inputs: Core's rule applied on the device.
        var mgr = manager()
        receive(mgr, script, listOf(In.Own(AssetUnits(X, 15))), listOf(0, 2), outputCount = 3)
        assertEquals(0L, rows.getValue(key(txid, 0)).assetQuantity)
        assertEquals(AssetUnits(X, 15), rows.getValue(key(txid, 2)).let { AssetUnits(it.assetId!!, it.assetQuantity) })
        assertEquals(mapOf(X to 15L), balances(mgr))

        // Someone else's inputs: the indexer says output 0 holds nothing, and it counts nothing.
        rows.clear(); answers.clear()
        mgr = manager()
        answers[key(txid, 0)] = StackLookup.Found(emptyList())
        receive(mgr, script, listOf(In.Foreign), listOf(0), outputCount = 3)
        assertEquals(emptyMap<String, Long>(), balances(mgr))
        assertNoRowExceeds("f5 foreign", carried = 15L)
    }

    /** BB-2026-10-09-susanto-f6: an asset that may carry transfer rules is not allocated on the
     *  device; nothing counts until the indexer has said what Core delivered. */
    @Test fun a_rule_bound_input_is_not_allocated_on_the_device() = runTest {
        val mgr = manager()
        answers[key(txid, 0)] = StackLookup.Found(emptyList())
        val credits = receive(mgr, hex("6a06444103150005"), listOf(In.Own(AssetUnits(X, 9))), listOf(0),
            outputCount = 3, ruleFree = { false })
        assertEquals(OutputCredit.Unknown, credits[0])
        assertEquals(emptyMap<String, Long>(), balances(mgr))
    }

    /** BB-2026-10-09-sendot-1: the reporter's payload, plain and unknown inputs. */
    @Test fun an_unbacked_fixed_instruction_counts_for_nothing() = runTest {
        for (inputs in listOf(listOf<In>(In.Plain), listOf<In>(In.Foreign))) {
            rows.clear(); answers.clear()
            val mgr = manager()
            answers[key(txid, 0)] = StackLookup.Found(emptyList())
            receive(mgr, hex("6a094441031500698967f0"), inputs, listOf(0), outputCount = 3)
            assertEquals("$inputs", emptyMap<String, Long>(), balances(mgr))
            assertNull("$inputs", mgr.receivedBackedUnits(rows.values.toList()))
        }
    }

    // ── the remainder proof ──────────────────────────────────────────────────────────────────

    /** A proof that a last output carries nothing never rests on consumption arithmetic that
     *  wraps. The parent carrier holds exactly what the wrapped product would claim. */
    @Test fun a_remainder_proof_never_rests_on_wrapped_consumption() = runTest {
        val theirs = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 }
        val ours = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x11 }
        val wrapped = 513L * WRAPPING_RANGE_AMOUNT   // deliberately unchecked
        assertTrue(wrapped in 1L until (1L shl 54))
        val p = "70".padEnd(64, '0')
        val t = "71".padEnd(64, '0')
        val chain = HashMap<String, WalkTx>()
        chain[p] = WalkTx(listOf("60".padEnd(64, '0') to 1), listOf(
            theirs,
            DigiAssetEncoder.encodeTransferScript(3, listOf(DigiAssetEncoder.TransferInstruction(false, false, false, 0, wrapped))),
            theirs,
        ))
        val outputs = ArrayList<ByteArray>()
        repeat(513) { outputs += theirs }
        outputs[1] = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(false, true, false, 512, WRAPPING_RANGE_AMOUNT)))
        outputs += ours                                          // output 513, the last
        chain[t] = WalkTx(listOf(p to 0), outputs)
        val walk = RemainderProofWalk(
            fetch = { id -> "tx:$id".toByteArray() },
            txidOf = { b -> String(b).removePrefix("tx:") },
            parse = { b -> chain[String(b).removePrefix("tx:")] },
        )
        assertEquals(
            RemainderProofWalk.Verdict.Held(RemainderProofWalk.Reason.NOT_PROVABLE),
            walk.proveLastOutputCarriesNoUnits(t, 513),
        )
    }

    private fun header(vararg inst: TransferInstruction) = DecodedAssetHeader(
        version = 3, opcode = 0x15, operation = AssetOperation.TRANSFER,
        metadataHash = null, metadataCid = null, totalQuantity = null, divisibility = 0,
        locked = false, aggregation = Aggregation.AGGREGATABLE, transferInstructions = inst.toList(),
    )

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    private companion object {
        /** Fits a signed count; 513 times it does not. */
        const val WRAPPING_RANGE_AMOUNT = 35_958_565_450_000_000L
    }
}
