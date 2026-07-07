package io.digibyte.ui.wallet

import io.digibyte.digidollar.Collateral
import io.digibyte.digidollar.LockTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Mint-calculator logic (issue #11): USD parsing, the consensus amount
 * bounds, and the collateral preview. These back [MintViewModel]'s live
 * calculator without a device — the preview must equal the exact
 * [Collateral.requiredSats] figure MintService locks.
 */
class DigiDollarMintCalculatorTest {

    private val tier0 = LockTiers.byIndex(0) // 1 hour, 1000% ratio
    private val price = 13_420L // micro-USD per DGB (regtest oracle vector)
    private val dcaHealthy = 10_000L

    @Test fun usdParsesToCents() {
        assertEquals(10_000L, MintViewModel.parseUsdToCents("100"))
        assertEquals(10_000L, MintViewModel.parseUsdToCents("100.00"))
        assertEquals(12_345L, MintViewModel.parseUsdToCents("123.45"))
        assertNull(MintViewModel.parseUsdToCents(""))
        assertNull(MintViewModel.parseUsdToCents("abc"))
        assertNull(MintViewModel.parseUsdToCents("-5"))
        // toDoubleOrNull accepts these Java FP tokens — must still be rejected.
        assertNull(MintViewModel.parseUsdToCents("NaN"))
        assertNull(MintViewModel.parseUsdToCents("Infinity"))
    }

    @Test fun mintAmountBounds() {
        // consensus range is [$100, $100,000] = [10_000, 10_000_000] cents
        assertTrue(MintViewModel.mintAmountValid(10_000))        // min $100
        assertTrue(MintViewModel.mintAmountValid(10_000_000))    // max $100k
        assertFalse(MintViewModel.mintAmountValid(9_999))        // below min
        assertFalse(MintViewModel.mintAmountValid(10_000_001))   // above max
    }

    @Test fun previewMatchesConsensusCollateral() {
        val cents = 10_000L
        val expected = Collateral.requiredSats(cents, tier0, price, dcaHealthy)
        assertEquals(
            expected,
            MintViewModel.previewCollateralSats(cents, tier0, price, dcaHealthy),
        )
        assertTrue(expected > 0)
    }

    @Test fun previewIsNullForNonPositiveAmount() {
        assertNull(MintViewModel.previewCollateralSats(0, tier0, price, dcaHealthy))
        assertNull(MintViewModel.previewCollateralSats(-1, tier0, price, dcaHealthy))
    }

    @Test fun shorterLockNeedsMoreCollateral() {
        // Tier 0 (1 hour, 1000%) locks far more than tier 9 (10 years, 200%).
        val cents = 100_000L
        val short = MintViewModel.previewCollateralSats(cents, tier0, price, dcaHealthy)!!
        val long = MintViewModel.previewCollateralSats(
            cents, LockTiers.byIndex(9), price, dcaHealthy,
        )!!
        assertTrue(short > long)
    }
}
