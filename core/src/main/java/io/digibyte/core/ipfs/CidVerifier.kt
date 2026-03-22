package io.digibyte.core.ipfs

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Verifies IPFS content by checking that its hash matches the CID.
 *
 * Supported formats:
 *  - CIDv0  — "Qm…" (Base58-encoded SHA-256 multihash, 46 chars)
 *  - CIDv1  — multibase prefix + version(1) + codec varint + multihash
 *              Currently handles base32 ('b') and base58btc ('z') multibase prefixes.
 *
 * Multihash function codes recognised (others return null → verify returns false):
 *  - 0x12  sha2-256
 *  - 0x14  sha2-512 (rare, included for completeness)
 *  - 0x11  sha1     (legacy, included for completeness)
 */
class CidVerifier {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Verify that [content] hashes to the digest embedded in [cid].
     * Returns `true` only when the computed hash matches exactly.
     */
    fun verify(cid: String, content: ByteArray): Boolean {
        val expectedDigest = extractDigest(cid) ?: return false
        val algorithm = extractHashAlgorithm(cid) ?: return false
        val actualDigest = hash(content, algorithm)
        return expectedDigest.contentEquals(actualDigest)
    }

    // -------------------------------------------------------------------------
    // Internal helpers (internal so tests can reach them directly)
    // -------------------------------------------------------------------------

    /**
     * Extract the raw hash digest bytes from a CID string.
     */
    internal fun extractDigest(cid: String): ByteArray? {
        val multihash = cidToMultihash(cid) ?: return null
        return parseMultihashDigest(multihash)
    }

    /**
     * Extract the JCA algorithm name for the hash function named in the CID.
     */
    internal fun extractHashAlgorithm(cid: String): String? {
        val multihash = cidToMultihash(cid) ?: return null
        val (fnCode, _) = readVarint(multihash, 0) ?: return null
        return multihashCodeToAlgorithm(fnCode)
    }

    /**
     * Hash [content] with [algorithm] (JCA name, e.g. "SHA-256").
     */
    internal fun hash(content: ByteArray, algorithm: String): ByteArray =
        MessageDigest.getInstance(algorithm).digest(content)

    // -------------------------------------------------------------------------
    // CID → multihash bytes
    // -------------------------------------------------------------------------

    private fun cidToMultihash(cid: String): ByteArray? {
        if (cid.isBlank()) return null

        // CIDv0: starts with "Qm", always Base58-encoded multihash directly
        if (cid.startsWith("Qm")) {
            return decodeBase58(cid)
        }

        // CIDv1: multibase prefix + CBOR-variant binary
        val prefix = cid[0]
        val encoded = cid.substring(1)

        val raw: ByteArray = when (prefix) {
            'b', 'B' -> decodeBase32(encoded) ?: return null  // base32 lower / upper
            'z'      -> decodeBase58(encoded)  ?: return null  // base58btc
            'f', 'F' -> decodeHex(encoded)     ?: return null  // base16
            else     -> return null
        }

        // raw = version(varint) + codec(varint) + multihash
        // Skip version varint
        val (version, afterVersion) = readVarint(raw, 0) ?: return null
        if (version != 1L) return null
        // Skip codec varint
        val (_, afterCodec) = readVarint(raw, afterVersion) ?: return null
        // Remainder is the multihash
        return raw.copyOfRange(afterCodec, raw.size)
    }

    // -------------------------------------------------------------------------
    // Multihash helpers
    // -------------------------------------------------------------------------

    /** Parse digest bytes from a multihash (fn_code varint + digest_len varint + digest). */
    private fun parseMultihashDigest(multihash: ByteArray): ByteArray? {
        val (_, afterFn) = readVarint(multihash, 0) ?: return null
        val (digestLen, afterLen) = readVarint(multihash, afterFn) ?: return null
        val end = afterLen + digestLen.toInt()
        if (end > multihash.size || digestLen <= 0) return null
        return multihash.copyOfRange(afterLen, end)
    }

    private fun multihashCodeToAlgorithm(code: Long): String? = when (code) {
        0x11L -> "SHA-1"
        0x12L -> "SHA-256"
        0x13L -> "SHA-512"   // sha2-512 in some old specs
        0x14L -> "SHA-512"
        else  -> null
    }

    // -------------------------------------------------------------------------
    // Varint reader (protobuf / unsigned-LEB128 style)
    // -------------------------------------------------------------------------

    /**
     * Read an unsigned varint from [buf] at [offset].
     * Returns Pair(value, nextOffset) or null on error.
     */
    private fun readVarint(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < buf.size) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) return Pair(result, pos)
            if (shift >= 64) return null  // overflow guard
        }
        return null  // unterminated varint
    }

    // -------------------------------------------------------------------------
    // Base58 (Bitcoin alphabet) — used by CIDv0 ("Qm…") and CIDv1 'z' prefix
    // -------------------------------------------------------------------------

    private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun decodeBase58(input: String): ByteArray? {
        if (input.isEmpty()) return null
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (ch in input) {
            val digit = BASE58_ALPHABET.indexOf(ch)
            if (digit < 0) return null
            num = num.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        // Count leading '1' characters → leading zero bytes
        val leadingZeros = input.takeWhile { it == '1' }.length
        val decoded = num.toByteArray()
        // BigInteger may add a sign byte; strip it
        val stripped = if (decoded.isNotEmpty() && decoded[0] == 0.toByte())
            decoded.copyOfRange(1, decoded.size) else decoded
        return ByteArray(leadingZeros) + stripped
    }

    // -------------------------------------------------------------------------
    // Base32 (RFC 4648, no padding) — used by CIDv1 'b' prefix
    // -------------------------------------------------------------------------

    private val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    private fun decodeBase32(input: String): ByteArray? {
        val lower = input.lowercase().trimEnd('=')
        if (lower.isEmpty()) return null
        var bits = 0
        var bitsCount = 0
        val out = mutableListOf<Byte>()
        for (ch in lower) {
            val v = BASE32_ALPHABET.indexOf(ch)
            if (v < 0) return null
            bits = (bits shl 5) or v
            bitsCount += 5
            if (bitsCount >= 8) {
                bitsCount -= 8
                out.add(((bits shr bitsCount) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Base16 / hex — used by CIDv1 'f' prefix
    // -------------------------------------------------------------------------

    private fun decodeHex(input: String): ByteArray? {
        if (input.length % 2 != 0) return null
        return try {
            ByteArray(input.length / 2) { i ->
                input.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) { null }
    }
}
