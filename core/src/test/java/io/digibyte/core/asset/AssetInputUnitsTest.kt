package io.digibyte.core.asset

import io.digibyte.core.model.AssetOperation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolving how many asset units a transaction's inputs carried — the missing half of the
 * implicit-change rule (see [AssetImplicitChangeTest]). The answer must be exact or
 * explicitly unknown: a guessed input total propagates into `sendAsset`'s OP_RETURN, where
 * over-stating it makes digiasset-core reject the transfer and burn the whole input.
 */
class AssetInputUnitsTest {

    private fun outpoints(vararg pairs: Pair<String, Int>) = pairs.toList()

    /** Nothing directs units to the row asked about: it is ordinary DGB change. */
    private val nothingTargetsIt: suspend (String, Int) -> Boolean? = { _, _ -> false }

    /** For inputs whose answer must not depend on the targeting question at all. */
    private val neverAsked: suspend (String, Int) -> Boolean? =
        { txid, vout -> throw AssertionError("asked whether $txid:$vout is targeted") }

    @Test fun sums_the_quantities_of_inputs_we_hold_rows_for() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("aa" to 0, "aa" to 2),
            rowQuantity = { txid, vout -> if (txid == "aa" && vout == 0) 100L else 0L },
            isAssetTx = { true },
            rowIsTargeted = nothingTargetsIt,
        )
        assertEquals(100L, units)
    }

    @Test fun an_input_from_a_plain_dgb_tx_contributes_nothing() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "plaindgb" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else null },
            isAssetTx = { txid -> txid == "asset" },
            rowIsTargeted = neverAsked,
        )
        assertEquals(40L, units)
    }

    /**
     * An untracked input from a transaction that DOES carry DigiAsset data could be holding
     * any number of units. Counting it as zero would under-state the input total and silently
     * strand the difference; the honest answer is "unknown".
     */
    @Test fun an_untracked_input_from_an_asset_tx_is_unknown() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "otherasset" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else null },
            isAssetTx = { true },
            rowIsTargeted = neverAsked,
        )
        assertNull(units)
    }

    /** A funding transaction we cannot retrieve tells us nothing, so neither can we. */
    @Test fun an_unretrievable_funding_tx_is_unknown() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "gone" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else null },
            isAssetTx = { txid -> if (txid == "gone") null else false },
            rowIsTargeted = neverAsked,
        )
        assertNull(units)
    }

    /**
     * The live case: mainnet tx 6aa6d5c9… spends both outputs of the issuance tx —
     * the 100-unit marker at vout 0 and the plain DGB change at vout 2, which the wallet
     * also holds a row for at quantity 0. Total in: 100.
     */
    @Test fun resolves_the_live_transfer_inputs_to_one_hundred() = runBlocking {
        val issuance = "ee47fe8bacfceba55e8f99717e7133d991a74dc29fc1ee48ab370ee4c7e83de2"
        val units = resolveInputAssetUnits(
            inputs = outpoints(issuance to 0, issuance to 2),
            rowQuantity = { _, vout -> if (vout == 0) 100L else 0L },
            isAssetTx = { true },
            // Nothing in the issuance directs units to its vout 2: it is the issuer's DGB change.
            rowIsTargeted = { _, vout -> vout == 0 },
        )
        assertEquals(100L, units)
    }

    // ── A stored quantity of zero ─────────────────────────────────────────
    //
    // Every owned output of an asset transaction gets a row, and a row's quantity starts at zero.
    // So a zero is stored both for ordinary DGB change and for an output that does carry units
    // this layer could not total (a percent target, a remainder left unknown). Only the first is
    // a quantity. Which one a row is, is a fact about the transaction that created it.

    @Test fun a_stored_zero_on_a_targeted_input_is_unknown() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "carrier" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else 0L },
            isAssetTx = { true },
            rowIsTargeted = { txid, _ -> txid == "carrier" },
        )
        assertNull("a targeted row with a stored zero could hold any number of units", units)
    }

    @Test fun a_stored_zero_whose_targeting_has_no_answer_is_unknown() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "carrier" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else 0L },
            isAssetTx = { true },
            rowIsTargeted = { _, _ -> null },
        )
        assertNull("no answer is not the answer 'plain change'", units)
    }

    /** GUARD. Ordinary DGB change of an earlier asset transaction, spent as a fee coin. */
    @Test fun a_stored_zero_that_nothing_targets_contributes_nothing() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "earlier" to 3),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else 0L },
            isAssetTx = { true },
            rowIsTargeted = nothingTargetsIt,
        )
        assertEquals(40L, units)
    }

    /** GUARD. A positive quantity is a resolved quantity; the targeting question is not put. */
    @Test fun a_positive_row_sums_without_the_targeting_question() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "asset" to 1),
            rowQuantity = { _, vout -> if (vout == 0) 40L else 2L },
            isAssetTx = { true },
            rowIsTargeted = neverAsked,
        )
        assertEquals(42L, units)
    }

    /**
     * What the rule is for. One instruction, 10 units to output 0; the inputs are a resolved
     * 10-unit row and a targeted row with a stored zero. The remainder cannot be ruled out, so
     * the targeting rule names the last output.
     */
    @Test fun the_last_output_of_such_a_transaction_is_named_by_the_targeting_rule() = runBlocking {
        val header = DecodedAssetHeader(
            version = 3, opcode = 0x15, operation = AssetOperation.TRANSFER,
            metadataHash = null, metadataCid = null, totalQuantity = null, divisibility = 0,
            locked = true, aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = listOf(
                TransferInstruction(skip = false, range = false, percent = false, outputIndex = 0, amount = 10L, isBurn = false),
            ),
        )
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "carrier" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 10L else 0L },
            isAssetTx = { true },
            rowIsTargeted = { txid, _ -> txid == "carrier" },
        )
        assertTrue(
            "the last output may carry what the zero row held",
            AssetTxQuantity.targetsOutput(header, vout = 3, firstNonOpReturnVout = 0, inputUnits = units, outputCount = 4),
        )
        assertFalse(
            "GUARD: an output in between, which nothing names, is still ordinary change",
            AssetTxQuantity.targetsOutput(header, vout = 2, firstNonOpReturnVout = 0, inputUnits = units, outputCount = 4),
        )
    }

    // ── Saying why ────────────────────────────────────────────────────────

    @Test fun names_the_zero_rows_when_they_are_the_whole_reason() = runBlocking {
        val why = ZeroRowInputs()
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "carrier" to 1, "plaindgb" to 0, "carrier2" to 5),
            rowQuantity = { txid, _ -> when (txid) { "asset" -> 40L; "plaindgb" -> null; else -> 0L } },
            isAssetTx = { txid -> txid != "plaindgb" },
            rowIsTargeted = { _, _ -> true },
            unresolved = why,
        )
        assertNull(units)
        assertEquals(listOf("carrier" to 1, "carrier2" to 5), why.rows)
        assertEquals("what every other input carried", 40L, why.otherUnits)
    }

    /** An input that is unknown for another reason makes the total unknown whatever the zero
     *  rows are, so they are not given as the reason. */
    @Test fun names_no_rows_when_another_input_is_unknown_anyway() = runBlocking {
        for (order in listOf(
            outpoints("carrier" to 1, "otherasset" to 0),
            outpoints("otherasset" to 0, "carrier" to 1),
        )) {
            val why = ZeroRowInputs()
            val units = resolveInputAssetUnits(
                inputs = order,
                rowQuantity = { txid, _ -> if (txid == "carrier") 0L else null },
                isAssetTx = { true },
                rowIsTargeted = { _, _ -> true },
                unresolved = why,
            )
            assertNull(units)
            assertTrue("$order: ${why.rows}", why.rows.isEmpty())
        }
    }

    @Test fun names_no_rows_when_the_total_is_known() = runBlocking {
        val why = ZeroRowInputs()
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "earlier" to 3),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else 0L },
            isAssetTx = { true },
            rowIsTargeted = nothingTargetsIt,
            unresolved = why,
        )
        assertEquals(40L, units)
        assertTrue(why.rows.isEmpty())
    }
}
