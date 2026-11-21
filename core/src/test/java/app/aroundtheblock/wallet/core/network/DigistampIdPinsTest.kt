package app.aroundtheblock.wallet.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin set of DigiStamp's Digi-ID sign-in host (MARKET-SIGNIN-ID-HOST): the live leaf and
 * intermediate, the ISRG backups, and nothing this host has retired. Live-chain agreement is checked
 * by scripts/check-pins-live.sh; the platform copy by PlatformPinSetParityTest.
 */
class DigistampIdPinsTest {

    @Test fun `the host is the sign-in host, exactly`() {
        assertEquals("id.digistamp.co", DigistampIdPins.HOST)
        assertEquals(app.aroundtheblock.wallet.core.digistamp.DigistampUris.SIGN_IN_HOST, DigistampIdPins.HOST)
    }

    @Test fun `pins are the 2026-11-21 live leaf and intermediate plus the ISRG backups`() {
        assertEquals(
            listOf(
                "sha256/4dpnWZ66VWKWEIqNBMK+Q1c+aROvD560F9yvVxr6+H4=",
                "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=",
                "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
                "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
                "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
            ),
            DigistampIdPins.PINS,
        )
    }

    @Test fun `no pin this host retired is still live`() {
        val overlap = DigistampIdPins.PINS.intersect(DigistampIdPins.RETIRED_PINS.toSet())
        assertTrue("a retired pin is still in PINS: $overlap", overlap.isEmpty())
    }

    /** The two hosts do not share a certificate: the leaf of one is no pin of the other. */
    @Test fun `the sign-in host's leaf is its own`() {
        assertTrue(DigistampIdPins.PINS.first() !in DigistampPins.PINS)
        assertTrue(DigistampPins.PINS.first() !in DigistampIdPins.PINS)
    }

    /** The backups are the same ISRG keys for both hosts; a change to one list's is a change to both. */
    @Test fun `both hosts carry the same ISRG backups`() {
        assertEquals(DigistampPins.PINS.takeLast(3), DigistampIdPins.PINS.takeLast(3))
    }

    @Test fun `the DigiStamp pinner holds every pin of this host, for this host only`() {
        val pinner = DigistampPins.certificatePinner()
        assertEquals(
            DigistampIdPins.PINS.toSet(),
            pinner.findMatchingPins(DigistampIdPins.HOST).map { "sha256/" + it.hash.base64() }.toSet(),
        )
        assertEquals(
            DigistampPins.PINS.toSet(),
            pinner.findMatchingPins(DigistampPins.HOST).map { "sha256/" + it.hash.base64() }.toSet(),
        )
        assertTrue(pinner.findMatchingPins("x.id.digistamp.co").isEmpty())
        assertTrue(pinner.findMatchingPins("digistamp.co").isEmpty())
    }
}
