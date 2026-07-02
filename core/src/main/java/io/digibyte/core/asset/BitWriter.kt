package io.digibyte.core.asset

/**
 * Bit-level writer for DigiAsset OP_RETURN data — the inverse of [BitReader].
 *
 * DigiAsset fields are packed at the bit level MSB-first with no alignment
 * constraints. After the last write, the remaining bits of the final byte
 * are padded with zeros; the decoder tolerates 0–7 trailing zero bits.
 *
 * SFFC (Short Fixed-precision Float Coding) implements the same variable-width
 * integer format the decoder expects: `value = mantissa × 10^exponent` with
 * the smallest bucket that can represent the input exactly.
 */
class BitWriter(initialCapacityBytes: Int = 8) {

    private var buf: ByteArray = ByteArray(maxOf(1, initialCapacityBytes))
    private var bitPos: Int = 0

    /**
     * Write the low [count] bits of [value] to the stream, MSB-first.
     * @throws IllegalArgumentException if count !in 1..64.
     */
    fun writeBits(value: Long, count: Int) {
        require(count in 1..64) { "count must be 1..64, got $count" }
        var remaining = count
        while (remaining > 0) {
            val byteIndex = bitPos ushr 3
            val bitOffset = bitPos and 7
            val bitsAvailableInByte = 8 - bitOffset
            val bitsToWrite = minOf(remaining, bitsAvailableInByte)
            val shift = remaining - bitsToWrite
            val mask = if (bitsToWrite == 64) -1L else (1L shl bitsToWrite) - 1
            val chunk = ((value ushr shift) and mask).toInt()
            val shiftIntoByte = bitsAvailableInByte - bitsToWrite
            ensureCapacity(byteIndex)
            buf[byteIndex] = (buf[byteIndex].toInt() or (chunk shl shiftIntoByte)).toByte()
            bitPos += bitsToWrite
            remaining -= bitsToWrite
        }
    }

    /** Convenience: write 8 bits from a byte. */
    fun writeByte(b: Byte): Unit = writeBits(b.toLong() and 0xFF, 8)

    /** Write every byte in [bytes] as an 8-bit chunk. */
    fun writeBytes(bytes: ByteArray) {
        for (b in bytes) writeByte(b)
    }

    /**
     * Encode a non-negative integer using the 7-bucket SFFC format that
     * [BitReader.readFixedPrecision] consumes. Picks the smallest bucket
     * whose (mantissa, exponent) pair represents [value] exactly.
     *
     * @throws IllegalArgumentException if value is negative or too large
     *         to fit in bucket 6 (2^54 - 1 ≈ 1.8×10^16).
     */
    fun writeFixedPrecision(value: Long) {
        require(value >= 0) { "value must be non-negative, got $value" }

        // Bucket 0: 3-bit prefix `000` + 5-bit mantissa (no exp). Values 0..31.
        if (value <= 31L) {
            writeBits(0b000L, 3)
            writeBits(value, 5)
            return
        }

        // Factor out trailing base-10 zeros.
        var m = value
        var e = 0
        while (m % 10 == 0L) {
            m /= 10
            e++
        }

        // Buckets 1..5: `(prefix, manBits, expBits)` triples. Try in order
        // (smallest bucket wins) and pick the first that fits both mantissa
        // and exponent. Note: buckets 1..3 use 4 exp bits, 4..5 use 3.
        data class Bucket(val prefix: Int, val manBits: Int, val expBits: Int)
        val buckets = listOf(
            Bucket(0b001, 9, 4),   // 2-byte
            Bucket(0b010, 17, 4),  // 3-byte
            Bucket(0b011, 25, 4),  // 4-byte
            Bucket(0b100, 34, 3),  // 5-byte
            Bucket(0b101, 42, 3),  // 6-byte
        )
        for (bucket in buckets) {
            val mMax = (1L shl bucket.manBits) - 1
            val eMax = (1 shl bucket.expBits) - 1
            if (m <= mMax && e <= eMax) {
                writeBits(bucket.prefix.toLong(), 3)
                writeBits(m, bucket.manBits)
                writeBits(e.toLong(), bucket.expBits)
                return
            }
        }

        // Bucket 6: 2-bit prefix `11` + 54-bit mantissa, no exponent.
        // Value stored as raw (m × 10^e), must fit in 54 bits.
        val raw = m * BitReader.pow10(e)
        require(raw in 0 until (1L shl 54)) { "value too large for SFFC: $value" }
        writeBits(0b11L, 2)
        writeBits(raw, 54)
    }

    /**
     * Return the byte array written so far. If the last byte is partial,
     * it is padded with trailing zero bits (the decoder tolerates this).
     */
    fun toByteArray(): ByteArray {
        val byteCount = (bitPos + 7) ushr 3
        return buf.copyOf(byteCount)
    }

    /** Current position in bits. */
    fun positionBits(): Int = bitPos

    private fun ensureCapacity(byteIndex: Int) {
        if (byteIndex >= buf.size) {
            var newSize = buf.size
            while (newSize <= byteIndex) newSize *= 2
            buf = buf.copyOf(newSize)
        }
    }
}
