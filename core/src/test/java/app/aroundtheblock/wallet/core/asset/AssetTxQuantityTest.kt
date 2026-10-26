package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.AssetOperation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sovereign per-output token-quantity rules, incl. the RANGE support the old
 * consumer dropped. Semantics confirmed against RenzoDD/digiasset-core
 * DigiByteTransaction.cpp:257-329.
 */
class AssetTxQuantityTest {

    private fun header(
        operation: AssetOperation,
        totalQuantity: Long? = null,
        instructions: List<TransferInstruction> = emptyList(),
    ) = DecodedAssetHeader(
        version = 3, opcode = 0x15, operation = operation,
        metadataHash = null, metadataCid = null,
        totalQuantity = totalQuantity, divisibility = 0,
        locked = true, aggregation = Aggregation.AGGREGATABLE,
        transferInstructions = instructions,
    )

    private fun ti(
        outputIndex: Int, amount: Long,
        range: Boolean = false, percent: Boolean = false, isBurn: Boolean = false, skip: Boolean = false,
    ) = TransferInstruction(
        skip = skip, range = range, percent = percent,
        outputIndex = outputIndex, amount = amount, isBurn = isBurn,
    )

    @Test fun issuance_lands_total_on_first_non_opreturn() {
        val h = header(AssetOperation.ISSUANCE, totalQuantity = 1000)
        assertEquals(1000L, AssetTxQuantity.forOutput(h, vout = 0, firstNonOpReturnVout = 0))
        assertEquals(0L, AssetTxQuantity.forOutput(h, vout = 2, firstNonOpReturnVout = 0))
    }

    @Test fun fixed_transfer_only_target_output() {
        val h = header(AssetOperation.TRANSFER, instructions = listOf(ti(outputIndex = 0, amount = 20)))
        assertEquals(20L, AssetTxQuantity.forOutput(h, 0, null))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 1, null))
    }

    @Test fun range_transfer_hits_every_output_0_to_N() {
        val h = header(AssetOperation.TRANSFER, instructions = listOf(ti(outputIndex = 2, amount = 5, range = true)))
        assertEquals(5L, AssetTxQuantity.forOutput(h, 0, null))
        assertEquals(5L, AssetTxQuantity.forOutput(h, 1, null))
        assertEquals(5L, AssetTxQuantity.forOutput(h, 2, null))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 3, null)) // beyond the range
    }

    @Test fun burn_instruction_counts_zero() {
        val h = header(AssetOperation.BURN, instructions = listOf(ti(outputIndex = 31, amount = 100, isBurn = true)))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 0, null))
        assertEquals("the destroy marker credits nobody", 0L, AssetTxQuantity.forOutput(h, 31, null))
    }

    /** Output 31 is the destroy marker only in a BURN (DigiAsset_Core decodeAssetTransfer). In a
     *  TRANSFER with 32 or more outputs it is a real output and is credited like any other — even
     *  if an instruction was built flagged as a burn. */
    @Test fun transfer_to_output_31_is_credited() {
        for (flagged in listOf(false, true)) {
            val h = header(AssetOperation.TRANSFER, instructions = listOf(ti(outputIndex = 31, amount = 100, isBurn = flagged)))
            assertEquals("isBurn=$flagged", 100L, AssetTxQuantity.forOutput(h, 31, null))
            assertEquals("isBurn=$flagged", 0L, AssetTxQuantity.forOutput(h, 30, null))
        }
    }

    /** A burn transaction destroys what its index-31 instruction names and still delivers what
     *  its other instructions name — fixed and range alike — as the reference does. */
    @Test fun burn_operation_credits_its_non_burn_instructions() {
        val h = header(
            AssetOperation.BURN,
            instructions = listOf(
                ti(outputIndex = 31, amount = 60, isBurn = true),
                ti(outputIndex = 1, amount = 40),
                ti(outputIndex = 2, amount = 5, range = true),
            ),
        )
        assertEquals(5L, AssetTxQuantity.forOutput(h, 0, null))
        assertEquals(45L, AssetTxQuantity.forOutput(h, 1, null))
        assertEquals(5L, AssetTxQuantity.forOutput(h, 2, null))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 31, null))
        // 60 burned + 40 + 3 x 5 consumed = 115; the other 15 of 130 ride to the last output.
        assertEquals(15L, AssetTxQuantity.forOutputTotal(h, 3, null, inputUnits = 130L, outputCount = 4))
    }

    @Test fun percent_instruction_skipped() {
        val h = header(AssetOperation.TRANSFER, instructions = listOf(ti(outputIndex = 0, amount = 50, percent = true)))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 0, null))
    }

    @Test fun multiple_instructions_sum_per_output() {
        val h = header(
            AssetOperation.TRANSFER,
            instructions = listOf(
                ti(outputIndex = 0, amount = 10),            // fixed → out0
                ti(outputIndex = 1, amount = 5, range = true), // range → out0,out1
                ti(outputIndex = 1, amount = 3),             // fixed → out1
            ),
        )
        assertEquals(15L, AssetTxQuantity.forOutput(h, 0, null)) // 10 + 5
        assertEquals(8L, AssetTxQuantity.forOutput(h, 1, null))  // 5 + 3
    }
}
