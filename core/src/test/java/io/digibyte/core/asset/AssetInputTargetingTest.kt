package io.digibyte.core.asset

import io.digibyte.core.model.AssetOperation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Is an input outpoint one that its own transaction directs asset units to?
 *
 * A row's stored quantity is zero both for ordinary DGB change and for an output whose units
 * could not be totalled. [outputIsTargeted] tells the two apart from the transaction that created
 * the output, with the same [AssetTxQuantity.targetsOutput] rule detection applies to outputs —
 * so the answer for an input is the answer that was, or would have been, given when that output
 * was first seen. Where only a remainder decides, the question moves one transaction back.
 */
class AssetInputTargetingTest {

    private fun ti(outputIndex: Int, amount: Long, range: Boolean = false, percent: Boolean = false) =
        TransferInstruction(
            skip = false, range = range, percent = percent,
            outputIndex = outputIndex, amount = amount, isBurn = false,
        )

    private fun header(operation: AssetOperation, instructions: List<TransferInstruction>) = DecodedAssetHeader(
        version = 3,
        opcode = if (operation == AssetOperation.ISSUANCE) 0x01 else 0x15,
        operation = operation,
        metadataHash = null, metadataCid = null,
        totalQuantity = if (operation == AssetOperation.ISSUANCE) 100L else null,
        divisibility = 0, locked = true, aggregation = Aggregation.AGGREGATABLE,
        transferInstructions = instructions,
    )

    /** The transactions the wallet holds, the rows it has stored, and what was read. */
    private val shapes = HashMap<String, AssetTxShape>()
    private val rows = HashMap<Pair<String, Int>, Long>()
    private val plain = HashSet<String>()
    private val shapesRead = mutableListOf<String>()

    private fun transfer(
        txid: String, outputCount: Int, inputs: List<Pair<String, Int>>?,
        vararg instructions: TransferInstruction,
    ) {
        shapes[txid] = AssetTxShape(header(AssetOperation.TRANSFER, instructions.toList()), 0, outputCount) { inputs }
    }

    private suspend fun targeted(txid: String, vout: Int, settled: MutableMap<String, Long?>? = null): Boolean? =
        outputIsTargeted(
            txid = txid, vout = vout,
            shapeOf = { shapesRead += it; shapes[it] },
            rowQuantity = { t, v -> rows[t to v] },
            isAssetTx = { t -> if (t in plain) false else if (t in shapes) true else null },
            settled = settled,
        )

    private suspend fun inputUnits(vararg inputs: Pair<String, Int>): Long? = resolveInputAssetUnits(
        inputs = inputs.toList(),
        rowQuantity = { t, v -> rows[t to v] },
        isAssetTx = { t -> if (t in plain) false else if (t in shapes) true else null },
        rowIsTargeted = { t, v -> targeted(t, v) },
    )

    // ── Decided by the payload alone ──────────────────────────────────────

    @Test fun an_output_a_percent_instruction_names_is_targeted() = runBlocking {
        transfer("carrier", 4, inputs = null, ti(outputIndex = 1, amount = 50, percent = true))
        assertEquals(true, targeted("carrier", 1))
    }

    @Test fun an_output_a_fixed_or_range_instruction_names_is_targeted() = runBlocking {
        transfer("fixed", 4, inputs = null, ti(outputIndex = 2, amount = 0))
        transfer("ranged", 6, inputs = null, ti(outputIndex = 3, amount = 5, range = true))
        assertEquals(true, targeted("fixed", 2))
        assertEquals(true, targeted("ranged", 1))
    }

    /** A percent instruction leaves the remainder unknown whatever the inputs carried. */
    @Test fun the_last_output_beside_a_percent_instruction_is_targeted() = runBlocking {
        transfer("carrier", 4, inputs = listOf("known" to 0), ti(outputIndex = 1, amount = 50, percent = true))
        rows["known" to 0] = 100L
        assertEquals(true, targeted("carrier", 3))
    }

