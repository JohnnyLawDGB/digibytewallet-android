package app.aroundtheblock.wallet.core.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smallest output the network relays, per script type, exactly as DigiByte Core 9.26 decides it
 * (`GetDustThreshold` / `IsDust` in `src/policy/policy.cpp`, `DUST_RELAY_TX_FEE` = 30,000 sat/kvB in
 * `src/policy/policy.h` — ten times Bitcoin's). A payment below its line is built, signed and then
 * refused by every node, so the send path refuses it first.
 *
 * DigiDollar token outputs are zero-value by protocol. Core lets them through below the line — but
 * only a zero-value, 34-byte, OP_1 output, and only inside a transaction carrying the DigiDollar
 * version marker (`IsDigiDollarDustExemptOutput`, and `IsStandardTx`'s marker-and-type test). The
 * same exemption here, or this check would refuse every DigiDollar send.
 */
class DustPolicyTest {

    private fun script(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val p2pkh = script("76a914" + "22".repeat(20) + "88ac")          // 25 bytes
    private val p2sh = script("a914" + "33".repeat(20) + "87")               // 23 bytes
    private val p2wpkh = script("0014" + "44".repeat(20))                    // 22 bytes
    private val p2wsh = script("0020" + "55".repeat(32))                     // 34 bytes
    private val p2tr = script("5120" + "66".repeat(32))                      // 34 bytes
    private val opReturn = script("6a024444010202c409024c1d")

    private fun lineIs(name: String, s: ByteArray, line: Long) {
        assertEquals("$name: the dust line is not DigiByte Core's", line, DustPolicy.threshold(s))
        assertTrue("$name: ${line - 1} sat is below the line and must be refused", DustPolicy.isDust(line - 1, s, txIsDigiDollar = false))
        assertFalse("$name: $line sat is at the line and must be sent", DustPolicy.isDust(line, s, txIsDigiDollar = false))
    }

    @Test fun `legacy P2PKH is dust below 5,460 sat`() = lineIs("P2PKH", p2pkh, 5_460)

    @Test fun `script-hash P2SH is dust below 5,400 sat`() = lineIs("P2SH", p2sh, 5_400)

    @Test fun `native segwit P2WPKH is dust below 2,940 sat`() = lineIs("P2WPKH", p2wpkh, 2_940)

    @Test fun `Taproot P2TR is dust below 3,300 sat`() = lineIs("P2TR", p2tr, 3_300)

    @Test fun `segwit script-hash P2WSH is dust below 3,300 sat`() = lineIs("P2WSH", p2wsh, 3_300)

    @Test fun `an unspendable data output has no line`() {
        assertEquals(0L, DustPolicy.threshold(opReturn))
        assertFalse(DustPolicy.isDust(0, opReturn, txIsDigiDollar = false))
        assertEquals("a script over Core's 10,000-byte limit is unspendable", 0L, DustPolicy.threshold(ByteArray(10_001) { 0x51 }))
    }

    @Test fun `a witness program is recognised by its shape, as Core recognises it`() {
        // Version 16 with a 40-byte program: a witness program. 41 bytes of program: not one.
        assertEquals(30L * (8 + 1 + 42 + 67), DustPolicy.threshold(script("6028" + "77".repeat(40))))
        assertEquals(30L * (8 + 1 + 43 + 148), DustPolicy.threshold(script("6029" + "77".repeat(41))))
    }

    @Test fun `a DigiDollar token output is exempt inside a DigiDollar transaction`() {
        assertTrue(DustPolicy.isDigiDollarExempt(0, p2tr, txIsDigiDollar = true))
        assertFalse("a DigiDollar token output was refused as dust — every DigiDollar send would be", DustPolicy.isDust(0, p2tr, txIsDigiDollar = true))
    }

    @Test fun `the exemption is exactly Core's - zero value, 34-byte OP_1 script, DigiDollar transaction`() {
        assertTrue("outside a DigiDollar transaction a zero-value Taproot output is dust", DustPolicy.isDust(0, p2tr, txIsDigiDollar = false))
        assertTrue("one satoshi is not zero", DustPolicy.isDust(1, p2tr, txIsDigiDollar = true))
        assertTrue("a P2WSH script is 34 bytes but not OP_1", DustPolicy.isDust(0, p2wsh, txIsDigiDollar = true))
        assertTrue("a P2WPKH script is not 34 bytes", DustPolicy.isDust(0, p2wpkh, txIsDigiDollar = true))
        assertTrue("ordinary DGB outputs of a DigiDollar transaction keep the line", DustPolicy.isDust(3_299, p2tr, txIsDigiDollar = true))
    }

    @Test fun `a DigiDollar transaction is the marker with a type Core knows`() {
        assertTrue(DustPolicy.isDigiDollarTransaction(0x01000770))   // mint
        assertTrue(DustPolicy.isDigiDollarTransaction(0x02000770))   // transfer
        assertTrue(DustPolicy.isDigiDollarTransaction(0x03000770))   // redeem
        assertTrue("flag bits do not matter", DustPolicy.isDigiDollarTransaction(0x02FF0770))
        assertFalse("type 0 is no DigiDollar transaction", DustPolicy.isDigiDollarTransaction(0x00000770))
        assertFalse("types from 4 are not", DustPolicy.isDigiDollarTransaction(0x04000770))
        assertFalse("no marker", DustPolicy.isDigiDollarTransaction(2))
        assertFalse(DustPolicy.isDigiDollarTransaction(0x02000771))
    }

    /**
     * A real DigiDollar transfer as it is on chain: DigiByte testnet26, block 83946, txid
     * 3d8797cb87f6903bceeea28e6366093faec34af629e051dfda7b3a9616c5a346 (the golden vector of
     * `digidollar_realtx_kat`). Version 0x02000770; two zero-value Taproot token outputs, 2.88 DGB
     * of Taproot change, and the OP_RETURN. Every output passes, so a DigiDollar send still goes.
     */
    @Test fun `a real DigiDollar transfer has no dust output`() {
        val version = 0x02000770
        val outputs = listOf(
            0L to "5120effc01464e19f45d27d3fd670e62f4b21a441e894bc9cd1726b99ca8c02000cd",
            0L to "512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a3",
            288_000_000L to "512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a3",
            0L to "6a024444010202c409024c1d",
        )
        val dd = DustPolicy.isDigiDollarTransaction(version)
        assertTrue(dd)
        for ((value, hex) in outputs) {
            assertFalse("output $value sat $hex of a real DigiDollar transfer is refused as dust", DustPolicy.isDust(value, script(hex), dd))
        }
        // And the exemption is what lets the token outputs through.
        assertTrue(DustPolicy.isDust(0, script(outputs[0].second), txIsDigiDollar = false))
    }
}
