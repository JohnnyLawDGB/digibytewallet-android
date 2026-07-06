package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * DD address codec KATs. The golden vector is a REAL testnet26 address
 * returned by a v9.26.3 node (docs/superpowers/specs/
 * 2026-07-04-digidollar-wire-format.md): Base58Check over
 * 2-byte network version || 32-byte FINAL tweaked output key.
 */
class DdAddressTest {

    private val goldenAddress = "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC"
    private val goldenKeyHex = "dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b"

    @Test
    fun `encodes the golden testnet output key to the node's TD address`() {
        assertEquals(
            goldenAddress,
            DdAddress.encode(goldenKeyHex.hexToByteArray(), DdAddress.Network.TESTNET),
        )
    }

    @Test
    fun `decodes the golden TD address back to the FINAL tweaked key`() {
        val decoded = checkNotNull(DdAddress.decode(goldenAddress))
        assertEquals(DdAddress.Network.TESTNET, decoded.network)
        assertEquals(goldenKeyHex, decoded.outputKeyHex)
    }

    @Test
    fun `mainnet and regtest addresses carry their own version prefixes`() {
        val key = goldenKeyHex.hexToByteArray()
        val dd = DdAddress.encode(key, DdAddress.Network.MAINNET)
        val rd = DdAddress.encode(key, DdAddress.Network.REGTEST)
        assertEquals("DD", dd.substring(0, 2))
        assertEquals("RD", rd.substring(0, 2))
        assertEquals(DdAddress.Network.MAINNET, DdAddress.decode(dd)?.network)
        assertEquals(DdAddress.Network.REGTEST, DdAddress.decode(rd)?.network)
    }

    @Test
    fun `rejects corrupted, truncated, and foreign addresses`() {
        // one flipped character breaks the checksum
        assertNull(DdAddress.decode(goldenAddress.dropLast(1) + "D"))
        assertNull(DdAddress.decode(goldenAddress.dropLast(4)))
        // a valid Base58Check string of the wrong payload shape/version
        assertNull(DdAddress.decode("DBqXpUW6LAi82XdiSK7K8bWnhBUmymDZDL")) // legacy DGB P2PKH
        assertNull(DdAddress.decode(""))
        assertNull(DdAddress.decode("not-base58-0OIl"))
    }
}