    /** GUARD. The live shape: an issuance's vout 2 is the issuer's DGB change. An issuance hands
     *  out what it issues and leaves no remainder, so its inputs are not even read. */
    @Test fun the_change_of_an_issuance_is_not_targeted() = runBlocking {
        shapes["issuance"] = AssetTxShape(
            header(AssetOperation.ISSUANCE, listOf(ti(outputIndex = 0, amount = 100))), 0, 3,
            inputs = { throw AssertionError("an issuance leaves no remainder: its inputs are not read") },
        )
        assertEquals(false, targeted("issuance", 2))
        assertEquals(true, targeted("issuance", 0))
    }

    /** GUARD. An output in the middle that no instruction names is change, whatever came in. */
    @Test fun an_output_nothing_names_is_not_targeted_even_with_unknown_inputs() = runBlocking {
        transfer("send", 4, inputs = null, ti(outputIndex = 0, amount = 10))
        assertEquals(false, targeted("send", 2))
    }

    // ── Decided by the remainder ──────────────────────────────────────────

    /** GUARD. A send this wallet builds: every unit assigned, DGB change last. */
    @Test fun the_last_output_of_a_fully_assigned_transfer_with_known_inputs_is_not_targeted() = runBlocking {
        transfer("send", 4, inputs = listOf("held" to 0, "coin" to 1), ti(0, 5), ti(2, 95))
        rows["held" to 0] = 100L
        plain += "coin"
        assertEquals(false, targeted("send", 3))
    }

    @Test fun the_last_output_of_a_transfer_that_leaves_a_remainder_is_targeted() = runBlocking {
        transfer("send", 4, inputs = listOf("held" to 0), ti(0, 10))
        rows["held" to 0] = 100L
        assertEquals(true, targeted("send", 3))
    }

    @Test fun the_last_output_is_targeted_when_its_transactions_inputs_cannot_be_read() = runBlocking {
        transfer("send", 4, inputs = null, ti(0, 5), ti(2, 95))
        assertEquals(true, targeted("send", 3))
    }

    /**
     * Two transactions back. `carrier:1` is a percent target stored as zero. `first` spent it and
     * assigned only what its resolved rows showed, so whatever `carrier:1` held rode to `first`'s
     * last output — also stored as zero. A transaction spending that output has inputs whose
     * total is unknown.
     */
    @Test fun a_zero_row_fed_by_a_targeted_zero_row_is_itself_targeted() = runBlocking {
        transfer("carrier", 4, inputs = listOf("origin" to 0), ti(outputIndex = 1, amount = 50, percent = true))
        transfer("first", 4, inputs = listOf("held" to 0, "carrier" to 1), ti(0, 5), ti(2, 95))
        rows["held" to 0] = 100L
        rows["carrier" to 1] = 0L
        rows["first" to 2] = 95L
        rows["first" to 3] = 0L

        assertEquals(true, targeted("first", 3))
        assertNull("the resolved asset change beside it does not make the total known",
            inputUnits("first" to 2, "first" to 3))
    }

    /**
     * A run of [length] ordinary sends: each pays its fee with the DGB change of the one before,
     * takes in a resolved 100-unit row of its own and assigns every unit of it. Every change
     * output on the run is a row with a stored zero. Returns the newest send's txid.
     */
    private fun runOfOrdinarySends(length: Int): String {
        shapes.clear(); rows.clear(); plain.clear(); shapesRead.clear()
        plain += "coin"
        var feeIn = "coin" to 0
        for (n in 1..length) {
            val send = "send$n"
            transfer(send, 4, inputs = listOf("held$n" to 0, feeIn), ti(0, 5), ti(2, 95))
            rows["held$n" to 0] = 100L
            rows[send to 3] = 0L
            feeIn = send to 3
        }
        return "send$length"
    }

