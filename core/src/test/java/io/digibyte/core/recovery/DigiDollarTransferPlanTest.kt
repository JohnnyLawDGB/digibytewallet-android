package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planning the transaction that moves a foreign wallet's DigiDollar into this one.
 *
 * ## What is different from every other transfer here
 *
 * The dollars carry ZERO satoshis, so the DGB paying for them is entirely separate — and the
 * DigiDollar fee floor is a **consensus rule, not an estimate**: 0.1 DGB, matching DigiByte Core's
 * own builder. Below it the transfer is not accepted however well-formed, so a wallet that cannot
 * meet it must be told before anything is signed rather than after a rejection.
 *
 * Recovery moves the WHOLE balance, so there is never DD change. A partial transfer would need a
 * change output on the foreign seed's taproot chain, and leaving dollars behind is the thing
 * recovery exists to avoid.
 */
class DigiDollarTransferPlanTest {

    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"
    private val recipientKey = "aa".repeat(32)
    private val changeAddr = "dgb1qxv7ml0y2j4q8k7dgn3szrz883ldxjze0djz0dr"
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"

    private fun holding(cents: Long, index: Int = 0) = DigiDollarScan.Holding(
        address = DigiDollarAddress("DD1x", xq, chain = 0, index = index),
        txid = "40a78f13", vout = 0, scriptPubKeyHex = "5120$xq",
    ).let { it to cents }

    private fun fee(id: String, sats: Long) = ForeignAssetTransferPlan.Spend(
        txid = id, vout = 0, amountSat = sats, scriptPubKeyHex = script, chain = 0, index = 0,
    )

    private fun plan(
        cents: Long = 100,
        holdings: List<DigiDollarScan.Holding> = listOf(holding(cents).first),
        feeInputs: List<ForeignAssetTransferPlan.Spend> = listOf(fee("f", 26_000_000L)),
    ) = DigiDollarTransferPlan.build(
        holdings = holdings,
        totalCents = cents,
        feeInputs = feeInputs,
        recipientKeyHex = recipientKey,
        changeAddress = changeAddr,
        feePerKb = 100_000L,
    )

    // ---- the ordinary case ---------------------------------------------------------------------

    @Test fun `a funded wallet plans a transfer of the whole balance`() {
        val ok = plan() as DigiDollarTransferPlan.Result.Ok
        assertEquals("every cent moves", 100L, ok.plan.cents)
        assertEquals(recipientKey, ok.plan.recipientKeyHex)
        assertEquals("the DD outpoint is spent", 1, ok.plan.ddInputs.size)
        assertEquals("the fee input is spent", 1, ok.plan.feeInputs.size)
    }

    /** The floor is consensus, not an estimate — the plan must clear it, not approach it. */
    @Test fun `the fee clears the DigiDollar consensus floor`() {
        val ok = plan() as DigiDollarTransferPlan.Result.Ok
        assertTrue("fee ${ok.plan.feeSat} is below the 0.1 DGB floor",
            ok.plan.feeSat >= DigiDollarTransferPlan.DD_MIN_FEE_SATS)
    }

    @Test fun `change returns to the destination, not the miner`() {
        val ok = plan() as DigiDollarTransferPlan.Result.Ok
        assertEquals(changeAddr, ok.plan.changeAddress)
        assertEquals("26,000,000 in, 10,000,000 fee floor -> the rest is change",
            26_000_000L - ok.plan.feeSat, ok.plan.changeAmountSat)
        assertTrue(ok.plan.changeAmountSat > 0)
    }

    // ---- the refusals --------------------------------------------------------------------------

    /**
     * The case this wallet actually hits: plenty of DGB by ordinary standards, nowhere near the
     * DigiDollar floor. Told before signing, with the figure.
     */
    @Test fun `too little DGB for the floor is refused with its number`() {
        val r = plan(feeInputs = listOf(fee("small", 5_000_000L)))
        val no = r as? DigiDollarTransferPlan.Result.Refused
        assertTrue("expected a refusal, got $r", no != null)
        assertEquals(DigiDollarTransferPlan.Reason.BELOW_FEE_FLOOR, no!!.reason)
        assertTrue("the shortfall must be stated", no.shortfallSat > 0)
        assertEquals(10_000_000L - 5_000_000L, no.shortfallSat)
    }

    @Test fun `no fee inputs at all is refused`() {
        assertTrue(plan(feeInputs = emptyList()) is DigiDollarTransferPlan.Result.Refused)
    }

    @Test fun `nothing to move is refused rather than building an empty transfer`() {
        val r = plan(holdings = emptyList())
        assertTrue(r is DigiDollarTransferPlan.Result.Refused)
    }

    /** Below the $1.00 per-output consensus minimum the network will not accept it. */
    @Test fun `a sub-dollar balance is refused`() {
        val r = plan(cents = 99)
        val no = r as? DigiDollarTransferPlan.Result.Refused
        assertTrue("expected a refusal, got $r", no != null)
        assertEquals(DigiDollarTransferPlan.Reason.BELOW_MIN_CENTS, no!!.reason)
    }

    @Test fun `an amount over the per-transfer maximum is refused`() {
        val r = plan(cents = 10_000_001)
        assertEquals(DigiDollarTransferPlan.Reason.ABOVE_MAX_CENTS,
            (r as DigiDollarTransferPlan.Result.Refused).reason)
    }

    /** Several outpoints combine into one transfer — they all pay the same wallet. */
    @Test fun `several DigiDollar outpoints are spent together`() {
        val hs = listOf(holding(100, index = 0).first, holding(100, index = 1).first)
        val ok = plan(cents = 200, holdings = hs) as DigiDollarTransferPlan.Result.Ok
        assertEquals(2, ok.plan.ddInputs.size)
        assertEquals(200L, ok.plan.cents)
    }

    /** A change output below dust would be unspendable; the remainder rides to the fee instead. */
    @Test fun `a dust remainder is not emitted as change`() {
        val ok = plan(feeInputs = listOf(fee("tight", 10_001_000L))) as DigiDollarTransferPlan.Result.Ok
        assertTrue("no dust change", ok.plan.changeAmountSat == 0L ||
            ok.plan.changeAmountSat > ForeignAssetTransferPlan.CHANGE_DUST_THRESHOLD)
    }
}
