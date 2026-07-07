package io.digibyte.digidollar

/**
 * Consensus DigiDollar transaction limits (DigiByte Core v9.26.4,
 * consensus/digidollar.h defaults; identical on mainnet and testnet).
 * The node enforces these at mempool/consensus level — the wallet checks
 * them before signing so users get an actionable error, not a raw reject.
 */
object DigiDollarLimits {

    /** Smallest permitted Mint: $100. */
    const val MIN_MINT_CENTS = 10_000L

    /** Largest permitted Mint: $100,000. */
    const val MAX_MINT_CENTS = 10_000_000L

    /** Smallest permitted DigiDollar output: $1. */
    const val MIN_OUTPUT_CENTS = 100L

    /** Fee floor for every DigiDollar transaction: 0.1 DGB. */
    const val MIN_DD_TX_FEE_SATS = 10_000_000L
}
