package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing the paired DigiDollar address lines the native derivation returns.
 *
 * Each line carries the SAME taproot key in two encodings — `DD…` for the backend lookup, X(Q)
 * for locating the token output and for a transfer's recipient field — plus the derivation
 * position needed to sign. They travel together because a mismatch between the two means looking
 * up one wallet's dollars while trying to spend another's.
 *
 * The values below are the real derivation for the wallet funded on mainnet during this work:
 * X(Q) `076cc826…` is exactly the key in that transaction's P2TR script `5120076cc826…`.
 */
class DigiDollarAddressLineTest {

    private val dd = "DD1JAMfBqU9mVnwqZE5SJfJCYT7TKCBnpGeMQQgdDThvbcKbDaTN"
    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"

    @Test fun `a well-formed line parses into its four parts`() {
        val a = DigiDollarAddress.parse("$dd|$xq|0|0")!!
        assertEquals(dd, a.ddAddress)
        assertEquals(xq, a.taprootOutputKeyHex)
        assertEquals(0, a.chain)
        assertEquals(0, a.index)
    }

    @Test fun `the internal chain is carried through`() {
        val a = DigiDollarAddress.parse("$dd|$xq|1|7")!!
        assertEquals(1, a.chain)
        assertEquals(7, a.index)
    }

    /**
     * A derivation that failed comes back as an empty slot rather than a line. Reading it as an
     * address would query the backend for "" and, worse, could pair a blank key with a real
     * position.
     */
    @Test fun `an empty slot is not an address`() {
        assertNull(DigiDollarAddress.parse(""))
        assertNull(DigiDollarAddress.parse("   "))
    }

    @Test fun `a truncated line is rejected rather than half-read`() {
        assertNull(DigiDollarAddress.parse("$dd|$xq|0"))
        assertNull(DigiDollarAddress.parse(dd))
    }

    /** A key that is not 32 bytes cannot be a taproot output key; accepting it would build a
     *  transfer paying a malformed script. */
    @Test fun `a short or malformed key is rejected`() {
        assertNull(DigiDollarAddress.parse("$dd|076cc826|0|0"))
        assertNull(DigiDollarAddress.parse("$dd|${"z".repeat(64)}|0|0"))
    }

    @Test fun `a non-numeric position is rejected`() {
        assertNull(DigiDollarAddress.parse("$dd|$xq|x|0"))
        assertNull(DigiDollarAddress.parse("$dd|$xq|0|y"))
    }

    @Test fun `a batch keeps only the parseable lines`() {
        val parsed = DigiDollarAddress.parseAll(
            arrayOf("$dd|$xq|0|0", "", "$dd|$xq|1|0", "garbage")
        )
        assertEquals(2, parsed.size)
        assertTrue(parsed.map { it.chain } == listOf(0, 1))
    }

    /** The script this key is spent from — matching what appeared on chain. */
    @Test fun `the p2tr script is derivable from the key`() {
        val a = DigiDollarAddress.parse("$dd|$xq|0|0")!!
        assertEquals("5120$xq", a.scriptPubKeyHex)
    }
}
