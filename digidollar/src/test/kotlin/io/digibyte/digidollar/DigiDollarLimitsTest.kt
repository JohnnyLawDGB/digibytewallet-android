package io.digibyte.digidollar

import kotlin.test.assertEquals
import org.junit.Test

class DigiDollarLimitsTest {

    // Consensus limits (Core v9.26.4 consensus/digidollar.h defaults;
    // mainnet == testnet). The wallet checks these before signing so users
    // get an actionable error, not a raw node reject.
    @Test
    fun `mainnet and testnet share the consensus mint and output limits`() {
        assertEquals(10_000, DigiDollarLimits.MIN_MINT_CENTS) // $100
        assertEquals(10_000_000, DigiDollarLimits.MAX_MINT_CENTS) // $100k
        assertEquals(100, DigiDollarLimits.MIN_OUTPUT_CENTS) // $1
        assertEquals(10_000_000, DigiDollarLimits.MIN_DD_TX_FEE_SATS) // 0.1 DGB
    }
}
