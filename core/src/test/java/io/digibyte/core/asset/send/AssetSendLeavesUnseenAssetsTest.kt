package io.digibyte.core.asset.send

import io.digibyte.core.asset.DigiAssetDecoder
import io.digibyte.core.db.entity.UtxoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What a send built by [AssetTransferPlanner] does under the protocol's own rules
 * ([DigiAssetTransferModel], a port of DigiAsset Core's transfer decode and rule check), when an
 * asset input carries something the wallet's row does not show.
 *
 * The planner is run for real and its outputs and encoded instructions are fed to the model, so
 * these follow the layout the wallet actually signs.
 */
class AssetSendLeavesUnseenAssetsTest {

    private val asset = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch"
    private val ruledAsset = "Ua7Q1ruledAssetIdForTheModel000000000"
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
            ruled = setOf(ruledAsset),
        )

    /** A ruled asset under the instructed one on the same input: the send moves what the wallet
     *  intended and the unseen asset stays at the source. */
    @Test fun an_unseen_asset_below_the_sent_one_stays_at_the_source() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 2)
        val r = run(p, mapOf("p" to listOf(asset to 5L, ruledAsset to 3L)))

        assertFalse("nothing is cleared", r.cleared)
        assertEquals(listOf(asset to 2L), r.outputs[0])
        assertEquals(listOf(asset to 3L, ruledAsset to 3L), r.outputs.last())
        assertEquals(addr(source), outputAddresses(p).last())
    }

    /** Red arm, the layout this replaces: asset change at a change address and DGB change last at
     *  another. The same input then sends the unseen ruled asset to a new address, its rule check
     *  fails, and every output is cleared. */
    @Test fun red_the_previous_layout_clears_every_output() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 2)
        val oldAddresses = listOf("recipient", "", "asset-change-address", "dgb-change-address")
        val oldInstructions = listOf(
            DigiAssetTransferModel.Instruction(false, 0, 2),
            DigiAssetTransferModel.Instruction(false, 2, 3),
        )
        val r = DigiAssetTransferModel.apply(
            inputs = p.inputs.map {
                DigiAssetTransferModel.Input(addr(it.scriptPubKey), if (it.txid == "p") listOf(asset to 5L, ruledAsset to 3L) else emptyList())
            },
            outputAddresses = oldAddresses,
            instructions = oldInstructions,
            ruled = setOf(ruledAsset),
        )
        assertTrue(r.cleared)
    }

    /** The whole holding sent, with the unseen asset under it: still nothing cleared. */
    @Test fun sending_the_whole_holding_leaves_the_unseen_asset_at_the_source() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 5)
        val r = run(p, mapOf("p" to listOf(asset to 5L, ruledAsset to 3L)))
        assertFalse(r.cleared)
        assertEquals(listOf(asset to 5L), r.outputs[0])
        assertEquals(listOf(ruledAsset to 3L), r.outputs.last())
    }

    /** The row over-states the asset (an unseen asset counted as this one): the instruction runs
     *  into the other asset, the protocol abandons the instructions and returns everything to the
     *  last output — the source. The send moves nothing, and nothing is lost. */
    @Test fun an_over_counted_row_returns_everything_to_the_source() {
        val p = plan(listOf(assetRow("p", 8)), listOf(feeCoin("f", source)), qty = 8)
        val r = run(p, mapOf("p" to listOf(asset to 5L, ruledAsset to 3L)))
        assertFalse(r.cleared)
        assertTrue(r.outputs[0].isEmpty())
        assertEquals(listOf(asset to 5L, ruledAsset to 3L), r.outputs.last())
    }

    /** The case the layout alone cannot cover: the row names this asset, the output holds the
     *  ruled one on top. The instruction moves the ruled asset to the recipient and every output
     *  is cleared. This is why every asset input is confirmed against the indexer first:
     *  [AssetInputCheck] refuses exactly this output. */
    @Test fun an_output_that_holds_another_asset_on_top_is_refused_by_the_input_check() {
        val p = plan(listOf(assetRow("p", 3)), listOf(feeCoin("f", source)), qty = 3)
        val r = run(p, mapOf("p" to listOf(ruledAsset to 3L)))
        assertTrue("the layout alone does not save this output", r.cleared)

        val verdict = AssetInputCheck.judge(
            StackLookup.Found(listOf(StackEntry(ruledAsset, 3L))), expectedAssetId = asset, recordedQuantity = 3,
        )
        assertEquals(AssetInputCheck.Verdict.MISMATCH, verdict)
    }

    /** A fee coin at another address carrying an unseen asset would add it to the leftover, which
     *  arrives at the source from elsewhere: rule check, everything cleared. Such a coin is not an
     *  eligible fee input unless the transaction that created it had no data output. */
    @Test fun a_fee_coin_from_elsewhere_must_come_from_a_transaction_without_data() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("z", elsewhere)), qty = 2)
        val r = run(p, mapOf("p" to listOf(asset to 5L), "z" to listOf(ruledAsset to 1L)))
        assertTrue("an unseen asset on a fee coin from elsewhere is a gain at the source", r.cleared)

        val z = feeCoin("z", elsewhere)
        assertFalse(AssetTransferPlanner.feeCoinEligible(z, source, parentHasDataOutput = true))
        assertFalse(AssetTransferPlanner.feeCoinEligible(z, source, parentHasDataOutput = null))
        assertTrue(AssetTransferPlanner.feeCoinEligible(z, source, parentHasDataOutput = false))
        assertTrue(AssetTransferPlanner.feeCoinEligible(feeCoin("x", source), source, parentHasDataOutput = true))
    }

    /** A fee coin at the source itself may carry anything: it stays at the source. */
    @Test fun a_fee_coin_at_the_source_keeps_its_unseen_asset_there() {
        val p = plan(listOf(assetRow("p", 5)), listOf(feeCoin("f", source)), qty = 2)
        val r = run(p, mapOf("p" to listOf(asset to 5L), "f" to listOf(ruledAsset to 1L)))
        assertFalse(r.cleared)
        assertTrue(r.outputs.last().contains(ruledAsset to 1L))
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
