package io.digibyte.core.asset

/**
 * Bit-level reader for DigiAsset OP_RETURN data.
 *
 * DigiAsset fields are packed at the bit level using "BitIO" encoding.
 * This reader wraps a byte array and provides bit-granular read access,
 * including the fixed-precision number format used for quantities.
 *
 * The fixed-precision format is a variable-width encoding where:
 *   - Values 0-31 fit in 1 byte (3-bit header 000, 5-bit mantissa)
 *   - Larger values use 2-7 bytes with a 3-bit length prefix,
 *     a mantissa, and an exponent (the value = mantissa * 10^exponent)
 *
 * Ported from RenzoDD/digiasset-core BitIO.cpp and RenzoDD/digibyte-js Precision.js.
 */
class BitReader(private val data: ByteArray) {

    /** Current read position in bits. */
    private var position: Int = 0

    /** Total number of bits available. */
    private val totalBits: Int = data.size * 8

    /** Number of bits remaining from the current position. */
    fun bitsRemaining(): Int = totalBits - position

    /**
     * Read [count] bits from the stream and return as a Long.
     * Maximum 64 bits.
     *
     * @throws IllegalStateException if not enough bits remain.
     * @throws IllegalArgumentException if count > 64.
     */
    fun readBits(count: Int): Long {
        require(count in 1..64) { "Bit count must be between 1 and 64, got $count" }
        check(bitsRemaining() >= count) { "Not enough bits: need $count, have ${bitsRemaining()}" }

        var result = 0L
        var remaining = count

        while (remaining > 0) {
            val byteIndex = position / 8
            val bitOffset = position % 8
            val bitsAvailableInByte = 8 - bitOffset
            val bitsToRead = minOf(remaining, bitsAvailableInByte)

            // Extract bits from current byte, MSB-first
            val shift = bitsAvailableInByte - bitsToRead
            val mask = ((1 shl bitsToRead) - 1)
            val bits = (data[byteIndex].toInt() ushr shift) and mask

            result = (result shl bitsToRead) or bits.toLong()
            position += bitsToRead
            remaining -= bitsToRead
        }

        return result
    }

    /**
     * Read [count] complete bytes from the stream.
     *
     * @throws IllegalStateException if not enough bits remain.
     */
    fun readBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        for (i in 0 until count) {
            result[i] = readBits(8).toByte()
        }
        return result
    }

    /**
     * Skip [count] bits without reading.
     *
     * @throws IllegalStateException if not enough bits remain.
     */
    fun skip(count: Int) {
        check(bitsRemaining() >= count) { "Not enough bits to skip: need $count, have ${bitsRemaining()}" }
        position += count
    }

    /**
     * Read a fixed-precision encoded integer from the bit stream.
     *
     * Encoding format (from BitIO.cpp getFixedPrecision):
     *   Read 3 bits as length indicator, add 1 to get byte count.
     *   If byte count >= 7, move back 1 bit and set byte count to 7.
     *
     *   byte count 1: mantissa = next 5 bits (value 0-31)
     *   byte count 2-4: mantissa = next (count*8-7) bits, exponent = next 4 bits
     *   byte count 5-6: mantissa = next (count*8-6) bits, exponent = next 3 bits
     *   byte count 7: mantissa = next 54 bits, no exponent
     *
     *   Result = mantissa * 10^exponent
     *
     * @throws IllegalStateException if not enough bits remain.
     */
    fun readFixedPrecision(): Long {
        check(bitsRemaining() >= 3) { "Not enough bits for fixed precision header" }

        var length = readBits(3).toInt() + 1

        // Max length is 7 bytes; if 7 or 8, treat header bits differently
        if (length >= 7) {
            position -= 1 // move back 1 bit (the 3rd header bit is part of mantissa)
            length = 7
        }

        val mantissa: Long
        val exponent: Int

        when {
            length == 1 -> {
                // 1-byte number: 5-bit mantissa, no exponent
                mantissa = readBits(5)
                exponent = 0
            }
            length < 5 -> {
                // 2-4 byte number: (count*8-7) mantissa bits, 4 exponent bits
                val mantissaBits = length * 8 - 7
                mantissa = readBits(mantissaBits)
                exponent = readBits(4).toInt()
            }
            length < 7 -> {
                // 5-6 byte number: (count*8-6) mantissa bits, 3 exponent bits
                val mantissaBits = length * 8 - 6
                mantissa = readBits(mantissaBits)
                exponent = readBits(3).toInt()
            }
            else -> {
                // 7 byte number: 54-bit mantissa, no exponent
                mantissa = readBits(54)
                exponent = 0
            }
        }

        return mantissa * pow10(exponent)
    }

    /** Get current position in bits. */
    fun getPosition(): Int = position

    companion object {
        /** Lookup table for 10^n, n in 0..18. */
        private val POW10 = longArrayOf(
            1L,
            10L,
            100L,
            1_000L,
            10_000L,
            100_000L,
            1_000_000L,
            10_000_000L,
            100_000_000L,
            1_000_000_000L,
            10_000_000_000L,
            100_000_000_000L,
            1_000_000_000_000L,
            10_000_000_000_000L,
            100_000_000_000_000L,
            1_000_000_000_000_000L,
            10_000_000_000_000_000L,
            100_000_000_000_000_000L,
            1_000_000_000_000_000_000L
        )

        /**
         * Fast power of 10 using lookup table.
         * @throws IllegalArgumentException if exponent > 18.
         */
        fun pow10(exponent: Int): Long {
            require(exponent in 0..18) { "Exponent must be 0-18, got $exponent" }
            return POW10[exponent]
        }
    }
}
