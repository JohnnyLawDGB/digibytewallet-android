package io.digibyte.core.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Divisibility formatting, vectors from RenzoDD/digiasset-core
 * tests/DigiAssetTest.cpp:135-141 (asset with divisibility 2).
 */
class FormatAssetCountTest {

    @Test fun decimals_zero_is_the_integer() {
        assertEquals("20", formatAssetCount(20, 0))
        assertEquals("1", formatAssetCount(1, 0))
    }

    @Test fun divisibility_two_reference_vectors() {
        assertEquals("0.05", formatAssetCount(5, 2))
        assertEquals("5.00", formatAssetCount(500, 2))
        assertEquals("50.00", formatAssetCount(5000, 2))
    }

    @Test fun pads_when_fewer_digits_than_decimals() {
        assertEquals("0.001", formatAssetCount(1, 3))
    }

    @Test fun negative_or_zero_decimals_treated_as_integer() {
        assertEquals("42", formatAssetCount(42, -1))
        assertEquals("42", formatAssetCount(42, 0))
    }
}
