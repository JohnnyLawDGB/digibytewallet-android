package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.asset.AssetTransferAllocator.Result
import app.aroundtheblock.wallet.core.model.AssetOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The hold rule ([AssetTxQuantity.targetsOutput]) covers every output DigiAsset Core's allocation
 * ([AssetTransferAllocator]) can credit, so no output that receives units is offered as plain DGB.
 *
 *  - An issuance's remainder lands on the LAST output (`allocateIssuance`); that output is held
 *    while the remainder is positive or cannot be known from the payload.
 *  - A transfer whose instruction names an output the transaction does not have is void, and the
 *    inputs' units all land on the last output; that output is held whenever the inputs may carry
 *    units.
 *
 * And the payload re-read ([AssetPayloadCheck]) agrees with [DigiAssetDecoder] on output index 31:
 * it is the destroy marker only in a BURN.
 */
class AssetHoldCoversCreditedOutputsTest {

    private val X = "La" + "x".repeat(36)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun decode(s: String) = requireNotNull(DigiAssetDecoder().decode(hex(s))) { "vector $s did not decode" }
    private fun b(v: Int) = "%02x".format(v)

    private fun held(h: DecodedAssetHeader, vout: Int, inputUnits: Long?, outputCount: Int = 3, first: Int? = 0) =
        AssetTxQuantity.targetsOutput(h, vout, first, inputUnits, outputCount)

    // ── Issuance remainder ─────────────────────────────────────────────────────────────────────

    /** qty 10, one instruction -> output 0 for 4: Core leaves 6 on the last output. */
    @Test fun an_issuance_remainder_on_the_last_output_is_held() {
        val h = decode("6a08" + "44410305" + "0a" + "00" + "04" + "10")
        assertEquals(AssetOperation.ISSUANCE, h.operation)
        val r = AssetTransferAllocator.allocateIssuance(h, X, 3, payloadExact = true)
        assertEquals(listOf(AssetUnits(X, 6)), (r as Result.Allocated).outputs[2])
        for (units in listOf(null, 0L, 10L)) assertTrue("inputUnits=$units", held(h, 2, units))
    }

    /** No instruction at all: the whole supply lands on the last output. */
    @Test fun an_issuance_with_no_instruction_holds_the_last_output() {
        val h = decode("6a06" + "44410305" + "0a" + "10")
        for (units in listOf(null, 0L, 10L)) assertTrue("inputUnits=$units", held(h, 2, units))
    }

    /** An issuance whose instruction names an output the transaction does not have is void: the
     *  whole supply lands on the last output. */
    @Test fun an_issuance_naming_a_missing_output_holds_the_last_output() {
        val h = decode("6a08" + "44410305" + "0a" + "05" + "04" + "10")
        assertEquals(mapOf(2 to listOf(AssetUnits(X, 10))),
            (AssetTransferAllocator.allocateIssuance(h, X, 3, payloadExact = true) as Result.Allocated).outputs)
        assertTrue(held(h, 2, null))
        assertTrue(held(h, 2, 0L))
    }

    /** Assigning more than is issued voids the instructions: everything to the last output. */
    @Test fun an_issuance_that_assigns_more_than_it_issues_holds_the_last_output() {
        for (flags in listOf("10", "14")) {   // aggregatable, hybrid
            val h = decode("6a08" + "44410305" + "0a" + "00" + "14" + flags)
            assertTrue("flags $flags", held(h, 2, null))
        }
    }

    /** A payload the wallet cannot read exactly (an earlier version, or a rules block it does not
     *  parse) leaves the remainder unknown: the last output is held. */
    @Test fun an_issuance_whose_instructions_are_not_read_exactly_holds_the_last_output() {
        val v2 = decode("6a07" + "4441020526" + "4000")
        assertEquals(AssetOperation.ISSUANCE, v2.operation)
        assertTrue(held(v2, 2, null))
        val rules = v2.copy(version = 3, opcode = 3, totalQuantity = 10L,
            transferInstructions = listOf(TransferInstruction(false, false, false, 0, 10L, false)))
        assertTrue(rules.hasRules)
        assertTrue(held(rules, 2, 0L))
    }

