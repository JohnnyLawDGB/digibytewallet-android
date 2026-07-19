package io.digibyte.core.asset

import io.digibyte.core.model.AssetOperation
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
        val h = header(AssetOperation.TRANSFER, instructions = listOf(ti(outputIndex = 31, amount = 100, isBurn = true)))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 0, null))
    }

    @Test fun burn_operation_counts_zero() {
        val h = header(AssetOperation.BURN, instructions = listOf(ti(0, 100)))
        assertEquals(0L, AssetTxQuantity.forOutput(h, 0, null))
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
