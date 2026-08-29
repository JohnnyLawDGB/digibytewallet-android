package io.digibyte.core.recovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driving the DigiDollar move: what is reported when each half succeeds or fails.
 *
 * The failures matter more than the success here. Dollars that are found and not moved must still
 * be REPORTED — silence about them is the exact bug this whole path exists to fix, and a recovery
 * that empties a wallet of DGB while saying nothing about its dollars is the worst outcome
 * available.
 */
class DigiDollarTransferServiceTest {

    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"
    private val recipient = "bb".repeat(32)
    private val changeAddr = "dgb1qxv7ml0y2j4q8k7dgn3szrz883ldxjze0djz0dr"
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"

    private val profile = DerivationProfile(
        label = "BIP84", description = "native", hmacKey = DerivationProfile.HMAC_STANDARD,
        prefixPath = intArrayOf(84, 20, 0), addressFormat = 1, isNative = true,
    )

    private fun holding(index: Int = 0) = DigiDollarScan.Holding(
        address = DigiDollarAddress("DD1x", xq, chain = 0, index = index),
        txid = "40a78f13", vout = 0, scriptPubKeyHex = "5120$xq",
    )

    private fun feeInput(sats: Long = 26_000_000L) = ForeignAssetTransferPlan.Spend(
        txid = "fee", vout = 0, amountSat = sats, scriptPubKeyHex = script, chain = 0, index = 0,
    )

    private val recorded = mutableListOf<Boolean>()

    private fun service(
        sign: (DigiDollarTransferPlan.Plan, ByteArray, DerivationProfile) -> String? =
            { _, _, _ -> "00ff" },
        broadcast: (ByteArray) -> String? = { "dd-txid" },
    ) = DigiDollarTransferService(
        sign = sign, broadcast = broadcast, log = { _, _ -> },
        recordOutgoing = { _, _, _, _, self -> recorded += self },
    )

    private fun run(
        svc: DigiDollarTransferService,
        scan: DigiDollarScan.Result,
        fees: List<ForeignAssetTransferPlan.Spend> = listOf(feeInput()),
    ) = runBlocking {
        svc.move(ByteArray(64) { 7 }, scan, fees, profile, recipient, changeAddr)
    }

    private fun scanOf(cents: Long, holdings: List<DigiDollarScan.Holding> = listOf(holding()),
                       reachable: Boolean = true, unlocatable: Long = 0L) =
        DigiDollarScan.Result(cents, holdings, reachable, unlocatable)

    // ---- the happy path -------------------------------------------------------------------------

    @Test fun `dollars are moved and the txid reported`() {
        val r = run(service(), scanOf(100))
        assertEquals(100L, r.cents)
        assertEquals("dd-txid", r.txid)
        assertTrue(r.moved)
    }

    /** The destination is this wallet's own DigiDollar address, so the activity list must not
     *  override the C core's receive categorisation into "Sent". */
    @Test fun `the move is recorded as a self transfer`() {
        recorded.clear()
        run(service(), scanOf(100))
        assertEquals(listOf(true), recorded)
    }

    /** Its inputs must be excluded from the sweep, or the sweep double-spends them. */
    @Test fun `the spent inputs are reported for sweep exclusion`() {
        val r = run(service(), scanOf(100))
        assertTrue("the DD outpoint", r.spentInputs.contains("40a78f13:0"))
        assertTrue("the fee input", r.spentInputs.contains("fee:0"))
    }

    // ---- found but not moved: still reported ----------------------------------------------------

    /**
     * The case this wallet actually hits. 0.05 DGB is plenty for an asset transfer and nowhere
     * near the 0.1 DGB DigiDollar floor — and the user must be told the dollars are there.
     */
    @Test fun `dollars below the fee floor are still reported, with the reason`() {
        val r = run(service(), scanOf(100), fees = listOf(feeInput(5_000_000L)))
        assertEquals("the balance is reported even though nothing moved", 100L, r.cents)
        assertFalse(r.moved)
        assertTrue(r.failureReason!!.contains("BELOW_FEE_FLOOR"))
    }

    /**
     * A wallet swept clean of DGB that still holds dollars. The recovery UI used to return early
     * here — no funded profile meant no fee inputs meant nothing was said — so the dollars went
     * unmentioned entirely. Zero fee inputs must produce the same honest refusal as too-few.
     */
    @Test fun `dollars with no DGB at all are still reported, with the reason`() {
        val r = run(service(), scanOf(100), fees = emptyList())
        assertEquals("the balance is reported even with nothing to pay a fee with", 100L, r.cents)
        assertFalse(r.moved)
        assertTrue("a reason must be given", !r.failureReason.isNullOrBlank())
    }

    /**
     * The refusal must survive as DATA, not only as an English sentence. The screen showed the
     * raw detail string — "BELOW_FEE_FLOOR: a DigiDollar transfer needs 10000000 sats of DGB" —
     * to a user looking at their own money, in one language, in satoshis. Carrying the reason and
     * the shortfall lets the UI say it in the user's language and in DGB.
     */
    @Test fun `a fee-floor refusal carries the reason and the shortfall`() {
        val r = run(service(), scanOf(100), fees = emptyList())
        assertEquals(DigiDollarTransferPlan.Reason.BELOW_FEE_FLOOR, r.refusalReason)
        assertEquals("the whole floor is missing when there is no DGB at all",
            DigiDollarTransferPlan.DD_MIN_FEE_SATS, r.shortfallSat)
    }

    @Test fun `a signing refusal still reports the balance`() {
        val r = run(service(sign = { _, _, _ -> null }), scanOf(250))
        assertEquals(250L, r.cents)
        assertNull(r.txid)
        assertTrue(r.failureReason!!.contains("sign"))
    }

    @Test fun `a broadcast failure still reports the balance`() {
        val r = run(service(broadcast = { null }), scanOf(250))
        assertEquals(250L, r.cents)
        assertTrue(r.failureReason!!.contains("roadcast"))
    }

    @Test fun `malformed signed hex is not broadcast`() {
        var broadcasts = 0
        val r = run(service(sign = { _, _, _ -> "nothex" }, broadcast = { broadcasts++; "x" }), scanOf(100))
        assertEquals(0, broadcasts)
        assertFalse(r.moved)
    }

    // ---- nothing there, versus nothing knowable --------------------------------------------------

    @Test fun `a wallet with no dollars reports none and does nothing`() {
        val r = run(service(), scanOf(0, holdings = emptyList()))
        assertFalse(r.hasDollars)
        assertNull(r.failureReason)
        assertTrue(r.reachable)
    }

    /**
     * An unreachable lookup must not be reported as "no dollars". The wallet may hold plenty and
     * we simply could not ask.
     */
    @Test fun `an unreachable lookup is carried through, not flattened to zero`() {
        val r = run(service(), scanOf(0, holdings = emptyList(), reachable = false))
        assertFalse("the caller must know the answer is not final", r.reachable)
        assertFalse(r.hasDollars)
    }

    /** Dollars whose outpoint could not be located are real and unmovable — both facts survive. */
    @Test fun `unlocatable dollars survive into the result`() {
        val r = run(service(), scanOf(500, holdings = emptyList(), unlocatable = 500L))
        assertEquals(500L, r.cents)
        assertEquals(500L, r.unlocatableCents)
        assertFalse(r.moved)
    }
}
