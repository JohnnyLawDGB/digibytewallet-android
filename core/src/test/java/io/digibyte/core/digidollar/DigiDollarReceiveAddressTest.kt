package io.digibyte.core.digidollar

import io.digibyte.digidollar.DdAddress
import io.digibyte.digidollar.EcOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #18 receive-screen half: the wallet's DigiDollar receive address.
 *
 * The taproot tweak math itself is proven against Core vectors in
 * :digidollar TaprootTest; here the identity [EcOps] makes the tweak a no-op,
 * so [DigiDollarReceiveAddress.forOwnerKey] must reproduce the exact `TD…`
 * golden vector that :digidollar DdAddressTest encodes from the same key —
 * pinning the owner-key → DD-address wiring end to end.
 */
class DigiDollarReceiveAddressTest {

    // Identity tweak: parity(0) + key, so ddTokenOutputKey returns the key
    // unchanged and the DD-token output key == the Owner key.
    private val identityEcOps = EcOps { key, _ -> ByteArray(1) + key }

    // The real testnet26 vector shared with :digidollar DdAddressTest.
    private val goldenKeyHex = "dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b"
    private val goldenAddress = "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC"

    @Test
    fun `derives the testnet TD address from the owner key`() {
        assertEquals(
            goldenAddress,
            DigiDollarReceiveAddress.forOwnerKey(goldenKeyHex, identityEcOps, testnet = true),
        )
    }

    @Test
    fun `mainnet selection yields a DD-prefixed address`() {
        val addr = DigiDollarReceiveAddress.forOwnerKey(goldenKeyHex, identityEcOps, testnet = false)
        assertEquals("DD", addr.substring(0, 2))
        // and it round-trips back to the same output key on mainnet
        val decoded = checkNotNull(DdAddress.decode(addr))
        assertEquals(DdAddress.Network.MAINNET, decoded.network)
        assertEquals(goldenKeyHex, decoded.outputKeyHex)
    }

    @Test
    fun `produced address always decodes back to the tweaked output key`() {
        val addr = DigiDollarReceiveAddress.forOwnerKey(goldenKeyHex, identityEcOps, testnet = true)
        val decoded = checkNotNull(DdAddress.decode(addr))
        assertEquals(DdAddress.Network.TESTNET, decoded.network)
        assertEquals(goldenKeyHex, decoded.outputKeyHex)
        assertNull("a truncated address must not decode", DdAddress.decode(addr.dropLast(3)))
    }

    @Test
    fun `a non-32-byte owner key is rejected, not silently encoded`() {
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                DigiDollarReceiveAddress.forOwnerKey("dead", identityEcOps, testnet = true)
            }.message.orEmpty().contains("32 bytes"),
        )
    }
}
