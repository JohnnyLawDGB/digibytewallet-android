package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.asset.AssetTransferAllocator.Result
import app.aroundtheblock.wallet.core.asset.send.DigiAssetTransferModel
import app.aroundtheblock.wallet.core.model.AssetOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [AssetTransferAllocator] against DigiAsset Core's transfer rules (`DigiByteTransaction.cpp`
 * `decodeAssetTransfer`, :298-436, and the `_assetFound` gate at :264).
 *
 * The OP_RETURN vectors are the ones in the 2026-10-08 bounty intake, decoded with the wallet's
 * real [DigiAssetDecoder]; each expected allocation is what DigiAsset Core delivers (the intake
 * reproduction's Core port, cross-checked by hand). The point of every forged vector is the same:
 * the output the instructions name receives nothing the inputs did not carry.
 */
class AssetTransferAllocatorTest {

    private val X = "La" + "x".repeat(36)
    private val Y = "La" + "y".repeat(36)
    private val A = "La" + "a".repeat(36)
    private val B = "La" + "b".repeat(36)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun decode(s: String) = requireNotNull(DigiAssetDecoder().decode(hex(s))) { "vector $s did not decode" }
    private fun u(id: String, n: Long) = AssetUnits(id, n)
    private fun allocate(script: String, inputs: List<List<AssetUnits>?>, outputs: Int = 3) =
        AssetTransferAllocator.allocate(decode(script), inputs, outputs) { true }

    private fun allocated(r: Result): Map<Int, List<AssetUnits>> {
        assertTrue("expected an allocation, got $r", r is Result.Allocated)
        return (r as Result.Allocated).outputs
    }

    // ── The forged vectors: nothing reaches the named output ─────────────────────────────────

    /** BB-2026-10-08-rico: `[noskip→0,1][skip→0,999999]` from a one-unit carrier and a plain fee
     *  coin. The second instruction asks for units no input holds, which voids both; the one unit
     *  goes to the last output. */
    @Test fun over_assignment_from_a_one_unit_carrier_delivers_nothing_to_the_named_output() {
        val r = allocate("6a0b4441031500018060f423f0", listOf(listOf(u(X, 1)), emptyList()))
        assertEquals(mapOf(2 to listOf(u(X, 1))), allocated(r))
        assertTrue((r as Result.Allocated).instructionsVoided)
    }

    /** BB-2026-10-08-anmxx: 100 units to output 1, no input carries an asset. Not an asset transfer. */
    @Test fun a_transfer_with_no_asset_input_is_not_an_asset_transfer() {
        assertEquals(Result.NotAnAssetTransfer, allocate("6a0744410315012012", listOf(emptyList())))
    }

    /** BB-2026-10-08-fzn (01): 1,000,000 units to output 0 with zero asset inputs. */
    @Test fun a_million_units_from_no_asset_input_is_not_an_asset_transfer() {
        assertEquals(Result.NotAnAssetTransfer, allocate("6a0744410315002016", listOf(emptyList(), emptyList())))
    }

    /** BB-2026-10-08-toshit: one unit in, 1,000,000 declared. Voided; the unit goes to the last output. */
    @Test fun a_million_declared_from_one_unit_is_voided() {
        val r = allocate("6a0744410315002016", listOf(listOf(u(X, 1))))
        assertEquals(mapOf(2 to listOf(u(X, 1))), allocated(r))
    }

    // ── The honest control: exactly Core ─────────────────────────────────────────────────────

    @Test fun honest_five_of_nine_lands_five_and_returns_four_to_the_last_output() {
        val r = allocate("6a06444103150005", listOf(listOf(u(X, 9)), emptyList()))
        assertEquals(mapOf(0 to listOf(u(X, 5)), 2 to listOf(u(X, 4))), allocated(r))
        assertEquals(false, (r as Result.Allocated).instructionsVoided)
    }

    // ── Root cause 2: the units' asset is the input they came from, not input 0's ────────────

    /** BB-2026-10-08-luis: inputs [X:1][Y:1,000,000]; output 0 receives Y. */
    @Test fun output_zero_receives_the_second_inputs_asset() {
        val r = allocate("6a09444103158201002644", listOf(listOf(u(X, 1)), listOf(u(Y, 1_000_000))))
        assertEquals(mapOf(0 to listOf(u(Y, 1_000_000)), 2 to listOf(u(X, 1))), allocated(r))
    }

    /** BB-2026-10-08-tsaqif: inputs [B:10][A:10]; output 0 receives A, output 1 B. */
    @Test fun a_skip_past_the_first_carrier_delivers_the_second_carriers_asset() {
        val r = allocate("6a0844410315810a000a", listOf(listOf(u(B, 10)), listOf(u(A, 10))))
        assertEquals(mapOf(1 to listOf(u(B, 10)), 0 to listOf(u(A, 10))), allocated(r))
    }

    /** Plain DGB inputs are not part of the input order: a carrier at input 1 behind a fee coin at
     *  input 0 is drawn from first, with no skip bit. */
    @Test fun a_plain_first_input_is_not_in_the_draw_order() {
        val r = allocate("6a06444103150005", listOf(emptyList(), listOf(u(Y, 9))))
        assertEquals(mapOf(0 to listOf(u(Y, 5)), 2 to listOf(u(Y, 4))), allocated(r))
    }

    /** One input carrying two assets: the first instruction takes X off the top, the second Y. */
    @Test fun a_mixed_input_is_drawn_from_the_top_of_its_stack() {
        val script = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(false, false, false, 0, 3),
            DigiAssetEncoder.TransferInstruction(false, false, false, 1, 4),
        )).joinToString("") { "%02x".format(it) }
        val r = allocate(script, listOf(listOf(u(X, 3), u(Y, 4))))
        assertEquals(mapOf(0 to listOf(u(X, 3)), 1 to listOf(u(Y, 4))), allocated(r))
    }

    /** An instruction that runs from one asset into another voids everything. */
    @Test fun an_instruction_that_runs_into_another_asset_voids_the_transfer() {
        val script = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(false, false, false, 0, 5),
        )).joinToString("") { "%02x".format(it) }
        val r = allocate(script, listOf(listOf(u(X, 3), u(Y, 4))))
        assertEquals(mapOf(2 to listOf(u(X, 3), u(Y, 4))), allocated(r))
    }

    // ── Core details ─────────────────────────────────────────────────────────────────────────

    @Test fun an_instruction_naming_a_missing_output_voids_the_transfer() {
        val h = header(AssetOperation.TRANSFER, ti(5, 2))
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(u(X, 9))), 3) { true }
        assertEquals(mapOf(2 to listOf(u(X, 9))), allocated(r))
    }

    @Test fun range_credits_every_output_and_consumes_the_product() {
        val h = header(AssetOperation.TRANSFER, ti(1, 3, range = true))
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(u(X, 10))), 3) { true }
        assertEquals(mapOf(0 to listOf(u(X, 3)), 1 to listOf(u(X, 3)), 2 to listOf(u(X, 4))), allocated(r))
    }

    @Test fun output_31_destroys_only_in_a_burn() {
        val burn = AssetTransferAllocator.allocate(header(AssetOperation.BURN, ti(31, 4)), listOf(listOf(u(X, 10))), 3) { true }
        assertEquals(mapOf(2 to listOf(u(X, 6))), allocated(burn))
        // In a transfer, 31 is an ordinary output index; with three outputs it does not exist.
        val transfer = AssetTransferAllocator.allocate(header(AssetOperation.TRANSFER, ti(31, 4)), listOf(listOf(u(X, 10))), 3) { true }
        assertEquals(mapOf(2 to listOf(u(X, 10))), allocated(transfer))
        val wide = AssetTransferAllocator.allocate(header(AssetOperation.TRANSFER, ti(31, 4)), listOf(listOf(u(X, 10))), 33) { true }
        assertEquals(mapOf(31 to listOf(u(X, 4)), 32 to listOf(u(X, 6))), allocated(wide))
    }

    /** `allowSkip`: a skip right after an instruction that emptied its input does not skip again. */
    @Test fun a_skip_after_an_input_is_emptied_does_not_skip_the_next_input() {
        val h = header(AssetOperation.TRANSFER, ti(0, 2, skip = true), ti(1, 3))
        val r = AssetTransferAllocator.allocate(h, listOf(listOf(u(X, 2)), listOf(u(Y, 3))), 3) { true }
        assertEquals(mapOf(0 to listOf(u(X, 2)), 1 to listOf(u(Y, 3))), allocated(r))
    }

    /** Before version 3, an empty first input makes Core ignore every instruction. */
    @Test fun legacy_versions_ignore_instructions_when_the_first_input_is_empty() {
        val h = header(AssetOperation.TRANSFER, ti(0, 2), version = 2)
        val r = AssetTransferAllocator.allocate(h, listOf(emptyList(), listOf(u(X, 5))), 3) { true }
        assertEquals(mapOf(2 to listOf(u(X, 5))), allocated(r))
    }

    // ── Fails closed ─────────────────────────────────────────────────────────────────────────

    @Test fun an_unknown_input_is_indeterminate() {
        val r = allocate("6a06444103150005", listOf(listOf(u(X, 9)), null))
        assertTrue(r is Result.Indeterminate)
    }

    @Test fun a_percent_instruction_is_indeterminate() {
        val r = AssetTransferAllocator.allocate(header(AssetOperation.TRANSFER, ti(0, 128, percent = true)), listOf(listOf(u(X, 9))), 3) { true }
        assertTrue(r is Result.Indeterminate)
    }

    @Test fun an_asset_that_may_carry_rules_is_indeterminate() {
        val r = AssetTransferAllocator.allocate(header(AssetOperation.TRANSFER, ti(0, 1)), listOf(listOf(u(X, 9))), 3) { it != X }
        assertTrue(r is Result.Indeterminate)
    }

    @Test fun drawing_across_entries_of_a_hybrid_or_unreadable_asset_is_indeterminate() {
        val hybrid = "Lh" + "h".repeat(36)
        val h = header(AssetOperation.TRANSFER, ti(0, 5))
        assertTrue(AssetTransferAllocator.allocate(h, listOf(listOf(u(hybrid, 3)), listOf(u(hybrid, 3))), 3) { true } is Result.Indeterminate)
        assertTrue(AssetTransferAllocator.allocate(h, listOf(listOf(u("unresolved:t", 3)), listOf(u("unresolved:t", 3))), 3) { true } is Result.Indeterminate)
        // Within one entry, nothing is wrapped and the answer is exact.
        assertEquals(mapOf(0 to listOf(u(hybrid, 5)), 2 to listOf(u(hybrid, 1))),
            allocated(AssetTransferAllocator.allocate(h, listOf(listOf(u(hybrid, 6))), 3) { true }))
    }

    @Test fun issuance_is_not_allocated_here() {
        assertTrue(AssetTransferAllocator.allocate(header(AssetOperation.ISSUANCE), listOf(emptyList()), 2) { true } is Result.Indeterminate)
    }

    @Test fun overflow_is_indeterminate() {
        val h = header(AssetOperation.TRANSFER, ti(8000, Long.MAX_VALUE / 2, range = true))
        assertTrue(AssetTransferAllocator.allocate(h, listOf(listOf(u(X, 9))), 3) { true } is Result.Indeterminate)
    }

    // ── Conservation, and agreement with the independent model, over random transfers ────────

    /**
     * Random fixed-amount transfers over random aggregatable stacks, compared with the test model
     * the send path is checked against ([DigiAssetTransferModel], a separate port of the same Core
     * code). Also: no output ever holds more of an asset than the inputs carried, and every unit
     * is accounted for.
     */
    @Test fun agrees_with_the_reference_model_and_conserves_units() {
        val rnd = Random(20261008)
        val ids = listOf(X, Y, A)
        repeat(4000) {
            val inputCount = 1 + rnd.nextInt(4)
            val stacks = List(inputCount) {
                if (rnd.nextInt(3) == 0) emptyList()
                else List(1 + rnd.nextInt(2)) { u(ids[rnd.nextInt(ids.size)], 1L + rnd.nextInt(20)) }
                    .fold(mutableListOf<AssetUnits>()) { acc, e ->
                        // A stack holds each asset once, as an aggregatable asset does.
                        if (acc.none { it.assetId == e.assetId }) acc.add(e); acc
                    }
            }
            val outputCount = 1 + rnd.nextInt(4)
            val instructions = List(rnd.nextInt(4)) {
                ti(rnd.nextInt(outputCount + 1), rnd.nextInt(25).toLong(), skip = rnd.nextBoolean())
            }
            val h = header(AssetOperation.TRANSFER, *instructions.toTypedArray())
            val ours = AssetTransferAllocator.allocate(h, stacks, outputCount) { true }

            val model = DigiAssetTransferModel.apply(
                inputs = stacks.map { s -> DigiAssetTransferModel.Input("in", s.map { it.assetId to it.count }) },
                outputAddresses = List(outputCount) { "out$it" },
                instructions = instructions.map { DigiAssetTransferModel.Instruction(it.skip, it.outputIndex, it.amount) },
                ruleBearing = emptySet(),
            )
            if (stacks.all { it.isEmpty() }) {
                assertEquals(Result.NotAnAssetTransfer, ours)
                return@repeat
            }
            val got = allocated(ours)
            val expected = model.outputs.withIndex()
                .filter { it.value.isNotEmpty() }
                .associate { (vout, held) -> vout to held.map { (id, n) -> u(id, n) } }
            assertEquals("stacks=$stacks instr=$instructions outputs=$outputCount", expected, got)

            val carried = stacks.flatten().groupBy { it.assetId }.mapValues { e -> e.value.sumOf { it.count } }
            val delivered = got.values.flatten().groupBy { it.assetId }.mapValues { e -> e.value.sumOf { it.count } }
            assertEquals("units are conserved", carried, delivered)
        }
    }

    private fun ti(
        outputIndex: Int, amount: Long,
        range: Boolean = false, percent: Boolean = false, skip: Boolean = false,
    ) = TransferInstruction(
        skip = skip, range = range, percent = percent,
        outputIndex = outputIndex, amount = amount, isBurn = !range && outputIndex == 31,
    )

    private fun header(operation: AssetOperation, vararg instructions: TransferInstruction, version: Int = 3) =
        DecodedAssetHeader(
            version = version, opcode = 0x15, operation = operation,
            metadataHash = null, metadataCid = null,
            totalQuantity = null, divisibility = 0,
            locked = true, aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = instructions.toList(),
        )
}
