package io.digibyte.core.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [BitReader], the bit-level reader used by the DigiAsset decoder.
 *
 * Tests cover basic bit reading, cross-byte reading, and the fixed-precision
 * number format used for DigiAsset quantities.
 */
class BitReaderTest {

    // ================================================================
    // Basic bit reading
    // ================================================================

    @Test
    fun `readBits extracts correct bits from single byte`() {
        // 0xA5 = 1010 0101
        val reader = BitReader(byteArrayOf(0xA5.toByte()))
        assertEquals(1L, reader.readBits(1))   // 1
        assertEquals(0L, reader.readBits(1))   // 0
        assertEquals(2L, reader.readBits(2))   // 10
        assertEquals(5L, reader.readBits(4))   // 0101
    }

    @Test
    fun `readBits crosses byte boundary correctly`() {
        // 0xFF 0x00 = 11111111 00000000
        val reader = BitReader(byteArrayOf(0xFF.toByte(), 0x00))
        reader.readBits(4) // skip 1111
        // Next 8 bits span bytes: 1111 0000
        assertEquals(0xF0L, reader.readBits(8))
    }

    @Test
    fun `readBits handles full byte reads`() {
        val reader = BitReader(byteArrayOf(0x44, 0x41))
        assertEquals(0x44L, reader.readBits(8))
        assertEquals(0x41L, reader.readBits(8))
    }

    @Test
    fun `readBits handles 16-bit read`() {
        val reader = BitReader(byteArrayOf(0x44, 0x41))
        assertEquals(0x4441L, reader.readBits(16))
    }

    @Test
    fun `bitsRemaining tracks position correctly`() {
        val reader = BitReader(byteArrayOf(0xFF.toByte(), 0x00))
        assertEquals(16, reader.bitsRemaining())
        reader.readBits(5)
        assertEquals(11, reader.bitsRemaining())
        reader.readBits(11)
        assertEquals(0, reader.bitsRemaining())
    }

    @Test
    fun `skip advances position without reading`() {
        val reader = BitReader(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        reader.skip(8) // skip first byte
        assertEquals(0xCDL, reader.readBits(8))
    }

    @Test
    fun `readBytes reads full bytes`() {
        val reader = BitReader(byteArrayOf(0x44, 0x41, 0x03))
        val bytes = reader.readBytes(3)
        assertEquals(0x44.toByte(), bytes[0])
        assertEquals(0x41.toByte(), bytes[1])
        assertEquals(0x03.toByte(), bytes[2])
    }

    // ================================================================
    // Fixed-precision decoding
    // ================================================================

    @Test
    fun `readFixedPrecision decodes single-byte values (0-31)`() {
        // Value 0: 00000000
        assertEquals(0L, BitReader(byteArrayOf(0x00)).readFixedPrecision())
        // Value 1: 00000001
        assertEquals(1L, BitReader(byteArrayOf(0x01)).readFixedPrecision())
        // Value 5: 00000101
        assertEquals(5L, BitReader(byteArrayOf(0x05)).readFixedPrecision())
        // Value 31: 00011111
        assertEquals(31L, BitReader(byteArrayOf(0x1F)).readFixedPrecision())
    }

    @Test
    fun `readFixedPrecision decodes two-byte values`() {
        // 32: bits = 001 000100000 0000 -> 0x2200
        assertEquals(32L, BitReader(byteArrayOf(0x22, 0x00)).readFixedPrecision())
        // 100: mantissa=1, exp=2 -> bits = 001 000000001 0010 -> 0x2012
        assertEquals(100L, BitReader(byteArrayOf(0x20, 0x12)).readFixedPrecision())
        // 500: mantissa=5, exp=2 -> bits = 001 000000101 0010 -> 0x2052
        assertEquals(500L, BitReader(byteArrayOf(0x20, 0x52)).readFixedPrecision())
        // 1000: mantissa=1, exp=3 -> bits = 001 000000001 0011 -> 0x2013
        assertEquals(1000L, BitReader(byteArrayOf(0x20, 0x13)).readFixedPrecision())
        // 10000: mantissa=1, exp=4 -> bits = 001 000000001 0100 -> 0x2014
        assertEquals(10000L, BitReader(byteArrayOf(0x20, 0x14)).readFixedPrecision())
    }

    @Test
    fun `readFixedPrecision decodes 2100000`() {
        // 2100000: mantissa=21, exp=5 -> 21 * 10^5 = 2100000
        // bits = 001 000010101 0101 -> 0x2155
        assertEquals(2100000L, BitReader(byteArrayOf(0x21, 0x55)).readFixedPrecision())
    }

    @Test
    fun `readFixedPrecision decodes 100000000`() {
        // 100000000: mantissa=1, exp=8 -> 1 * 10^8
        // bits = 001 000000001 1000 -> 0x2018
        assertEquals(100000000L, BitReader(byteArrayOf(0x20, 0x18)).readFixedPrecision())
    }

    @Test
    fun `readFixedPrecision reads multiple values from stream`() {
        // Read 500 then 200 from a continuous stream
        // 500 = 0x2052 (16 bits), 200 = mantissa=2, exp=2 -> 001 000000010 0010 = 0x2022 (16 bits)
        val reader = BitReader(byteArrayOf(0x20, 0x52, 0x20, 0x22))
        assertEquals(500L, reader.readFixedPrecision())
        assertEquals(200L, reader.readFixedPrecision())
    }

    @Test
    fun `readFixedPrecision handles non-byte-aligned reads`() {
        // Read 3 bits of other data, then a precision value
        // 3 bits of 0s, then value 5 (00000101) = total 11 bits
        // 000 00000101 xxxxx -> in bytes: 0x00 0xA0 (with 5 padding bits)
        // Bits: 00000000 10100000
        val reader = BitReader(byteArrayOf(0x00, 0xA0.toByte()))
        reader.skip(3) // skip 3 bits
        assertEquals(5L, reader.readFixedPrecision())
    }

    // ================================================================
    // pow10 tests
    // ================================================================

    @Test
    fun `pow10 returns correct values`() {
        assertEquals(1L, BitReader.pow10(0))
        assertEquals(10L, BitReader.pow10(1))
        assertEquals(100L, BitReader.pow10(2))
        assertEquals(1000L, BitReader.pow10(3))
        assertEquals(1000000000L, BitReader.pow10(9))
        assertEquals(1000000000000000000L, BitReader.pow10(18))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pow10 rejects exponent over 18`() {
        BitReader.pow10(19)
    }
}
