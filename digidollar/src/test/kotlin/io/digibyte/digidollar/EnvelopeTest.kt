package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class EnvelopeTest {

    // nVersion from fixtures/mint-tx.json — a Core-built regtest Mint.
    @Test
    fun `mint fixture nVersion parses as a DigiDollar Mint`() {
        assertEquals(DigiDollarTxType.MINT, DigiDollarVersion.parse(16779120))
    }

    // Fixture nVersions: mint-tx.json, transfer-tx.json, redeem-tx.json.
    @Test
    fun `built versions match the Core fixture nVersions for all three types`() {
        assertEquals(16779120, DigiDollarVersion.build(DigiDollarTxType.MINT))
        assertEquals(0x02000770, DigiDollarVersion.build(DigiDollarTxType.TRANSFER))
        assertEquals(0x03000770, DigiDollarVersion.build(DigiDollarTxType.REDEMPTION))
    }

    @Test
    fun `plain and malformed nVersions parse as not DigiDollar`() {
        assertNull(DigiDollarVersion.parse(2)) // ordinary spend (spend-tx.json)
        assertNull(DigiDollarVersion.parse(1))
        assertNull(DigiDollarVersion.parse(0x04000770)) // marker but unknown type code
        assertNull(DigiDollarVersion.parse(0x00000770)) // marker but type code zero
        assertNull(DigiDollarVersion.parse(0x01000771)) // wrong marker
    }
}
