package app.aroundtheblock.wallet.core.ipfs

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
 * Multihash function codes verified:
 *  - 0x12  sha2-256
 *  - 0x13  sha2-512
 *
 * Every other code is unverifiable: [extractHashAlgorithm] returns null and [verify] returns
 * false, the same answer as for a mismatch. That includes 0x11 (sha1), whose matches prove
 * nothing about which content was addressed, and 0x14, which is sha3-512 — not SHA-512.
 */
class CidVerifier {

    companion object {
        /**
         * No value longer than this is a CID. The longest form [verify] can check, sha2-512 in
         * base16, is 137 characters; the rest is headroom for a two-byte codec.
         */
        const val MAX_CID_CHARS = 160

        /** Multicodec `raw`: the CID's hash is the hash of the file's own bytes, all of them. */
        const val CODEC_RAW = 0x55L

        /** Multicodec `dag-pb`: the CID's hash is the hash of a UnixFS node (for a file of more
         *  than one block, a list of links), never of the file's bytes. */
        const val CODEC_DAG_PB = 0x70L
    }

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

    /**
     * Whether [cid] has the shape of a CID at all: a CIDv0 (`Qm…`) or a CIDv1 in a multibase this
     * class reads, decoding to a multihash that carries a digest. Says nothing about whether the
     * hash function is one [verify] can check. Used to refuse a value that is not a CID (the text
     * "null", a word, a truncated id) before any request is made for it.
     */
    fun isWellFormed(cid: String): Boolean {
        val multihash = cidToMultihash(cid) ?: return false
        return parseMultihashDigest(multihash) != null
    }

    /**
     * The multicodec of what [cid] addresses, read from the CID itself: [CODEC_DAG_PB] for every
     * CIDv0 (the form has no codec field and is dag-pb by definition), the codec field of a CIDv1
     * in whichever multibase and with whichever hash it is written, null when [cid] is not a CID.
     *
     * This, and not how the text begins, says what a verified answer is. `bafybe…` is only the
     * base32 spelling of "CIDv1, dag-pb, sha2-256": the same codec in base58 (`zdj7W…`), in base16
     * or with another hash begins differently, and is just as much a wrapper and not the file.
     */
    fun codec(cid: String): Long? = parse(cid)?.takeIf { parseMultihashDigest(it.multihash) != null }?.codec

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

    private fun cidToMultihash(cid: String): ByteArray? = parse(cid)?.multihash

    /** What a CID decodes to: the multicodec of what it addresses, and the multihash. */
    private class Parsed(val codec: Long, val multihash: ByteArray)

    private fun parse(cid: String): Parsed? {
        if (cid.isBlank()) return null
        // Bounded before any decoding: the decoders' work grows with the input, and no CID is long.
        if (cid.length > MAX_CID_CHARS) return null

        // CIDv0: starts with "Qm", always Base58-encoded multihash directly. It has no codec field:
        // a CIDv0 is dag-pb by definition.
        if (cid.startsWith("Qm")) {
            return decodeBase58(cid)?.let { Parsed(CODEC_DAG_PB, it) }
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
        val (version, afterVersion) = readVarint(raw, 0) ?: return null
        if (version != 1L) return null
        val (codec, afterCodec) = readVarint(raw, afterVersion) ?: return null
        // Remainder is the multihash
        return Parsed(codec, raw.copyOfRange(afterCodec, raw.size))
    }

    // -------------------------------------------------------------------------
    // Multihash helpers
    // -------------------------------------------------------------------------

    /** Parse digest bytes from a multihash (fn_code varint + digest_len varint + digest). */
    private fun parseMultihashDigest(multihash: ByteArray): ByteArray? {
        val (_, afterFn) = readVarint(multihash, 0) ?: return null
        val (digestLen, afterLen) = readVarint(multihash, afterFn) ?: return null
        // Compared as Long, before any narrowing: a declared length past Int range would wrap.
        if (digestLen <= 0 || digestLen > (multihash.size - afterLen).toLong()) return null
        val end = afterLen + digestLen.toInt()
        return multihash.copyOfRange(afterLen, end)
    }

    private fun multihashCodeToAlgorithm(code: Long): String? = when (code) {
        0x12L -> "SHA-256"   // sha2-256
        0x13L -> "SHA-512"   // sha2-512
        else  -> null        // unverifiable, 0x11 (sha1) and 0x14 (sha3-512) included
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
