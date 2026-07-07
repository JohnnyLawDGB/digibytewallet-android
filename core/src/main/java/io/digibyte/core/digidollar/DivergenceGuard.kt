package io.digibyte.core.digidollar

import kotlin.math.abs

/**
 * ADR-0002 Mint price cross-check: a wrong Oracle price cannot steal funds
 * but causes bounded loss by over-collateralization, so the Oracle price
 * must agree with the wallet's independent fiat source (CoinGecko/Binance
 * via PriceProvider) before any Mint. Missing either price blocks — a Mint
 * priced on one source is exactly the failure mode this guard exists for.
 */
object DivergenceGuard {

    /** Maximum tolerated |oracle − independent| / independent, in basis
     *  points. 10% bounds the over-collateralization a bad Oracle price can
     *  cause while riding out normal cross-exchange spread. */
    const val DEFAULT_MAX_DIVERGENCE_BPS = 1_000L

    sealed class Decision {
        object Allow : Decision()
        data class Block(val reason: String) : Decision()
    }

    fun check(
        oracleMicroUsd: Long?,
        independentUsd: Double?,
        maxDivergenceBps: Long = DEFAULT_MAX_DIVERGENCE_BPS,
    ): Decision {
        if (oracleMicroUsd == null || oracleMicroUsd <= 0) {
            return Decision.Block("Oracle price unavailable — cannot verify the Mint price")
        }
        if (independentUsd == null || independentUsd <= 0.0) {
            return Decision.Block(
                "Independent DGB price unavailable — cannot cross-check the Oracle price",
            )
        }
        val oracleUsd = oracleMicroUsd / 1_000_000.0
        val divergenceBps = abs(oracleUsd - independentUsd) / independentUsd * 10_000
        if (divergenceBps > maxDivergenceBps) {
            val pct = "%.1f".format(divergenceBps / 100)
            return Decision.Block(
                "Oracle price ($$oracleUsd) diverges $pct% from the market price " +
                    "($$independentUsd) — Mint blocked until prices agree",
            )
        }
        return Decision.Allow
    }
}
