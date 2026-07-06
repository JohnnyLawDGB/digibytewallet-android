package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class CollateralTest {

    // The live differential proof (dgb-support regtest-oracle-findings):
    // $100 at tier 3 (6 months / 350%), oracle price 13,420 micro-USD,
    // healthy DCA -> Core locked exactly 26,341.28166915 DGB.
    @Test
    fun `reproduces the Core-proven collateral for the fixture mint`() {
        val sats = Collateral.requiredSats(
            ddCents = 10_000,
            tier = LockTiers.byIndex(3),
            oraclePriceMicroUsd = 13_420,
        )
        assertEquals(2_634_128_166_915, sats)
    }

    // An unhealthy system surcharges collateral: 12,000 bps on tier 3 gives
    // effective ratio ceil(350 * 12000 / 10000) = 420%.
    // KNOWN GAP: this expected value was computed from the same Kotlin
    // formula, NOT from Core — it guards against regressions only. A
    // Core/regtest-anchored unhealthy-DCA vector is still needed (the only
    // Core-proven case above has DCA as a no-op).
    @Test
    fun `DCA multiplier surcharges the effective ratio with ceiling math`() {
        val sats = Collateral.requiredSats(
            ddCents = 10_000,
            tier = LockTiers.byIndex(3),
            oraclePriceMicroUsd = 13_420,
            dcaMultiplierBps = 12_000,
        )
        assertEquals(3_160_953_800_298, sats)
    }

    @Test
    fun `rejects non-positive amounts and prices, and results beyond MAX_MONEY`() {
        val tier = LockTiers.byIndex(3)
        assertFailsWith<IllegalArgumentException> {
            Collateral.requiredSats(ddCents = 0, tier = tier, oraclePriceMicroUsd = 13_420)
        }
        assertFailsWith<IllegalArgumentException> {
            Collateral.requiredSats(ddCents = -100, tier = tier, oraclePriceMicroUsd = 13_420)
        }
        assertFailsWith<IllegalArgumentException> {
            Collateral.requiredSats(ddCents = 10_000, tier = tier, oraclePriceMicroUsd = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            // zero/negative DCA would zero out or negate the collateral
            Collateral.requiredSats(
                ddCents = 10_000,
                tier = tier,
                oraclePriceMicroUsd = 13_420,
                dcaMultiplierBps = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            // $100k at 1000% against a 1-micro-USD price: far beyond 21B DGB.
            Collateral.requiredSats(
                ddCents = 10_000_000,
                tier = LockTiers.byIndex(0),
                oraclePriceMicroUsd = 1,
            )
        }
    }
}
