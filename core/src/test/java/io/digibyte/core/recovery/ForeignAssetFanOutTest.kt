package io.digibyte.core.recovery

import io.digibyte.core.asset.send.DA_MARKER_SATS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting one DGB output into enough fee outputs that every asset can move.
 *
 * ## The problem
 *
 * An asset moves in its own transaction, and two transactions cannot spend the same UTXO, so
 * moving N assets needs N spendable outputs. The transfer's change goes to the DESTINATION
 * wallet, so it never comes back to fund the next move.
 *
 * A wallet holding 50 assets and one DGB output therefore moves exactly one, and the only remedy
 * is sending DGB back into a wallet the user is leaving. That shape is common, not hypothetical.
 *
 * ## The sizing is derived, not chosen
 *
 * Each fee output has to be big enough to fund a real transfer. That exact seam has already been
 * got wrong once here: [AssetFeeReserve] shipped a 40,000-sat per-asset constant against a real
 * cost of 54,900–70,100, and described itself as "deliberately an over-estimate" while being an
 * under-estimate. So the amount comes from the transaction that will actually be built, and the
 * gate below asserts it by BUILDING one rather than by comparing to a number.
 */
class ForeignAssetFanOutTest {

    private val feePerKb = 100_000L
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"
    private val sourceAddr = "DBWmsXxuXsyzhKsBYnqRkMRKjErYGA6xZv"

    private fun spend(id: String, sats: Long) = ForeignAssetTransferPlan.Spend(
        txid = id, vout = 0, amountSat = sats, scriptPubKeyHex = script, chain = 0, index = 0,
    )

    private fun plan(assetCount: Int, plain: List<ForeignAssetTransferPlan.Spend>) =
        ForeignAssetFanOut.plan(
            assetCount = assetCount,
            plainInputs = plain,
            sourceAddress = sourceAddr,
            feePerKb = feePerKb,
        )

    // ---- invariant 1: don't split what is already split ---------------------------------------

    @Test fun `no fan-out when there are already enough outputs`() {
        val r = plan(2, listOf(spend("a", 5_000_000L), spend("b", 5_000_000L)))
        assertTrue("two assets, two outputs — nothing to do: $r", r is ForeignAssetFanOut.Result.NotNeeded)
    }

    @Test fun `no fan-out when there are more outputs than assets`() {
        val r = plan(1, listOf(spend("a", 5_000_000L), spend("b", 5_000_000L)))
        assertTrue(r is ForeignAssetFanOut.Result.NotNeeded)
    }

    @Test fun `no fan-out when there are no assets`() {
        assertTrue(plan(0, listOf(spend("a", 5_000_000L))) is ForeignAssetFanOut.Result.NotNeeded)
    }

    // ---- the case this exists for --------------------------------------------------------------

    /** The reported real shape: many assets, one output. */
    @Test fun `fifty assets and one output produces fifty fee outputs`() {
        val r = plan(50, listOf(spend("only", 100_000_000L)))
        val ok = r as? ForeignAssetFanOut.Result.Ok
        assertTrue("expected a fan-out, got $r", ok != null)

        val feeOutputs = ok!!.plan.outputs.filter { it.isFeeOutput }
        assertEquals("one fee output per asset", 50, feeOutputs.size)
    }

    /**
     * Invariant 2, and the reason this test file exists. Every fee output must actually fund a
     * transfer — asserted by BUILDING one against it, so the two sides cannot drift the way the
     * reserve's constant drifted from the transfer's real cost.
     */
    @Test fun `every fee output it creates can fund a real transfer`() {
        val r = plan(50, listOf(spend("only", 100_000_000L)))
        val ok = (r as ForeignAssetFanOut.Result.Ok)

        val assetInput = ForeignAssetTransferPlan.Spend(
            txid = "asset", vout = 0, amountSat = DA_MARKER_SATS,
            scriptPubKeyHex = script, chain = 0, index = 0,
        )
        ok.plan.outputs.filter { it.isFeeOutput }.forEachIndexed { i, out ->
            val built = ForeignAssetTransferPlan.build(
                assetInput = assetInput,
                assetUnits = 1L,
                feeInputs = listOf(spend("fee$i", out.amountSat)),
                destAddress = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f",
                feePerKb = feePerKb,
            )
            assertTrue(
                "fee output $i holds ${out.amountSat} sats and cannot fund a transfer: $built",
                built is ForeignAssetTransferPlan.Result.Ok,
            )
        }
    }

    /** The fan-out must pay the SOURCE wallet, or the moves cannot spend what it creates. */
    @Test fun `fee outputs are payable by the seed being recovered`() {
        val ok = plan(3, listOf(spend("only", 50_000_000L))) as ForeignAssetFanOut.Result.Ok
        assertTrue(
            "every output must go to the source wallet",
            ok.plan.outputs.all { it.address == sourceAddr },
        )
    }

    @Test fun `value is conserved and the fee is positive`() {
        val ok = plan(3, listOf(spend("only", 50_000_000L))) as ForeignAssetFanOut.Result.Ok
        val out = ok.plan.outputs.sumOf { it.amountSat }
        assertEquals(50_000_000L - out, ok.plan.feeSat)
        assertTrue("the fan-out pays a fee", ok.plan.feeSat > 0)
    }

    /** Leftover goes back to the source as change, not to the miner. */
    @Test fun `the remainder comes back as change, not fee`() {
        val ok = plan(2, listOf(spend("big", 500_000_000L))) as ForeignAssetFanOut.Result.Ok
        val change = ok.plan.outputs.filterNot { it.isFeeOutput }
        assertEquals("one change output", 1, change.size)
        assertTrue("nearly all of a 5 DGB input comes back", change.single().amountSat > 490_000_000L)
    }

    /** A remainder too small to be worth an output must not become dust. */
    @Test fun `a dust remainder is left to the fee rather than emitted`() {
        val ok = plan(2, listOf(spend("tight", 200_000L))) as? ForeignAssetFanOut.Result.Ok
        if (ok != null) {
            assertTrue(
                "no output may be dust",
                ok.plan.outputs.all { it.amountSat > ForeignAssetTransferPlan.CHANGE_DUST_THRESHOLD },
            )
        }
    }

    // ---- invariant 4: say so BEFORE broadcasting ------------------------------------------------

    /**
     * Discovering the money ran out after three assets have moved is the worst version of this.
     * The shortfall has to be a refusal with a figure, before anything is signed.
     */
    @Test fun `not enough DGB is refused with the number it needed`() {
        val r = plan(50, listOf(spend("small", 500_000L)))
        val no = r as? ForeignAssetFanOut.Result.Refused
        assertTrue("expected a refusal, got $r", no != null)
        assertTrue("the shortfall must be stated", no!!.shortfallSat > 0)
    }

    @Test fun `no plain DGB at all is refused`() {
        assertTrue(plan(3, emptyList()) is ForeignAssetFanOut.Result.Refused)
    }

    /** Several small outputs are combined rather than refused when together they suffice. */
    @Test fun `several inputs are combined to fund the split`() {
        // Two inputs, three assets — a fan-out IS required, and neither input covers it alone.
        val r = plan(3, listOf(spend("a", 150_000L), spend("b", 150_000L)))
        val ok = r as? ForeignAssetFanOut.Result.Ok
        assertTrue("two 0.001 inputs should together cover three transfers: $r", ok != null)
        assertEquals("both inputs are spent", 2, ok!!.plan.inputs.size)
    }
}
