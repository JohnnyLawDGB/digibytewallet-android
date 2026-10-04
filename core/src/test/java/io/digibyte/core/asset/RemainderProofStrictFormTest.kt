package io.digibyte.core.asset

import io.digibyte.core.asset.RemainderProofWalk.Verdict
import io.digibyte.core.asset.send.DigiAssetTransferModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transfer shapes the remainder proof must not prove, because the protocol gives units to the last
 * output in them: an earlier transfer version whose first input holds no asset (the instructions are
 * not applied at all), a zero-amount instruction after the holding is used up (the instruction set is
 * abandoned), and a transaction with more than one data output (the instructions read may not be the
 * ones applied). Each is held, never released.
 */
class RemainderProofStrictFormTest {
    private val ours = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x11 }
    private val theirs = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 }
    private fun fixed(out: Int, amount: Long) =
        DigiAssetEncoder.TransferInstruction(skip = false, range = false, percent = false, outputIndex = out, amount = amount)
    private fun transferV(v: Int, vararg inst: DigiAssetEncoder.TransferInstruction) =
        DigiAssetEncoder.encodeTransferScript(v, inst.toList())
    private fun id(tag: String) = tag.padEnd(64, '0')
    private val chain = HashMap<String, WalkTx>()
    private fun put(txid: String, inputs: List<Pair<String, Int>>, vararg outputs: ByteArray) {
        chain[txid] = WalkTx(inputs, outputs.toList())
    }
    private fun walk() = RemainderProofWalk(
        fetch = { txid -> "tx:$txid".toByteArray() },
        txidOf = { bytes -> String(bytes).removePrefix("tx:") },
        parse = { bytes -> chain[String(bytes).removePrefix("tx:")] },
    )
    private val sender = id("70")
    private val plain = id("50")
    private val t = id("71")

    init {
        // The sender's transfer names its output 0 for 5 units: that output holds nothing or 5.
        put(sender, listOf(id("60") to 1), theirs, transferV(3, fixed(0, 5)), theirs)
        // An ordinary payment: its output 0 holds nothing.
        put(plain, listOf(id("40") to 0), theirs, theirs)
    }

    @Test fun a_zero_amount_instruction_after_the_holding_is_used_up_is_not_proven() = runTest {
        put(t, listOf(sender to 0, plain to 0), theirs, theirs, transferV(3, fixed(0, 5), fixed(1, 0)), ours)
        val core = DigiAssetTransferModel.apply(
            inputs = listOf(DigiAssetTransferModel.Input("A", listOf("X" to 5L)), DigiAssetTransferModel.Input("B", emptyList())),
            outputAddresses = listOf("T", "T2", "", "V"),
            instructions = listOf(DigiAssetTransferModel.Instruction(false, 0, 5), DigiAssetTransferModel.Instruction(false, 1, 0)),
            ruleBearing = emptySet(),
        )
        assertEquals("the protocol gives the units to the last output", listOf("X" to 5L), core.outputs[3])
        assertNotEquals(Verdict.Proven, walk().proveLastOutputCarriesNoUnits(t, 3))
    }

    @Test fun an_earlier_version_transfer_whose_first_input_holds_nothing_is_not_proven() = runTest {
        put(t, listOf(plain to 0, sender to 0), theirs, transferV(2, fixed(0, 5)), ours)
        assertNotEquals(Verdict.Proven, walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun a_transaction_with_two_data_outputs_is_not_proven() = runTest {
        val other = byteArrayOf(0x6A, 0x02, 0x01, 0x02)
        put(t, listOf(sender to 0, plain to 0), theirs, transferV(3, fixed(0, 5)), other, ours)
        assertNotEquals(Verdict.Proven, walk().proveLastOutputCarriesNoUnits(t, 3))
    }

    /** GUARD: the shape the proof exists for is still proven — version 3, one holding drawn whole by
     *  non-zero instructions, one data output. */
    @Test fun the_plain_version_three_shape_is_still_proven() = runTest {
        put(t, listOf(sender to 0, plain to 0), theirs, transferV(3, fixed(0, 5)), ours)
        assertTrue(walk().proveLastOutputCarriesNoUnits(t, 2) is Verdict.Proven)
    }
}
