package app.aroundtheblock.wallet.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Digistamp pin set: the live leaf and intermediate, ISRG backups, and no retired pin left live
 * (4.1.3 row 2: the 4.1.2 set still carried a leaf that had been renewed away, so pinning rested on
 * the intermediate alone). Live-chain agreement is checked by scripts/check-pins-live.sh.
 */
class DigistampPinsTest {

    @Test fun `no retired pin is still live`() {
        val overlap = DigistampPins.PINS.intersect(DigistampPins.RETIRED_PINS.toSet())
        assertTrue("a retired pin is still in PINS: $overlap", overlap.isEmpty())
    }

    @Test fun `the 2026-08-23 leaf is retired`() {
        assertTrue(DigistampPins.RETIRED_PINS.contains("sha256/APmRUNe9kpbMkhWTnrdA/3y7EIwNGI9MmDKb/DId08I="))
    }

    @Test fun `pins are the 2026-10-29 live leaf and intermediate plus the ISRG backups`() {
        assertEquals(
            listOf(
                "sha256/4QRqLjH49bkWbXdyanK3jENaFwUIqL2Lr+bXn1Y9Bcw=",
                "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=",
                "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
                "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
                "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
            ),
            DigistampPins.PINS,
        )
    }

    @Test fun `the pinner holds every pin for this host and the DigiScope pins beside them`() {
        val pins = DigistampPins.certificatePinner().pins
        assertEquals(DigistampPins.PINS.size, pins.count { it.pattern == DigistampPins.HOST })
        assertEquals(DigiScopePins.PINS.size, pins.count { it.pattern == DigiScopePins.HOST })
    }
}
