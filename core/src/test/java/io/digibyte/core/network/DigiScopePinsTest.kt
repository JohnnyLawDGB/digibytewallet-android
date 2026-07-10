package io.digibyte.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the recurring "stale digiscope cert pin" bug.
 *
 * The DigiAsset "metadata offline" outage (v3.10.16) and an earlier "Scan for
 * missing funds" outage both had the same cause: a rotated cert left a dead pin
 * live in a client. These tests fail loudly if a retired pin is ever left in
 * [DigiScopePins.PINS], and lock the current values so an accidental edit is
 * caught in CI rather than on a user's device.
 */
class DigiScopePinsTest {

    @Test
    fun `no retired pin is still live`() {
        val overlap = DigiScopePins.PINS.intersect(DigiScopePins.RETIRED_PINS.toSet())
        assertTrue(
            "A retired (dead) cert pin is still present in PINS: $overlap. " +
                "Rotating a pin means moving the old value into RETIRED_PINS, not leaving it live.",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `pins match the current live chain (leaf + LE intermediate)`() {
        assertEquals(
            listOf(
                "sha256/mxkNfnacx0nKcMPntNt/8/iv7iEoVNyg0WkCOt2FdU0=",
                "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=",
            ),
            DigiScopePins.PINS,
        )
    }

    @Test
    fun `every pin is a well-formed sha256 pin`() {
        (DigiScopePins.PINS + DigiScopePins.RETIRED_PINS).forEach {
            assertTrue("malformed pin: $it", it.startsWith("sha256/") && it.length > 12)
        }
    }

    @Test
    fun `certificatePinner builds and pins exactly the current set`() {
        val pinner = DigiScopePins.certificatePinner()
        assertNotNull(pinner)
        assertEquals(DigiScopePins.PINS.size, pinner.pins.size)
    }
}
