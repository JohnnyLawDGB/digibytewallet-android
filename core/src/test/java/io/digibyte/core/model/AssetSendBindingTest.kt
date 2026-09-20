package io.digibyte.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asset quantity a user approves is the quantity that is sent.
 *
 * An [ApprovedSend.Asset] is made once, from the text in the quantity field and the asset's
 * divisibility, when the user asks to review. From then on the text plays no part: the
 * confirmation shows [ApprovedSend.amountText], which is written out from the whole units held by
 * the approval, and the send receives those same units. A [SendConfirmation] then lets a send go
 * ahead only for the approval that is on screen, and only once.
 *
 * `SendAmountBindingTest` holds the same rule for DGB and DigiDollar.
 */
class AssetSendBindingTest {

    private val addr = "dgb1qexampleexampleexampleexampleexample"
    private val assetId = "La4WAqZfExampleExampleExampleExampleEx"

    private fun asset(typed: String, divisibility: Int) =
        ApprovedSend.asset(addr, assetId, typed, divisibility, feePerKb = 100_000L, feeEstimateSats = 61_300L)

    @Test fun `the approval text is written from the whole units that are sent`() {
        val typed = listOf(
            "1" to 0, "100" to 0, "0100" to 0, " 3 " to 0,
            "1.5" to 2, "1.50" to 2, "01.5" to 2, ".5" to 2, "1,5" to 2, "1,234.5" to 2, "  0.29 " to 2,
            "1,234" to 3,
            "1" to 8, "0.00000029" to 8, "0,29" to 8, "1234.50000000" to 8,
        )
        for ((text, divisibility) in typed) {
            val approved = asset(text, divisibility)
            assertNotNull("'$text' at $divisibility", approved)
            assertEquals(
                "'$text' at $divisibility",
                AssetQuantity.format(approved!!.units, divisibility), approved.amountText,
            )
            assertEquals(
                "'$text' at $divisibility",
                approved.units, AssetQuantity.parse(approved.amountText, divisibility),
            )
        }
    }

    @Test fun `each spelling of a quantity is approved as the same text`() {
        assertEquals("1.5", asset("1.50", 2)!!.amountText)
        assertEquals("1.5", asset("01.5", 2)!!.amountText)
        assertEquals("1.5", asset("1,5", 2)!!.amountText)
        assertEquals(150L, asset("1,5", 2)!!.units)
        assertEquals("0.5", asset(".5", 2)!!.amountText)
        assertEquals("1234.5", asset("1,234.5", 2)!!.amountText)
        assertEquals("1.234", asset("1,234", 3)!!.amountText)
        assertEquals("100", asset("0100", 0)!!.amountText)
        assertEquals("3", asset(" 3 ", 0)!!.amountText)
        assertEquals("1234.5", asset("1234.50000000", 8)!!.amountText)
    }

    @Test fun `exponent text is never approved`() {
        for (text in listOf("1e2", "1E2", "2e1", "1e0")) {
            val approved = asset(text, 0)
            assertNull("'$text': ${approved?.units} units shown as '${approved?.amountText}'", approved)
        }
    }

    @Test fun `text that is not a positive quantity of the asset is never approved`() {
        val refused = listOf(
            "" to 0, "   " to 0, "abc" to 0, "0" to 0, "0.00" to 2, "-1" to 0, "+1" to 0,
            "1e2" to 0, "1E2" to 2, "1e-2" to 8, "NaN" to 0, "Infinity" to 2,
            "1.5" to 0, "1.234" to 2, "0.000000001" to 8,
            "1,5" to 0, "1,2,3" to 2, "1,234,567" to 0, "1.2.3" to 2,
            "9223372036854775808" to 0, "92233720368547758.08" to 2,
            "1" to -1, "1" to 9,
        )
        for ((text, divisibility) in refused) {
            assertNull("'$text' at $divisibility", asset(text, divisibility))
        }
    }

    @Test fun `the asset, the destination, the divisibility and the fee travel with the quantity unchanged`() {
        val approved = ApprovedSend.asset(addr, assetId, "2.5", 2, feePerKb = 123_456L, feeEstimateSats = 75_678L)!!
        assertEquals(addr, approved.address)
        assertEquals(assetId, approved.assetId)
        assertEquals(250L, approved.units)
        assertEquals(2, approved.divisibility)
        assertEquals(123_456L, approved.feePerKb)
        assertEquals(75_678L, approved.feeEstimateSats)
    }

    // ── Which approval a send may be made for ────────────────────────────────────────────────
    // The one on screen, and only once. Identity, not equality: two approvals that read the same
    // are still two approvals, and only one of them is what the user is looking at.

    private fun confirmation() = SendConfirmation<ApprovedSend.Asset>()

    @Test fun `the approval on screen is sent, and only once`() {
        val confirmation = confirmation()
        val approved = asset("5", 0)!!
        assertNull(confirmation.approval.value)
        assertTrue(confirmation.open(approved))
        assertSame(approved, confirmation.approval.value)
        assertTrue(confirmation.claim(approved))
        assertFalse("claimed a second time", confirmation.claim(approved))
        assertFalse("claimed a third time", confirmation.claim(approved))
    }

    @Test fun `an approval that another one replaced is refused`() {
        val confirmation = confirmation()
        val earlier = asset("5", 0)!!
        val current = asset("7", 0)!!
        confirmation.open(earlier)
        confirmation.open(current)
        assertSame(current, confirmation.approval.value)
        assertFalse("the earlier approval", confirmation.claim(earlier))
        assertTrue("the approval on screen", confirmation.claim(current))
    }

    @Test fun `an approval whose confirmation was closed is refused`() {
        val confirmation = confirmation()
        val approved = asset("5", 0)!!
        confirmation.open(approved)
        confirmation.close()
        assertNull(confirmation.approval.value)
        assertFalse(confirmation.claim(approved))
    }

    @Test fun `an approval that was never on screen is refused`() {
        val confirmation = confirmation()
        assertFalse("nothing on screen", confirmation.claim(asset("5", 0)!!))
        confirmation.open(asset("5", 0)!!)
        assertFalse("another approval on screen", confirmation.claim(asset("9", 0)!!))
    }

    @Test fun `an approval that reads the same as the one on screen is still not that one`() {
        val confirmation = confirmation()
        val onScreen = asset("5", 0)!!
        val sameReading = asset("5", 0)!!
        confirmation.open(onScreen)
        assertFalse(confirmation.claim(sameReading))
        assertTrue(confirmation.claim(onScreen))
    }

    /** While a send is out, its approval stays on screen: nothing takes its place until it closes. */
    @Test fun `an approval being sent stays on screen until its confirmation closes`() {
        val confirmation = confirmation()
        val sending = asset("5", 0)!!
        val next = asset("7", 0)!!
        confirmation.open(sending)
        assertTrue(confirmation.claim(sending))
        assertSame(sending, confirmation.approval.value)
        assertFalse("opened over an approval that is being sent", confirmation.open(next))
        assertSame(sending, confirmation.approval.value)
        assertFalse(confirmation.claim(next))

        confirmation.close()
        assertNull(confirmation.approval.value)
        assertTrue(confirmation.open(next))
        assertFalse("the approval already sent", confirmation.claim(sending))
        assertTrue(confirmation.claim(next))
    }
}
