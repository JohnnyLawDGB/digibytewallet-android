package io.digibyte.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The amount a user approves is the amount that is sent.
 *
 * An [ApprovedSend] is made once, from the text in the amount field, when the user asks to review.
 * From then on the text plays no part: the confirmation shows [ApprovedSend.amountText], which is
 * written out from the integer, and the send receives that same integer. Whatever was typed —
 * a decimal comma, grouping, padding zeros — the number on the confirmation is the number that
 * leaves the wallet, in one unambiguous spelling.
 */
class SendAmountBindingTest {

    private val addr = "dgb1qexampleexampleexampleexampleexample"

    private fun dgb(typed: String) = ApprovedSend.dgb(addr, typed, feePerKb = 100_000L, feeEstimateSats = 14_100L)

    /** Seven of the app's languages type the decimal point as a comma. */
    @Test fun `a lone decimal comma is accepted and approved in the dot spelling`() {
        val approved = dgb("0,1")
        assertNotNull("0,1 must be accepted", approved)
        assertEquals(10_000_000L, approved!!.sats)
        assertEquals("0.1", approved.amountText)
    }

    @Test fun `the approval text is written from the integer that is sent`() {
        val typed = listOf(
            "0.1", "1.5", "0.00000029", "1234.5",
            "1,234", "1,234.5", "0,29", "1.50", "01.5", "  0.29 ", "1e2", "1.100000000",
        )
        for (text in typed) {
            val approved = dgb(text)
            assertNotNull(text, approved)
            assertEquals(text, DgbAmount.format(approved!!.sats), approved.amountText)
            assertEquals(text, approved.sats, DgbAmount.toSats(approved.amountText))
        }
    }

    @Test fun `each spelling of an amount is approved as the same text`() {
        assertEquals("1.234", dgb("1,234")!!.amountText)
        assertEquals(123_400_000L, dgb("1,234")!!.sats)
        assertEquals("1234.5", dgb("1,234.5")!!.amountText)
        assertEquals("1.5", dgb("1.50")!!.amountText)
        assertEquals("100", dgb("1e2")!!.amountText)
        assertEquals("0.00000029", dgb("0.00000029")!!.amountText)
    }

    @Test fun `text that is not a positive amount is never approved`() {
        for (text in listOf("", "   ", "abc", "0", "0.0", "-1", "1,2,3", "1,234,567", "1.2.3", "0.000000001", "NaN")) {
            assertNull(text, dgb(text))
        }
    }

    @Test fun `the destination and fee travel with the amount unchanged`() {
        val approved = ApprovedSend.dgb(addr, "2", feePerKb = 123_456L, feeEstimateSats = 17_407L)!!
        assertEquals(addr, approved.address)
        assertEquals(200_000_000L, approved.sats)
        assertEquals(123_456L, approved.feePerKb)
        assertEquals(17_407L, approved.feeEstimateSats)
    }

    // ── The approximate-dollars row of the confirmation ──────────────────────────────────────
    // It is shown beside the amount, so it is part of what is approved: read once, at review,
    // held by the approval, and written out from whole cents like the amount itself.

    private fun dgbWithDollars(typed: String, dollarsShown: String) =
        ApprovedSend.dgb(addr, typed, feePerKb = 100_000L, feeEstimateSats = 14_100L, dollarsShown = dollarsShown)

    @Test fun `the dollars row is held by the approval and written from whole cents`() {
        val comma = dgbWithDollars("81.7", "0,5")!!
        assertEquals(50L, comma.approxUsdCents)
        assertEquals("0.50", comma.approxUsdText)

        assertEquals("1234.50", dgbWithDollars("200000", "1,234.5")!!.approxUsdText)
        assertEquals("40.00", dgbWithDollars("6500", "40")!!.approxUsdText)
        assertEquals("0.06", dgbWithDollars("10", "0.06")!!.approxUsdText)
        assertEquals("0.00", dgbWithDollars("0.00000001", "0.00")!!.approxUsdText)
    }

    @Test fun `dollars text that is not whole cents gives no dollars row and still an approval`() {
        for (text in listOf("abc", "0.005", "1e2", "NaN", "1,2,3", "-1", "$5", "   ")) {
            val approved = dgbWithDollars("10", text)
            assertNotNull(text, approved)
            assertEquals(text, 1_000_000_000L, approved!!.sats)
            assertNull(text, approved.approxUsdCents)
            assertEquals(text, "", approved.approxUsdText)
        }
    }

    @Test fun `an approval made with no dollars text has no dollars row`() {
        assertEquals("", dgb("10")!!.approxUsdText)
        assertNull(dgb("10")!!.approxUsdCents)
        assertEquals("", dgbWithDollars("10", "")!!.approxUsdText)
    }

    // ── DigiDollar: the same binding, in whole cents ─────────────────────────────────────────

    @Test fun `a DigiDollar approval holds whole cents and shows them`() {
        val comma = ApprovedSend.digiDollar(addr, "1,50")
        assertEquals(150L, comma?.cents)
        assertEquals("1.50", comma?.amountText)

        val whole = ApprovedSend.digiDollar(addr, "40")
        assertEquals(4000L, whole?.cents)
        assertEquals("40.00", whole?.amountText)

        val grouped = ApprovedSend.digiDollar(addr, "1,234.5")
        assertEquals(123_450L, grouped?.cents)
        assertEquals("1234.50", grouped?.amountText)
        assertEquals(addr, grouped?.address)
    }

    @Test fun `dollar text that is not a positive number of whole cents is never approved`() {
        for (text in listOf("1.005", "0.001", "1e5", "NaN", "Infinity", "-5", "0", "0.00", "", "abc", "1,234")) {
            assertNull(text, ApprovedSend.digiDollar(addr, text))
        }
    }
}
