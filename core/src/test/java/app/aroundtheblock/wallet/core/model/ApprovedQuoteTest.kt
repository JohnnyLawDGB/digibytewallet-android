package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An approval takes the quote review made — the rate a send was built or planned at, and its fee —
 * without changing what is sent. GUARD: these are new and pass as written; they pin that carrying the
 * quote can never move the address, the amount or the asset the user approved.
 */
class ApprovedQuoteTest {

    @Test fun `a DGB approval carries its quote and nothing else changes`() {
        val typed = ApprovedSend.dgb("dgb1qrecipient", "1.5", 100_000L, 0L, dollarsShown = "12.34")!!
        val quoted = typed.withQuote(feePerKb = 240_384L, feeSats = 50_000L)

        assertEquals(240_384L, quoted.feePerKb)
        assertEquals(50_000L, quoted.feeEstimateSats)
        assertEquals(typed.address, quoted.address)
        assertEquals(typed.sats, quoted.sats)
        assertEquals(typed.amountText, quoted.amountText)
        assertEquals(typed.approxUsdText, quoted.approxUsdText)
    }

    @Test fun `an asset approval takes a rate and keeps what it sends`() {
        val typed = ApprovedSend.asset("dgb1qrecipient", "La-asset", "1.50", 2, 100_000L, 0L)!!
        val atRate = typed.atRate(163_132L)

        assertEquals(163_132L, atRate.feePerKb)
        assertEquals(typed.address, atRate.address)
        assertEquals(typed.assetId, atRate.assetId)
        assertEquals(typed.units, atRate.units)
        assertEquals(typed.divisibility, atRate.divisibility)
        assertEquals(typed.feeEstimateSats, atRate.feeEstimateSats)
        // The plan's fee then comes on top, at the same rate.
        assertEquals(163_132L, atRate.withPlannedFee(61_300L).feePerKb)
        assertEquals(61_300L, atRate.withPlannedFee(61_300L).feeEstimateSats)
    }
}
