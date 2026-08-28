package io.digibyte.core.recovery

import io.digibyte.core.asset.DigiAssetDecoder
import io.digibyte.core.asset.send.DA_MARKER_SATS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building the transaction that moves a DigiAsset OUT of a wallet the user is leaving.
 *
 * ## What this closes
 *
 * The sweep already refuses to spend an asset-bearing UTXO as plain DGB — doing so destroys the
 * asset rather than moving it — and [AssetFeeReserve] already holds back DGB so the asset can
 * pay its own way out later. The app tells the user exactly that: the assets "were left in the
 * old wallet along with enough DGB to move them later."
 *
 * There was no later. Nothing built the transfer. This is that transaction.
 *
 * ## The invariant the layout exists to protect
 *
 * A DigiAsset transfer's OP_RETURN assigns units to specific outputs, and the protocol credits
 * anything left unassigned to the transaction's LAST output. Our unit count can be an
 * under-estimate — [io.digibyte.core.asset.AssetTxQuantity] deliberately skips `percent`
 * instructions rather than invent a number — so the plan is built so that **every output belongs
 * to the destination wallet.** Under-count the units and the remainder still lands with the user
 * instead of being burned. That property, not the arithmetic, is what makes this safe to ship.
 */
class ForeignAssetTransferPlanTest {

    private val dest = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f"
    private val feePerKb = 100_000L

    private fun spend(txid: String, sats: Long, chain: Int = 0, index: Int = 0) =
        ForeignAssetTransferPlan.Spend(
            txid = txid, vout = 0, amountSat = sats,
            scriptPubKeyHex = "76a914aabbccddeeff00112233445566778899aabbccdd88ac",
            chain = chain, index = index,
        )

    /** The asset's own marker output — 6,000 sats, as DigiAsset convention puts it. */
    private val assetUtxo = spend("a55e700000000000000000000000000000000000000000000000000000000001", DA_MARKER_SATS)
    /** Plain DGB held back by the reserve to pay for this move. */
    private val feeUtxo = spend("feeeee00000000000000000000000000000000000000000000000000000000002", 200_000L, chain = 1, index = 3)

    private fun ok(): ForeignAssetTransferPlan.Plan {
        val r = ForeignAssetTransferPlan.build(
            assetInput = assetUtxo, assetUnits = 10L,
            feeInputs = listOf(feeUtxo), destAddress = dest, feePerKb = feePerKb,
        )
        assertTrue("expected a plan, got $r", r is ForeignAssetTransferPlan.Result.Ok)
        return (r as ForeignAssetTransferPlan.Result.Ok).plan
    }

    // ---- the layout ---------------------------------------------------------------------------

    @Test fun `the asset input is spent first so instructions can reference input zero`() {
        val plan = ok()
        assertEquals(assetUtxo.txid, plan.inputs.first().txid)
        assertEquals(listOf(assetUtxo, feeUtxo), plan.inputs)
    }

    @Test fun `the layout is marker then OP_RETURN then change`() {
        val plan = ok()
        assertEquals(3, plan.outputs.size)

        assertEquals("recipient marker at vout 0", dest, plan.outputs[0].address)
        assertEquals(DA_MARKER_SATS, plan.outputs[0].amountSat)

        assertEquals("OP_RETURN carries no address", "", plan.outputs[1].address)
        assertEquals(0L, plan.outputs[1].amountSat)
        assertTrue("OP_RETURN script is present", plan.outputs[1].scriptHex.isNotEmpty())
    }

    /**
     * The safety property. Unassigned units are credited to the LAST output, so the last output
     * must be ours — otherwise an under-counted transfer burns the remainder.
     */
    @Test fun `every output belongs to the destination wallet`() {
        val plan = ok()
        val valueOutputs = plan.outputs.filter { it.address.isNotEmpty() }
        assertTrue("nothing goes back to the old wallet", valueOutputs.all { it.address == dest })
        assertEquals("the last output is ours, so residual units land with the user",
            dest, plan.outputs.last().address)
    }

    @Test fun `the OP_RETURN sends the whole quantity to the marker output`() {
        val plan = ok()
        val script = plan.outputs[1].scriptHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val header = DigiAssetDecoder().decode(script)
        assertNotNull("the marker must decode as a DigiAsset transfer", header)
        val inst = header!!.transferInstructions.single()
        assertEquals("units land on the recipient marker", 0, inst.outputIndex)
        assertEquals(10L, inst.amount)
        assertTrue("an absolute amount, never a percentage", !inst.percent)
        assertTrue("not a range instruction", !inst.range)
        assertTrue("not a burn", !inst.isBurn)
    }