    /**
     * GUARD. The ordinary case, several sends deep: each send pays its fee with the DGB change of
     * the one before, and each assigned every unit it took in. None of those change outputs is
     * targeted, so the newest send's inputs total exactly its asset row and its own change stays
     * spendable.
     */
    @Test fun a_run_of_ordinary_change_stays_untargeted() = runBlocking {
        shapes["issuance"] = AssetTxShape(
            header(AssetOperation.ISSUANCE, listOf(ti(outputIndex = 0, amount = 100))), 0, 3,
            inputs = { listOf("coin" to 0) },
        )
        plain += "coin"
        rows["issuance" to 0] = 100L
        rows["issuance" to 2] = 0L
        var assetIn = "issuance" to 0
        var feeIn = "issuance" to 2
        var left = 100L
        for (n in 1..12) {
            val send = "send$n"
            left -= 5
            transfer(send, 4, inputs = listOf(assetIn, feeIn), ti(0, 5), ti(2, left))
            rows[send to 2] = left
            rows[send to 3] = 0L
            assetIn = send to 2
            feeIn = send to 3
        }

        assertEquals(false, targeted("send12", 3))
        assertEquals(40L, inputUnits(assetIn, feeIn))
    }

    // ── No answer ─────────────────────────────────────────────────────────

    @Test fun a_transaction_that_cannot_be_read_gives_no_answer() = runBlocking {
        assertNull(targeted("absent", 0))
    }

    // ── The run is followed to its start, however long it is ─────────────

    /**
     * GUARD (the neighbouring rule, at depth). The question moves back one transaction at a time
     * and keeps moving until the run ends. Every step of this run is the ordinary change of a
     * send whose inputs were known, so the newest change is ordinary change too — at any length.
     * The answer a wallet gets for its newest change must not turn over because of how many sends
     * came before it.
     */
    @Test fun a_run_of_any_length_stays_untargeted() = runBlocking {
        assertEquals(false, targeted(runOfOrdinarySends(129), 3))
        assertEquals(false, targeted(runOfOrdinarySends(200), 3))
        assertEquals(false, targeted(runOfOrdinarySends(2_000), 3))
    }

    /** The walk carries its own state, so a run far deeper than a call stack is still answered. */
    @Test fun a_run_deeper_than_a_call_stack_is_answered() = runBlocking {
        assertEquals(false, targeted(runOfOrdinarySends(20_000), 3))
    }

    /** What the walk costs: each transaction on the run is read once. */
    @Test fun each_transaction_on_the_run_is_read_once() = runBlocking {
        val newest = runOfOrdinarySends(300)

        assertEquals(false, targeted(newest, 3))

        assertEquals("reads: ${shapesRead.size}", shapesRead.size, shapesRead.distinct().size)
    }

    // ── A pass's record of what it has already resolved ───────────────────

    @Test fun a_total_already_settled_in_the_pass_is_used_and_not_resolved_again() = runBlocking {
        transfer("send", 4, inputs = listOf("held" to 0, "earlier" to 3), ti(0, 5), ti(2, 95))
        val settled = HashMap<String, Long?>()

        settled["send"] = 100L
        assertEquals(false, targeted("send", 3, settled))
        settled["send"] = 130L
        assertEquals(true, targeted("send", 3, settled))
        settled["send"] = null
        assertEquals("a settled 'unknown' is an answer too", true, targeted("send", 3, settled))
        assertEquals("only the transaction asked about was read", listOf("send", "send", "send"), shapesRead)
    }

    @Test fun a_total_resolved_on_the_way_is_recorded_for_the_pass() = runBlocking {
        transfer("send", 4, inputs = listOf("held" to 0, "coin" to 1), ti(0, 5), ti(2, 95))
        rows["held" to 0] = 100L
        plain += "coin"
        val settled = HashMap<String, Long?>()

        assertEquals(false, targeted("send", 3, settled))

        assertTrue("send" in settled)
        assertEquals(100L, settled["send"])
    }
}
