package io.digibyte.core.asset

import io.digibyte.core.model.AssetOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The DigiAssets implicit-change rule: after the explicit transfer instructions are
 * applied, every input unit they did NOT assign is credited to the transaction's LAST
 * output. Confirmed against DigiAsset_Core `DigiByteTransaction.cpp`
 * `decodeAssetTransfer` ("//see if any change" — `lastOutput = _outputs.size() - 1`).
 *
 * The wallet ignored this rule, so an asset received from any tool that lets its change
 * ride implicitly (bread-era wallets, digiasset-core) under-counted by the whole change.
 */
class AssetImplicitChangeTest {

    private fun transfer(instructions: List<TransferInstruction>) = DecodedAssetHeader(
        version = 3, opcode = 0x15, operation = AssetOperation.TRANSFER,
        metadataHash = null, metadataCid = null,
        totalQuantity = null, divisibility = 0,
        locked = true, aggregation = Aggregation.AGGREGATABLE,
        transferInstructions = instructions,
    )

    private fun ti(
        outputIndex: Int, amount: Long,
        range: Boolean = false, percent: Boolean = false, isBurn: Boolean = false,
    ) = TransferInstruction(
        skip = false, range = range, percent = percent,
        outputIndex = outputIndex, amount = amount, isBurn = isBurn,
    )

    /**
     * The live failure. Mainnet tx 6aa6d5c92b2bf0d2368aaf718e596e84764a52ba7eaabbcd336b17a483d5a04f
     * carries OP_RETURN `6a0644410115000a`: ONE instruction, 10 units to vout 0. Its input
     * held 100 units of La4WAqZf…, so 90 ride to the last output (vout 3). The explorer
     * agrees: 90 @ DQcps2q. The wallet credited it 0 and displayed 10 instead of 100.
     */
    @Test fun leftover_units_are_credited_to_the_last_output() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 10)))
        assertEquals(90L, AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 4))
    }

    /**
     * A send this wallet built itself (db5480e1…, `6a094441031500050225f0`) assigns every
     * unit explicitly — 5 to the recipient, 95 to the asset-change marker. Nothing rides
     * implicitly, so the DGB change output must stay unencumbered.
     */
    @Test fun fully_assigned_transfer_leaves_no_implicit_change() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 5), ti(outputIndex = 2, amount = 95)))
        assertEquals(0L, AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 4))
    }

    /**
     * A range instruction CONSUMES `(outputIndex + 1) * amount` from the inputs even though
     * it credits `amount` to each output — the asymmetry in the reference
     * (`totalAmount = range ? (output + 1) * amount : amount`). Getting this wrong
     * manufactures phantom change.
     */
    @Test fun range_instruction_consumes_amount_per_covered_output() {
        val h = transfer(listOf(ti(outputIndex = 2, amount = 10, range = true)))
        assertEquals(70L, AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 4))
    }

    /** A burn consumes its units from the inputs — they are destroyed, not left over. */
    @Test fun burn_instruction_consumes_its_units() {
        val h = transfer(listOf(ti(outputIndex = 31, amount = 40, isBurn = true)))
        assertEquals(60L, AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 4))
    }

    /** Percent instructions are unresolvable here, so the answer is "unknown" — never a number. */
    @Test fun percent_instruction_yields_unknown() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 128, percent = true)))
        assertNull(AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 4))
    }

    /** Unknown input units stay unknown — the display must never guess a quantity. */
    @Test fun unknown_input_units_yield_unknown() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 10)))
        assertNull(AssetTxQuantity.implicitChange(h, inputUnits = null, outputCount = 4))
    }

    /** Instructions that over-assign relative to the inputs we resolved: clamp at 0, never negative. */
    @Test fun over_assignment_clamps_to_zero() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 150)))
        assertEquals(0L, AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 4))
    }

    /**
     * An issuance assigns its whole supply by convention, so there is no implicit change to
     * credit — and crediting one would double-count the issuer's marker.
     */
    @Test fun issuance_has_no_implicit_change() {
        val h = DecodedAssetHeader(
            version = 3, opcode = 0x01, operation = AssetOperation.ISSUANCE,
            metadataHash = null, metadataCid = null,
            totalQuantity = 100L, divisibility = 0,
            locked = true, aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = listOf(ti(outputIndex = 0, amount = 100)),
        )
        assertEquals(0L, AssetTxQuantity.implicitChange(h, inputUnits = 0L, outputCount = 3))
    }

    /**
     * A burn-opcode transaction still runs the change step in the reference
     * (`decodeAssetTransfer` is called for `DIGIASSET_BURN` and the "see if any change"
     * block is unconditional), so units the burn instructions don't consume survive on the
     * last output rather than vanishing.
     */
    @Test fun burn_opcode_tx_still_credits_its_leftover() {
        val h = DecodedAssetHeader(
            version = 3, opcode = 0x25, operation = AssetOperation.BURN,
            metadataHash = null, metadataCid = null,
            totalQuantity = null, divisibility = 0,
            locked = true, aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = listOf(ti(outputIndex = 31, amount = 25, isBurn = true)),
        )
        assertEquals(75L, AssetTxQuantity.implicitChange(h, inputUnits = 100L, outputCount = 3))
    }

    /**
     * The single entry point detection uses: explicit instructions PLUS the implicit
     * remainder on the last output. Driven with tx 6aa6d5c9…'s real shape (4 outputs, one
     * instruction of 10 to vout 0, 100 units in): vout 0 keeps its 10, vout 3 gains the 90,
     * and the outputs in between stay at zero.
     */
    @Test fun total_for_output_adds_the_implicit_remainder_to_the_last_output() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 10)))
        fun total(vout: Int) = AssetTxQuantity.forOutputTotal(
            h, vout, firstNonOpReturnVout = 0, inputUnits = 100L, outputCount = 4,
        )
        assertEquals(10L, total(0))
        assertEquals(0L, total(2))
        assertEquals(90L, total(3))
    }

    /** Unknown input units must not invent a credit — the last output stays at what the
     *  explicit instructions gave it. */
    @Test fun total_for_output_credits_nothing_when_input_units_are_unknown() {
        val h = transfer(listOf(ti(outputIndex = 0, amount = 10)))
        assertEquals(0L, AssetTxQuantity.forOutputTotal(
            h, vout = 3, firstNonOpReturnVout = 0, inputUnits = null, outputCount = 4,
        ))
    }

    /**
     * The credit lands on the LAST output verbatim — [AssetTxQuantity.implicitChangeVout]
     * is `outputCount - 1`, even when that output is the OP_RETURN (the reference credits
     * it there too, which burns the remainder; mirroring it beats "improving" it).
     */
    @Test fun implicit_change_targets_the_final_output_index() {
        assertEquals(3, AssetTxQuantity.implicitChangeVout(outputCount = 4))
        assertEquals(0, AssetTxQuantity.implicitChangeVout(outputCount = 1))
    }
}
