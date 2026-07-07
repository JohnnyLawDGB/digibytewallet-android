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

    // Core-anchored unhealthy-DCA vectors (closes the former UNPROVEN gap).
    //
    // Captured 2026-07-07 from DigiByte Core v9.26.4 on regtest: after minting
    // $100 to seed DD supply, `calculatecollateralrequirement 10000 180 <price>`
    // was called with prices that drive Core's own system-health calculation
    // into each unhealthy band. The `wallet_collateral_dgb` Core returns (base
    // requirement + its 1% safety margin) is asserted below, satoshi-exact.
    // Core's discrete DCA bands are the ONLY reachable multipliers — dca.cpp
    // HEALTH_TIERS: >=150% healthy 10000bps, 120-149% warning 12500bps,
    // 110-119% critical 15000bps, <110% emergency 20000bps. (The old
    // regression-only vector used 12000bps, a value Core can never emit.)
    //
    // calculatecollateralrequirement shares the mint builder's exact arithmetic
    // (txbuilder.cpp: ApplyDCA -> ceil base -> ApplyCollateralSafetyMargin) and
    // the same chain-derived health source, so its wallet_collateral IS what a
    // real mint locks. A live mint into an unhealthy band could not be used to
    // anchor here because reaching one by crashing the mock price trips Core's
    // separate mint volatility freeze (minting-frozen-volatility-candidate) —
    // orthogonal to the collateral formula. Repro: scratchpad/dca-vector.sh.
    @Test
    fun `warning-band DCA (12500 bps) matches Core wallet_collateral`() {
        // price 5100 micro-USD -> Core health 134% (warning), effective 438%.
        assertEquals(
            8_674_117_647_059,
            Collateral.requiredSats(
                ddCents = 10_000,
                tier = LockTiers.byIndex(3),
                oraclePriceMicroUsd = 5_100,
                dcaMultiplierBps = 12_500,
            ),
        )
    }

    @Test
    fun `critical-band DCA (15000 bps) matches Core wallet_collateral`() {
        // price 4370 micro-USD -> Core health 115% (critical), effective 525%.
        assertEquals(
            12_133_867_276_888,
            Collateral.requiredSats(
                ddCents = 10_000,
                tier = LockTiers.byIndex(3),
                oraclePriceMicroUsd = 4_370,
                dcaMultiplierBps = 15_000,
            ),
        )
    }

    @Test
    fun `emergency-band DCA (20000 bps) matches Core wallet_collateral`() {
        // price 3600 micro-USD -> Core health 94% (emergency), effective 700%.
        assertEquals(
            19_638_888_888_889,
            Collateral.requiredSats(
                ddCents = 10_000,
                tier = LockTiers.byIndex(3),
                oraclePriceMicroUsd = 3_600,
                dcaMultiplierBps = 20_000,
            ),
        )
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