    // ---- the money ----------------------------------------------------------------------------

    @Test fun `value is conserved — the fee is exactly what the outputs do not claim`() {
        val plan = ok()
        val totalIn = plan.inputs.sumOf { it.amountSat }
        val totalOut = plan.outputs.sumOf { it.amountSat }
        assertEquals(totalIn - totalOut, plan.feeSat)
        assertTrue("the fee is positive", plan.feeSat > 0)
    }

    /**
     * The native signer refuses an implied fee above 3x what the size justifies, because a fee
     * that large means the caller lost track of value — it is a burn, and it is silent. The plan
     * has to land inside that band or it can never be signed.
     */
    @Test fun `the implied fee lands inside the band the native signer will accept`() {
        val plan = ok()
        val estSize = 10 + plan.inputs.size * 160 + plan.outputs.size * 34
        val expected = (estSize.toLong() * feePerKb) / 1000L
        assertTrue("fee ${plan.feeSat} is below the relay minimum $expected", plan.feeSat >= expected)
        assertTrue("fee ${plan.feeSat} exceeds 3x $expected and would be refused",
            plan.feeSat <= expected * 3)
    }

    // ---- the refusals -------------------------------------------------------------------------

    /**
     * Without a change output the last output is the OP_RETURN, and residual units are burned.
     * Refusing is the only safe answer: an unmoved asset can be moved tomorrow.
     */
    @Test fun `too little DGB to leave a change output is refused, not squeezed`() {
        val r = ForeignAssetTransferPlan.build(
            assetInput = assetUtxo, assetUnits = 10L,
            feeInputs = listOf(spend("sma11", 45_000L)), destAddress = dest, feePerKb = feePerKb,
        )
        val refused = r as? ForeignAssetTransferPlan.Result.Refused
        assertNotNull("expected a refusal, got $r", refused)
        assertEquals(ForeignAssetTransferPlan.Reason.INSUFFICIENT_FEE_FUNDS, refused!!.reason)
    }

    @Test fun `no fee inputs at all is refused`() {
        val r = ForeignAssetTransferPlan.build(
            assetInput = assetUtxo, assetUnits = 10L,
            feeInputs = emptyList(), destAddress = dest, feePerKb = feePerKb,
        )
        assertTrue(r is ForeignAssetTransferPlan.Result.Refused)
    }

    /**
     * A quantity of zero cannot be encoded — the transfer payload requires at least one
     * instruction — and it also means we do not actually know what is on this outpoint. Building
     * a transfer around a number we do not have is the one thing that could destroy the asset.
     */
    @Test fun `a zero or unknown quantity is refused rather than guessed`() {
        listOf(0L, -1L).forEach { units ->
            val r = ForeignAssetTransferPlan.build(
                assetInput = assetUtxo, assetUnits = units,
                feeInputs = listOf(feeUtxo), destAddress = dest, feePerKb = feePerKb,
            )
            val refused = r as? ForeignAssetTransferPlan.Result.Refused
            assertNotNull("units=$units must refuse, got $r", refused)
            assertEquals(ForeignAssetTransferPlan.Reason.UNKNOWN_QUANTITY, refused!!.reason)
        }
    }

    @Test fun `an empty destination address is refused`() {
        val r = ForeignAssetTransferPlan.build(
            assetInput = assetUtxo, assetUnits = 10L,
            feeInputs = listOf(feeUtxo), destAddress = "  ", feePerKb = feePerKb,
        )
        assertTrue(r is ForeignAssetTransferPlan.Result.Refused)
    }

    /**
     * The reserve holds back whole UTXOs, so a wallet can arrive here with several small ones.
     * They all have to be spendable into the same transfer or the fee cannot be met.
     */
    @Test fun `several small fee inputs are combined`() {
        val smalls = listOf(spend("s1", 40_000L), spend("s2", 40_000L), spend("s3", 40_000L))
        val r = ForeignAssetTransferPlan.build(
            assetInput = assetUtxo, assetUnits = 10L,
            feeInputs = smalls, destAddress = dest, feePerKb = feePerKb,
        )
        val plan = (r as ForeignAssetTransferPlan.Result.Ok).plan
        assertEquals(4, plan.inputs.size)
        assertEquals(dest, plan.outputs.last().address)
    }
}
