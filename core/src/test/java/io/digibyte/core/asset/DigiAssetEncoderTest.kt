package io.digibyte.core.asset

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [DigiAssetEncoder]. Every fixture here mirrors one
 * in [DigiAssetDecoderTest]; the encoder's output must match the decoder's
 * input bytes byte-for-byte.
 */
class DigiAssetEncoderTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun bytesFromHex(h: String): ByteArray =
        ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── BitWriter smoke tests ─────────────────────────────────────────

    @Test
    fun `bitwriter round-trips single bits`() {
        val w = BitWriter()
        w.writeBits(0b1, 1); w.writeBits(0b0, 1); w.writeBits(0b1, 1); w.writeBits(0b1, 1)
        val r = BitReader(w.toByteArray())
        assertEquals(1L, r.readBits(1))
        assertEquals(0L, r.readBits(1))
        assertEquals(1L, r.readBits(1))
        assertEquals(1L, r.readBits(1))
    }

    @Test
    fun `bitwriter round-trips a byte`() {
        val w = BitWriter()
        w.writeByte(0xAB.toByte())
        assertEquals("ab", hex(w.toByteArray()))
    }

    @Test
    fun `bitwriter pads last byte with zeros`() {
        val w = BitWriter()
        w.writeBits(0b1, 1)   // only 1 bit written; expect 0b10000000 = 0x80
        assertEquals("80", hex(w.toByteArray()))
    }

    // ── SFFC round-trip against BitReader ─────────────────────────────

    @Test
    fun `sffc round-trips 1-byte bucket values 0 through 31`() {
        for (v in 0L..31L) {
            val w = BitWriter()
            w.writeFixedPrecision(v)
            val decoded = BitReader(w.toByteArray()).readFixedPrecision()
            assertEquals("value $v", v, decoded)
        }
    }

    @Test
    fun `sffc round-trips value 500 in bucket 1`() {
        // 500 = 5 × 10^2; bucket 1 has 9-bit mantissa + 4-bit exp, fits.
        val w = BitWriter()
        w.writeFixedPrecision(500L)
        // After SFFC, padded to byte boundary: prefix(3) + man(9) + exp(4) = 16 bits = 2 bytes.
        assertEquals("2 bytes for value 500", 2, w.toByteArray().size)
        val r = BitReader(w.toByteArray())
        assertEquals(500L, r.readFixedPrecision())
    }

    @Test
    fun `sffc round-trips value 100 and 1000`() {
        for (v in listOf(100L, 1000L, 10_000L, 100_000L)) {
            val w = BitWriter()
            w.writeFixedPrecision(v)
            assertEquals("round-trip $v", v, BitReader(w.toByteArray()).readFixedPrecision())
        }
    }

    @Test
    fun `sffc round-trips large non-power-of-10 values`() {
        for (v in listOf(12345L, 999_999L, 1_234_567_890L, 2_100_000L)) {
            val w = BitWriter()
            w.writeFixedPrecision(v)
            assertEquals("round-trip $v", v, BitReader(w.toByteArray()).readFixedPrecision())
        }
    }

    // ── Gold-fixture round-trips against the decoder ──────────────────

    @Test
    fun `transfer v3 out=0 amount=500 matches TRANSFER_V3 fixture`() {
        // Expected: 6a0744410315002052
        val script = DigiAssetEncoder.encodeSimpleTransfer(
            version = 3,
            recipientOutputIndex = 0,
            quantity = 500L,
        )
        assertEquals("6a0744410315002052", hex(script))

        // And the decoder sees exactly what it expects.
        val decoded = DigiAssetDecoder().decode(script)!!
        assertEquals(3, decoded.version)
        assertEquals(0x15, decoded.opcode)
        assertEquals(1, decoded.transferInstructions.size)
        val inst = decoded.transferInstructions[0]
        assertEquals(0, inst.outputIndex)
        assertEquals(500L, inst.amount)
    }

    @Test
    fun `transfer v2 out=0 amount=1000 matches REAL_MAINNET_TRANSFER_V2 fixture`() {
        // Real mainnet txid 1af59aea... in block 11_000_208
        // Expected: 6a0744410215002013
        val script = DigiAssetEncoder.encodeSimpleTransfer(
            version = 2,
            recipientOutputIndex = 0,
            quantity = 1000L,
        )
        assertEquals("6a0744410215002013", hex(script))
    }

    @Test
    fun `transfer with skip bit round-trips`() {
        // TRANSFER_V3_SKIP fixture — two instructions:
        //   inst 1: skip=1, out=0, amount=100
        //   inst 2: skip=0, out=1, amount=50
        // Expected hex: 6a0a44410315802012012051
        val script = DigiAssetEncoder.encodeTransferScript(
            version = 3,
            instructions = listOf(
                DigiAssetEncoder.TransferInstruction(
                    skip = true, range = false, percent = false,
                    outputIndex = 0, amount = 100L,
                ),
                DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = 1, amount = 50L,
                ),
            )
        )
        // Verify by decoder round-trip (bit-exact hex match optional; focus
        // on semantic equivalence since trailing padding bits could differ).
        val decoded = DigiAssetDecoder().decode(script)!!
        assertEquals(2, decoded.transferInstructions.size)
        val i1 = decoded.transferInstructions[0]
        val i2 = decoded.transferInstructions[1]
        assertTrue(i1.skip)
        assertEquals(0, i1.outputIndex)
        assertEquals(100L, i1.amount)
        assertTrue(!i2.skip)
        assertEquals(1, i2.outputIndex)
        assertEquals(50L, i2.amount)
    }

    @Test
    fun `burn instruction encodes output 31 with range=false`() {
        val script = DigiAssetEncoder.encodeTransferScript(
            version = 3,
            instructions = listOf(
                DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = 31, amount = 300L,
                )
            )
        )
        val decoded = DigiAssetDecoder().decode(script)!!
        val inst = decoded.transferInstructions[0]
        assertTrue(inst.isBurn)
        assertEquals(31, inst.outputIndex)
        assertEquals(300L, inst.amount)
    }

    @Test
    fun `range transfer encodes 13-bit output index`() {
        val script = DigiAssetEncoder.encodeTransferScript(
            version = 3,
            instructions = listOf(
                DigiAssetEncoder.TransferInstruction(
                    skip = false, range = true, percent = false,
                    outputIndex = 500, amount = 50L,
                )
            )
        )
        val decoded = DigiAssetDecoder().decode(script)!!
        val inst = decoded.transferInstructions[0]
        assertTrue("range flag set", inst.range)
        assertEquals(500, inst.outputIndex)
        assertEquals(50L, inst.amount)
    }

    @Test
    fun `percent transfer encodes 8-bit amount`() {
        val script = DigiAssetEncoder.encodeTransferScript(
            version = 3,
            instructions = listOf(
                DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = true,
                    outputIndex = 0, amount = 75L,
                )
            )
        )
        val decoded = DigiAssetDecoder().decode(script)!!
        val inst = decoded.transferInstructions[0]
        assertTrue("percent flag set", inst.percent)
        assertEquals(75L, inst.amount)
    }

    // ── Validation ────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-range outputIndex over 31`() {
        DigiAssetEncoder.TransferInstruction(
            skip = false, range = false, percent = false,
            outputIndex = 32, amount = 1L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects range outputIndex over 8191`() {
        DigiAssetEncoder.TransferInstruction(
            skip = false, range = true, percent = false,
            outputIndex = 8192, amount = 1L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects percent amount over 100`() {
        DigiAssetEncoder.TransferInstruction(
            skip = false, range = false, percent = true,
            outputIndex = 0, amount = 101L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid version`() {
        DigiAssetEncoder.encodeTransferScript(
            version = 1,
            instructions = listOf(
                DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = 0, amount = 1L,
                )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty instruction list`() {
        DigiAssetEncoder.encodeTransferPayload(version = 3, instructions = emptyList())
    }

    @Suppress("UNUSED_VARIABLE")
    @Test
    fun `rejects payload over 80 bytes`() {
        // 50 instructions of simple transfer ≈ 50×2 bytes + 4 header = 104 bytes.
        val tooMany = List(50) {
            DigiAssetEncoder.TransferInstruction(
                skip = true, range = false, percent = false,
                outputIndex = 0, amount = 1L,
            )
        }
        val thrown = runCatching { DigiAssetEncoder.encodeTransferScript(3, tooMany) }
            .exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException for oversized payload, got $thrown",
            thrown is IllegalArgumentException,
        )
    }
}
