package io.digibyte.core.asset.send

import io.digibyte.core.asset.DigiAssetDecoder
import io.digibyte.core.db.entity.UtxoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Where a send built by [AssetTransferPlanner] leaves every unit, under the protocol's own
 * assignment rules ([DigiAssetTransferModel], a port of DigiAsset Core's transfer decode and rule
 * check): the units its instructions name go where they say, and every other unit on its inputs
 * stays at the source address.
 *
 * The planner is run for real and its outputs and encoded instructions are fed to the model, so
 * these follow the layout the wallet actually signs.
 */
class AssetSendSourceAddressTest {

    private val asset = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch"
    private val otherAsset = "Ua7Q1otherAssetIdForTheModel000000000"
    private val source = byteArrayOf(0x00, 0x14, 1, 2, 3)          // the address the asset sits at
    private val elsewhere = byteArrayOf(0x00, 0x14, 9, 9, 9)       // another address of this wallet

    private fun assetRow(txid: String, qty: Long, script: ByteArray = source) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = DA_MARKER_SATS, blockHeight = 100L,
        isAsset = true, assetId = asset, assetQuantity = qty,
    )

    private fun feeCoin(txid: String, script: ByteArray, sats: Long = 1_000_000L) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = sats, blockHeight = 100L,
    )

    private fun plan(assets: List<UtxoEntity>, fees: List<UtxoEntity>, qty: Long): AssetTransferPlan =
        when (val r = AssetTransferPlanner.plan(assets, fees, qty, 100_000L)) {
            is AssetTransferPlanner.Result.Ready -> r.plan
            is AssetTransferPlanner.Result.Refused -> { fail("refused: ${r.message}"); throw AssertionError() }
        }

    private fun addr(script: ByteArray) = script.joinToString("") { "%02x".format(it) }

    /** The plan's outputs as the model sees them: who each pays. */
    private fun outputAddresses(p: AssetTransferPlan) = p.outputs.map {
        when (it.role) {
            PlannedOutput.Role.RECIPIENT_MARKER -> "recipient"
            PlannedOutput.Role.ASSET_DATA -> ""
            PlannedOutput.Role.DGB_CHANGE, PlannedOutput.Role.ASSET_CHANGE_MARKER -> addr(p.sourceScript)
        }
    }

    private fun instructions(p: AssetTransferPlan) =
        DigiAssetDecoder().decode(p.opReturnScript)!!.transferInstructions.map {
            DigiAssetTransferModel.Instruction(it.skip, it.outputIndex, it.amount)
        }

    private fun run(p: AssetTransferPlan, stacks: Map<String, List<Pair<String, Long>>>): DigiAssetTransferModel.Result =
        DigiAssetTransferModel.apply(
            inputs = p.inputs.map { DigiAssetTransferModel.Input(addr(it.scriptPubKey), stacks[it.txid] ?: emptyList()) },
            outputAddresses = outputAddresses(p),
            instructions = instructions(p),
            ruleBearing = setOf(otherAsset),
        )

    /** A second entry under the sent asset on the same input: the send moves what its
     *  instructions name, and the second entry stays at the source. */
    @Test fun units_the_instructions_do_not_assign_stay_at_the_source() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 2)
        val r = run(p, mapOf("p" to listOf(asset to 5L, otherAsset to 3L)))

        assertFalse(r.ruleCheckFailed)
        assertEquals(listOf(asset to 2L), r.outputs[0])
        assertEquals(listOf(asset to 3L, otherAsset to 3L), r.outputs.last())
        assertEquals(addr(source), outputAddresses(p).last())
    }

    /** Red arm, the layout this replaces: asset change at a change address and DGB change last at
     *  another. The unassigned units then arrive at an address they did not come from. */
    @Test fun red_the_previous_layout_moves_unassigned_units_away_from_the_source() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 2)
        val oldAddresses = listOf("recipient", "", "asset-change-address", "dgb-change-address")
        val oldInstructions = listOf(
            DigiAssetTransferModel.Instruction(false, 0, 2),
            DigiAssetTransferModel.Instruction(false, 2, 3),
        )
        val r = DigiAssetTransferModel.apply(
            inputs = p.inputs.map {
                DigiAssetTransferModel.Input(addr(it.scriptPubKey), if (it.txid == "p") listOf(asset to 5L, otherAsset to 3L) else emptyList())
            },
            outputAddresses = oldAddresses,
            instructions = oldInstructions,
            ruleBearing = setOf(otherAsset),
        )
        assertTrue(r.ruleCheckFailed)
    }

    /** The whole holding sent, with a second entry under it: the second entry stays at the source. */
    @Test fun sending_the_whole_holding_keeps_other_entries_at_the_source() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 5)
        val r = run(p, mapOf("p" to listOf(asset to 5L, otherAsset to 3L)))
        assertFalse(r.ruleCheckFailed)
        assertEquals(listOf(asset to 5L), r.outputs[0])
        assertEquals(listOf(otherAsset to 3L), r.outputs.last())
    }

    /** The row states more units than the input's first entry holds: the instruction runs into the
     *  next entry, the protocol abandons the instructions and returns everything to the last
     *  output — the source. The send moves nothing. */
    @Test fun a_quantity_above_the_first_entry_returns_everything_to_the_source() {
        val p = plan(listOf(assetRow("p", 8)), listOf(feeCoin("f", source)), qty = 8)
        val r = run(p, mapOf("p" to listOf(asset to 5L, otherAsset to 3L)))
        assertFalse(r.ruleCheckFailed)
        assertTrue(r.outputs[0].isEmpty())
        assertEquals(listOf(asset to 5L, otherAsset to 3L), r.outputs.last())
    }

    /** The layout covers units the instructions do not assign; it cannot cover an input whose
     *  first entry is not the asset the row names, because the instruction itself moves that
     *  entry. Every asset input is therefore confirmed against the indexer first, and
     *  [AssetInputCheck] refuses exactly this input. */
    @Test fun an_input_whose_first_entry_is_another_asset_is_refused_by_the_input_check() {
        val p = plan(listOf(assetRow("p", 3)), listOf(feeCoin("f", source)), qty = 3)
        val r = run(p, mapOf("p" to listOf(otherAsset to 3L)))
        assertTrue("the layout alone does not keep this input's entry at the source", r.ruleCheckFailed)

        val verdict = AssetInputCheck.judge(
            StackLookup.Found(listOf(StackEntry(otherAsset, 3L))), expectedAssetId = asset, recordedQuantity = 3,
        )
        assertEquals(AssetInputCheck.Verdict.MISMATCH, verdict)
    }

    /** A fee coin at another address with an asset entry would add it to the leftover, which then
     *  arrives at the source from elsewhere. Such a coin is not an eligible fee input unless the
     *  transaction that created it had no data output. */
    @Test fun a_fee_coin_from_elsewhere_must_come_from_a_transaction_without_data() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("z", elsewhere)), qty = 2)
        val r = run(p, mapOf("p" to listOf(asset to 5L), "z" to listOf(otherAsset to 1L)))
        assertTrue("an entry on a fee coin from elsewhere arrives at the source", r.ruleCheckFailed)

        val z = feeCoin("z", elsewhere)
        assertFalse(AssetTransferPlanner.feeCoinEligible(z, source, parentHasDataOutput = true))
        assertFalse(AssetTransferPlanner.feeCoinEligible(z, source, parentHasDataOutput = null))
        assertTrue(AssetTransferPlanner.feeCoinEligible(z, source, parentHasDataOutput = false))
        assertTrue(AssetTransferPlanner.feeCoinEligible(feeCoin("x", source), source, parentHasDataOutput = true))
    }

    /** A fee coin at the source itself keeps whatever it holds at the source. */
    @Test fun a_fee_coin_at_the_source_keeps_its_entries_there() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 2)
        val r = run(p, mapOf("p" to listOf(asset to 5L), "f" to listOf(otherAsset to 1L)))
        assertFalse(r.ruleCheckFailed)
        assertTrue(r.outputs.last().contains(otherAsset to 1L))
    }

    /** Every send's last output pays the source, and the asset-change instruction (when there is
     *  one) names that output. */
    @Test fun the_last_output_is_the_source_and_carries_the_change() {
        for ((qty, fees) in listOf(2L to listOf(feeCoin("f", source)), 5L to listOf(feeCoin("f", source)))) {
            val p = plan(listOf(assetRow("p", 5)), fees, qty)
            assertEquals(PlannedOutput.Role.ASSET_CHANGE_MARKER, p.outputs.last().role)
            val change = instructions(p).filter { it.output != 0 }
            assertTrue(change.all { it.output == p.outputs.lastIndex })
        }
    }

    /** Asset inputs at two addresses are never planned together. */
    @Test fun asset_inputs_from_two_addresses_are_refused() {
        val r = AssetTransferPlanner.plan(
            listOf(assetRow("p", 2), assetRow("q", 2, elsewhere)), listOf(feeCoin("f", source)), 3, 100_000L,
        )
        assertTrue(r is AssetTransferPlanner.Result.Refused)
    }

    @Test fun source_groups_keep_only_addresses_that_cover_the_amount_largest_first() {
        val rows = listOf(assetRow("a", 2), assetRow("b", 3), assetRow("c", 4, elsewhere), assetRow("d", 1, byteArrayOf(7)))
        val groups = AssetTransferPlanner.sourceGroups(rows, quantity = 4)
        assertEquals(listOf(setOf("a", "b"), setOf("c")), groups.map { g -> g.map { it.txid }.toSet() })
        assertEquals(5L, AssetTransferPlanner.largestSingleSource(rows))
        assertTrue(AssetTransferPlanner.sourceGroups(rows, quantity = 6).isEmpty())
    }
}
