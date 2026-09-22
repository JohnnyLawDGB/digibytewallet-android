package io.digibyte.ui.asset

import io.digibyte.core.model.ApprovedSend
import io.digibyte.core.model.AssetQuantity
import io.digibyte.core.model.DigiByteUri
import io.digibyte.ui.navigation.ScanDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An asset amount in a transfer request means the same number of units for whoever wrote the
 * request, in the quantity field it pre-fills, and in the approval made from that field.
 *
 * A request names WHOLE UNITS of the asset (`DigiByteUri.assetAmount`). The form shows and reads
 * quantities at the asset's divisibility — 150 units of an asset with two decimals read "1.5" —
 * and that divisibility is a chain fact which can arrive after the form is on screen. So the units
 * travel as an integer from the route to the approval, and text is only ever written FROM them, at
 * the divisibility of the moment the text is needed.
 */
class RequestedAssetQuantityTest {

    private val addr = "dgb1qexampleexampleexampleexampleexample"
    private val assetId = "La4WAqZfExampleExampleExampleExampleEx"

    private fun requested(units: Long?) = RequestedAssetQuantity().apply { deliver(units) }

    /** Review, as the view model makes it: the text it reads and the divisibility it reads it at are one step. */
    private fun RequestedAssetQuantity.approvedAt(divisibility: Int, fieldShows: String): ApprovedSend.Asset? =
        ApprovedSend.asset(
            address = addr,
            assetId = assetId,
            typedQuantity = textToApprove(fieldShows, divisibility),
            divisibility = divisibility,
            feePerKb = 100_000L,
            feeEstimateSats = 61_300L,
        )

    @Test fun `150 units of an asset with two decimals pre-fill as one and a half`() {
        assertEquals("1.5", requested(150L).fieldText(2))
    }

    @Test fun `150 units of an asset with no decimals pre-fill as 150`() {
        assertEquals("150", requested(150L).fieldText(0))
    }

    @Test fun `the pre-filled text is the requested units at every divisibility`() {
        val request = requested(150L)
        for (divisibility in 0..AssetQuantity.MAX_DIVISIBILITY) {
            val text = request.fieldText(divisibility)
            assertNotNull("at $divisibility", text)
            assertEquals("at $divisibility", 150L, AssetQuantity.parse(text!!, divisibility))
        }
        assertEquals("0.0000015", request.fieldText(8))
    }

    /** The asset's divisibility can become known after the field was filled: the text follows it. */
    @Test fun `while the field is the request's, its text is written again when the divisibility changes`() {
        val request = requested(150L)
        assertEquals("150", request.fieldText(0))
        assertEquals("1.5", request.fieldText(2))
        assertEquals("0.0000015", request.fieldText(8))
        assertEquals("150", request.fieldText(0))
    }

    /**
     * Review reads at the divisibility the view model holds, which can be newer than the one the
     * field was last written at. The approval holds the requested units either way.
     */
    @Test fun `the approval holds exactly the requested units, whatever the field showed`() {
        // The field was filled before the asset's two decimals were known, and still shows "150".
        assertEquals(150L, requested(150L).approvedAt(divisibility = 2, fieldShows = "150")!!.units)
        for (units in listOf(1L, 7L, 150L, 123_456_789L, Long.MAX_VALUE)) {
            for (divisibility in 0..AssetQuantity.MAX_DIVISIBILITY) {
                for (fieldShows in listOf("", "150", "1.5", units.toString())) {
                    val approved = requested(units).approvedAt(divisibility, fieldShows)
                    assertNotNull("$units at $divisibility over '$fieldShows'", approved)
                    assertEquals("$units at $divisibility over '$fieldShows'", units, approved!!.units)
                    assertEquals(AssetQuantity.format(units, divisibility), approved.amountText)
                }
            }
        }
    }

    /** The whole path on the JVM: the request as text, the route, the field, the approval. */
    @Test fun `a scanned request for 150 units is approved as 150 units`() {
        val uri = DigiByteUri.parse("digibyte:$addr?assetId=$assetId&assetAmount=150")!!
        val route = ScanDestination.forDigiByteUri(uri, returnTo = "", encode = { it })
        assertTrue("the route does not carry the requested units as an integer: $route", route.endsWith("&units=150"))
        val request = requested(route.substringAfterLast("&units=").toLong())

        assertEquals("1.5", request.fieldText(2))
        assertEquals("150", request.fieldText(0))
        assertEquals(uri.assetAmount, request.approvedAt(divisibility = 2, fieldShows = "1.5")!!.units)
        assertEquals(uri.assetAmount, request.approvedAt(divisibility = 2, fieldShows = "150")!!.units)
        assertEquals("1.5", request.approvedAt(divisibility = 2, fieldShows = "150")!!.amountText)
    }

    @Test fun `an edited field is the user's text - never written over, and read as typed`() {
        val request = requested(150L)
        assertEquals("1.5", request.fieldText(2))
        request.edited()
        assertNull(request.fieldText(2))
        assertNull("a later divisibility writes over what the user typed", request.fieldText(8))
        assertEquals("2", request.textToApprove("2", 2))
        assertEquals(200L, request.approvedAt(divisibility = 2, fieldShows = "2")!!.units)
        assertNull(request.approvedAt(divisibility = 2, fieldShows = "not a quantity"))
    }

    /** The route hands its argument over on every composition; only the first counts. */
    @Test fun `a request the user edited away does not come back with the route's next delivery`() {
        val request = requested(150L)
        request.edited()
        request.deliver(150L)
        assertNull(request.fieldText(2))
        assertEquals("3", request.textToApprove("3", 2))

        val untouched = requested(150L)
        untouched.deliver(999L)
        assertEquals("150", untouched.fieldText(0))
    }

    @Test fun `a field the user typed in before the request arrived stays the user's`() {
        val request = RequestedAssetQuantity()
        request.edited()
        request.deliver(150L)
        assertNull(request.fieldText(2))
    }

    @Test fun `a route without a usable request leaves the field alone`() {
        for (none in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
            val request = requested(none)
            assertNull("$none", request.fieldText(2))
            assertEquals("$none", "1.5", request.textToApprove("1.5", 2))
            assertEquals("$none", 150L, request.approvedAt(divisibility = 2, fieldShows = "1.5")!!.units)
        }
    }

    /** A divisibility no asset has reads nothing, so nothing is approved: the request is not read at some other scale. */
    @Test fun `a request is approved at a divisibility an asset can have, or not at all`() {
        for (divisibility in listOf(-1, 9, 18)) {
            assertNull("at $divisibility", requested(150L).approvedAt(divisibility, fieldShows = "150"))
        }
    }
}
