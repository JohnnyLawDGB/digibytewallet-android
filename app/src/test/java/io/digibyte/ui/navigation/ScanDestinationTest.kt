package io.digibyte.ui.navigation

import io.digibyte.core.model.DigiByteUri
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a scanned `digibyte:` URI is routed. Pure so the JVM can prove it.
 *
 * The scanner used to build `send?address=…` and nothing else: the parser read the amount, the
 * send view model knew how to apply it, and the navigation layer between them dropped it. A
 * user scanning the digiscope tip-wallet deposit QR (address plus a dust-coded amount) got the
 * address and an empty amount field — the exact report of 2026-09-07, and the "known issue" on
 * the site's wallet test checklist.
 */
class ScanDestinationTest {

    private val enc: (String) -> String = { it }

    @Test fun `a plain request carries only the address`() {
        val uri = DigiByteUri.parse("digibyte:DTest123")!!
        assertEquals("send?address=DTest123", ScanDestination.forDigiByteUri(uri, returnTo = "", encode = enc))
    }

    @Test fun `a payment request carries the amount in satoshis`() {
        val uri = DigiByteUri(address = "DTest123", amount = 500_001_234L)
        assertEquals(
            "send?address=DTest123&amount=500001234",
            ScanDestination.forDigiByteUri(uri, returnTo = "", encode = enc),
        )
    }

    /** The whole report in one line: the real tip-wallet string, parsed, routed, exact. */
    @Test fun `the tip-wallet deposit QR yields the exact dust-coded amount`() {
        val uri = DigiByteUri.parse("digibyte:DTest123?amount=5.00001234")!!
        assertEquals(
            "send?address=DTest123&amount=500001234",
            ScanDestination.forDigiByteUri(uri, returnTo = "", encode = enc),
        )
    }

    @Test fun `a zero amount is treated as no amount`() {
        val uri = DigiByteUri(address = "DTest123", amount = 0L)
        assertEquals("send?address=DTest123", ScanDestination.forDigiByteUri(uri, returnTo = "", encode = enc))
    }

    @Test fun `a returnTo caller gets the address only`() {
        val uri = DigiByteUri.parse("digibyte:DTest123?amount=1")!!
        assertEquals(
            "asset_send/La1?address=DTest123",
            ScanDestination.forDigiByteUri(uri, returnTo = "asset_send/La1", encode = enc),
        )
    }

    /**
     * A request names whole units of the asset. The route carries them as that integer, under a
     * name that says so; the send screen writes the quantity field from it at the asset's own
     * divisibility. It is never carried as text for a later step to read at some scale.
     */
    @Test fun `an asset request goes to that asset's send screen with its whole units`() {
        val uri = DigiByteUri.parse("digibyte:DTest123?assetId=La1&assetAmount=7")!!
        assertEquals(
            "asset_send/La1?address=DTest123&units=7",
            ScanDestination.forDigiByteUri(uri, returnTo = "", encode = enc),
        )
        val large = DigiByteUri.parse("digibyte:DTest123?assetId=La1&assetAmount=9223372036854775807")!!
        assertEquals(
            "asset_send/La1?address=DTest123&units=9223372036854775807",
            ScanDestination.forDigiByteUri(large, returnTo = "", encode = enc),
        )
    }

    /** The route's units are an integer argument: a request object without usable units names none. */
    @Test fun `an asset request without usable units goes to the send screen with none`() {
        for (units in listOf(null, 0L, -7L)) {
            val uri = DigiByteUri(address = "DTest123", assetId = "La1", assetAmount = units)
            assertEquals(
                "units=$units",
                "asset_send/La1?address=DTest123",
                ScanDestination.forDigiByteUri(uri, returnTo = "", encode = enc),
            )
        }
    }

    @Test fun `the address and asset id pass through the encoder`() {
        val uri = DigiByteUri.parse("digibyte:D Test?amount=1")!!
        assertEquals(
            "send?address=<D Test>&amount=100000000",
            ScanDestination.forDigiByteUri(uri, returnTo = "", encode = { "<$it>" }),
        )
    }
}