    /** GUARD. An issuance that hands out its whole supply leaves no remainder: its DGB change on
     *  the last output stays spendable. */
    @Test fun an_issuance_that_assigns_its_whole_supply_leaves_the_last_output_spendable() {
        val h = decode("6a08" + "44410305" + "0a" + "00" + "0a" + "10")
        val r = AssetTransferAllocator.allocateIssuance(h, X, 3, payloadExact = true)
        assertEquals(mapOf(0 to listOf(AssetUnits(X, 10))), (r as Result.Allocated).outputs)
        for (units in listOf(null, 0L, 10L)) assertFalse("inputUnits=$units", held(h, 2, units))
        assertTrue(held(h, 0, null))
    }

    // ── Void transfer ──────────────────────────────────────────────────────────────────────────

    /** One instruction to output 5 of a three-output transaction: void, so all 9 input units land
     *  on the last output. */
    @Test fun a_transfer_naming_a_missing_output_holds_the_last_output() {
        val h = decode("6a06" + "44410315" + "05" + "09")
        assertEquals(AssetOperation.TRANSFER, h.operation)
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(AssetUnits(X, 9))), 3) { true }
        assertEquals(Result.Allocated(mapOf(2 to listOf(AssetUnits(X, 9))), instructionsVoided = true), r)
        assertTrue("inputs carried exactly what was assigned", held(h, 2, 9L))
        assertTrue("inputs carried more", held(h, 2, 12L))
        assertTrue("inputs unknown", held(h, 2, null))
    }

    /** An instruction that moves zero units still reads an input; after the inputs are used up
     *  that read fails and voids every instruction, so all 5 units land on the last output even
     *  though the instructions assign exactly what came in. */
    @Test fun a_zero_amount_instruction_after_the_inputs_are_used_holds_the_last_output() {
        val h = decode("6a06" + "44410315" + "05" + "09").copy(transferInstructions = listOf(
            TransferInstruction(false, false, false, 0, 5L, false),
            TransferInstruction(false, false, false, 1, 0L, false),
        ))
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(AssetUnits(X, 5))), 3) { true }
        assertEquals(Result.Allocated(mapOf(2 to listOf(AssetUnits(X, 5))), instructionsVoided = true), r)
        assertTrue(held(h, 2, 5L))
    }

    /** GUARD (held before this rule too): a range instruction whose end is past the outputs is void,
     *  and it already names the last output. */
    @Test fun a_range_transfer_past_the_outputs_holds_the_last_output() {
        val inst = TransferInstruction(false, true, false, 3, 3L, false)
        val h = decode("6a06" + "44410315" + "05" + "09").copy(transferInstructions = listOf(inst))
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(AssetUnits(X, 12))), 3) { true }
        assertEquals(mapOf(2 to listOf(AssetUnits(X, 12))), (r as Result.Allocated).outputs)
        assertTrue(held(h, 2, 12L))
    }

    /** GUARD. A void transfer whose inputs carried nothing is not an asset transfer: nothing moves,
     *  and the last output stays as it was. */
    @Test fun a_void_transfer_with_no_units_in_does_not_hold_the_last_output() {
        val h = decode("6a06" + "44410315" + "05" + "09")
        assertEquals(Result.NotAnAssetTransfer, AssetTransferAllocator.allocate(h, listOf(emptyList()), 3) { true })
        assertFalse(held(h, 2, 0L))
    }

    /** GUARD. A burn to the destroy marker in a transaction of fewer than 32 outputs is not void. */
    @Test fun a_burn_to_the_destroy_marker_is_not_a_missing_output() {
        val h = decode("6a06" + "44410325" + "1f" + "09")
        assertEquals(AssetOperation.BURN, h.operation)
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(AssetUnits(X, 9))), 3) { true }
        assertEquals(Result.Allocated(emptyMap(), instructionsVoided = false), r)
        assertFalse(held(h, 2, 9L))
    }

    // ── Every credited output is held ──────────────────────────────────────────────────────────

    /**
     * Generated transfers, burns and issuances (version 3, fixed and range instructions, one
     * aggregatable asset, no skip): every output the allocation credits — the OP_RETURN aside —
     * is held, for the input total the allocation drew from and when that total is unknown.
     */
    @Test fun every_output_the_allocation_credits_is_held() {
        val rnd = Random(0x5eed)
        var checked = 0
        repeat(20_000) {
            val outputCount = 1 + rnd.nextInt(6)
            val opReturn = rnd.nextInt(outputCount)
            val first = (0 until outputCount).firstOrNull { it != opReturn }
            val op = listOf(AssetOperation.TRANSFER, AssetOperation.BURN, AssetOperation.ISSUANCE)[rnd.nextInt(3)]
            val instructions = List(rnd.nextInt(4)) {
                val range = rnd.nextInt(4) == 0
                val out = if (op == AssetOperation.BURN && rnd.nextInt(4) == 0) 31 else rnd.nextInt(outputCount + 2)
                TransferInstruction(false, range, false, out, rnd.nextLong(0, 8), !range && out == 31 && op == AssetOperation.BURN)
            }
            val h = DecodedAssetHeader(
                version = 3, opcode = when (op) { AssetOperation.ISSUANCE -> 0x05; AssetOperation.BURN -> 0x25; else -> 0x15 },
                operation = op, metadataHash = null, metadataCid = null,
                totalQuantity = if (op == AssetOperation.ISSUANCE) rnd.nextLong(1, 20) else null,
                divisibility = 0, locked = true, aggregation = Aggregation.AGGREGATABLE,
                transferInstructions = instructions,
            )
            val inputUnits = rnd.nextLong(0, 20)
            val r = if (op == AssetOperation.ISSUANCE) {
                AssetTransferAllocator.allocateIssuance(h, X, outputCount, payloadExact = true)
            } else {
                AssetTransferAllocator.allocate(h, listOf(listOf(AssetUnits(X, inputUnits))), outputCount) { true }
            }
            val outputs = (r as? Result.Allocated)?.outputs ?: return@repeat
            for ((vout, units) in outputs) {
                if (vout == opReturn || units.none { it.count > 0L }) continue
                checked++
                assertTrue("$op $instructions in=$inputUnits n=$outputCount vout=$vout",
                    AssetTxQuantity.targetsOutput(h, vout, first, inputUnits, outputCount))
                assertTrue("$op $instructions in=unknown n=$outputCount vout=$vout",
                    AssetTxQuantity.targetsOutput(h, vout, first, null, outputCount))
            }
        }
        assertTrue("the generator exercised the rule ($checked)", checked > 1_000)
    }

    // ── Output index 31 in the payload re-read ─────────────────────────────────────────────────

    @Test fun the_reported_index_31_payloads_read_exactly() {
        for (s in listOf("6a06444103151f01", "6a06444103151e01", "6a06444103251f01",
                "6a0844410305011f0100", "6a0844410305011e0100")) {
            assertTrue(s, AssetPayloadCheck.readsExactly(hex(s), decode(s)))
        }
    }

    /** The re-read and the decoder agree on every non-range output index, in every operation. */
    @Test fun the_payload_reread_agrees_with_the_decoder_on_every_output_index() {
        for (out in 0..31) for (range in listOf(false, true)) {
            // A range instruction carries a 13-bit index: 0b010 + 13 bits = two bytes.
            val inst = if (range) b(0x40 or (out shr 8)) + b(out and 0xFF) else b(out)
            val n = inst.length / 2
            for (s in listOf(
                "6a" + b(5 + n) + "44410315" + inst + "01",           // transfer
                "6a" + b(5 + n) + "44410325" + inst + "01",           // burn
                "6a" + b(7 + n) + "4441030501" + inst + "0100",       // issuance
            )) {
                val h = decode(s)
                assertTrue("$s ${h.transferInstructions}", AssetPayloadCheck.readsExactly(hex(s), h))
            }
        }
    }

    /** GUARD. Reading index 31 exactly does not change what is held: an instruction to output 31
     *  of a transfer names a real output, held whatever came in. */
    @Test fun an_instruction_to_output_31_of_a_transfer_is_held() {
        val h = decode("6a06444103151f01")
        for (units in listOf(null, 0L, 1L)) assertTrue(held(h, 31, units, outputCount = 33))
    }
}
