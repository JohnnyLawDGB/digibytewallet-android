package io.digibyte.digidollar

import java.math.BigInteger

/**
 * Required-Collateral arithmetic, mirroring DigiByte Core v9.26.4 exactly
 * (consensus/dca.cpp basis-points ceiling math; digidollar/txbuilder.cpp
 * collateral formula and +1% safety margin).
 *
 * Money units: DGB in satoshis, DigiDollar in cents, Oracle price in
 * micro-USD per DGB (1_000_000 = $1.00). Intermediates exceed Long range,
 * so the math runs in BigInteger; inputs and results are Long satoshis.
 */
object Collateral {

    /** Satoshis per DGB. */
    const val COIN = 100_000_000L

    /** 21 billion DGB in satoshis. */
    const val MAX_MONEY = 21_000_000_000L * COIN

    /** Basis-points scale: 10,000 bps = 1.0x (healthy DCA). */
    const val DCA_BPS_SCALE = 10_000L

    /**
     * DGB satoshis that must be locked to Mint [ddCents] of DigiDollar at
     * [tier], given the Oracle price and DCA multiplier.
     */
    fun requiredSats(
        ddCents: Long,
        tier: LockTier,
        oraclePriceMicroUsd: Long,
        dcaMultiplierBps: Long = DCA_BPS_SCALE,
    ): Long {
        require(ddCents > 0) { "DigiDollar amount must be positive, got $ddCents" }
        require(oraclePriceMicroUsd > 0) { "Oracle price must be positive, got $oraclePriceMicroUsd" }
        require(dcaMultiplierBps > 0) { "DCA multiplier must be positive, got $dcaMultiplierBps" }

        // Core dca.cpp ApplyDCA: effectiveRatio = ceil(baseRatio * bps / 10000).
        // Core-anchored across all bands (v9.26.4 regtest, 2026-07-07): the
        // healthy (10000), warning (12500), critical (15000) and emergency
        // (20000) multipliers each reproduce Core's calculatecollateralrequirement
        // wallet_collateral satoshi-exact — see CollateralTest. Those four are
        // Core's only reachable bands (dca.cpp HEALTH_TIERS).
        val effectiveRatio = ceilDiv(
            tier.ratioPercent.toBigInteger() * dcaMultiplierBps.toBigInteger(),
            DCA_BPS_SCALE.toBigInteger(),
        )

        // Core txbuilder.cpp: base = ceil(ddCents * COIN * ratio * 100 / priceMicroUsd)
        val base = ceilDiv(
            ddCents.toBigInteger() * COIN.toBigInteger() * effectiveRatio * BigInteger.valueOf(100),
            oraclePriceMicroUsd.toBigInteger(),
        )
        val maxMoney = MAX_MONEY.toBigInteger()
        require(base <= maxMoney) { "required Collateral exceeds MAX_MONEY" }

        // Core ApplyCollateralSafetyMargin: floor(base * 101 / 100), re-capped
        val withMargin = base * BigInteger.valueOf(101) / BigInteger.valueOf(100)
        require(withMargin <= maxMoney) { "required Collateral exceeds MAX_MONEY" }
        return withMargin.longValueExact()
    }

    private fun ceilDiv(n: BigInteger, d: BigInteger): BigInteger = (n + d - BigInteger.ONE) / d
}
