package app.aroundtheblock.wallet.core.ipfs

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for [CidVerifier].
 *
 * All CIDs used here are derived deterministically from known content so the tests
 * run entirely offline — no network required.
 */
class CidVerifierTest {

    private lateinit var verifier: CidVerifier

    // Known content and its CIDv0
    // CIDv0 = Base58(multihash(sha2-256, sha256(content)))
    // We derive this programmatically so the test is self-consistent.
    private val knownContent = "Hello, DigiAssets!".toByteArray(Charsets.UTF_8)
    private lateinit var knownCidV0: String

    @Before
    fun setUp() {
        verifier = CidVerifier()
        // Build a real CIDv0 for knownContent so tests are deterministic
        knownCidV0 = buildCidV0(knownContent)
    }

    // -------------------------------------------------------------------------
    // CIDv0 — "Qm…" Base58-encoded SHA-256 multihash
    // -------------------------------------------------------------------------

    @Test
    fun `verify CIDv0 with matching SHA-256 returns true`() {
        assertTrue(verifier.verify(knownCidV0, knownContent))
    }

    @Test
    fun `verify CIDv0 with mismatched content returns false`() {
        val differentContent = "Tampered data!".toByteArray(Charsets.UTF_8)
        assertFalse(verifier.verify(knownCidV0, differentContent))
    }

    @Test
    fun `verify returns false for invalid CID`() {
        assertFalse(verifier.verify("not-a-real-cid", knownContent))
        assertFalse(verifier.verify("", knownContent))
        assertFalse(verifier.verify("Qm!!!invalid###", knownContent))
    }

    @Test
    fun `verify returns false for truncated CIDv0`() {
        // A "Qm" CID truncated to a few characters cannot decode to a valid multihash
        assertFalse(verifier.verify("QmAB", knownContent))
    }

    @Test
    fun `extractDigest from CIDv0 returns 32-byte SHA-256 digest`() {
        val digest = verifier.extractDigest(knownCidV0)
        assertNotNull(digest)
        assertEquals(32, digest!!.size)
        // Digest must match SHA-256(knownContent)
        val expected = MessageDigest.getInstance("SHA-256").digest(knownContent)
        assertArrayEquals(expected, digest)
    }

    @Test
    fun `extractHashAlgorithm from CIDv0 returns SHA-256`() {
        assertEquals("SHA-256", verifier.extractHashAlgorithm(knownCidV0))
    }

    @Test
    fun `extractDigest returns null for garbage input`() {
        assertNull(verifier.extractDigest("garbage!!!"))
        assertNull(verifier.extractDigest(""))
    }

    // -------------------------------------------------------------------------
    // CIDv1 — base32 multibase ('b' prefix)
    // -------------------------------------------------------------------------

    @Test
    fun `verify CIDv1 base32 with matching content returns true`() {
        val cidV1 = buildCidV1Base32(knownContent)
        assertTrue(verifier.verify(cidV1, knownContent))
    }

    @Test
    fun `verify CIDv1 base32 with mismatched content returns false`() {
        val cidV1 = buildCidV1Base32(knownContent)
        val differentContent = "Wrong content".toByteArray(Charsets.UTF_8)
        assertFalse(verifier.verify(cidV1, differentContent))
    }

    // -------------------------------------------------------------------------
    // Hash functions: a CID verifies only under a function the verifier computes as named
    // -------------------------------------------------------------------------

    @Test
    fun `a SHA-1 multihash is unverifiable even when its digest matches`() {
        // 0x11 = sha1. A match under a broken hash says nothing about which content it was.
        val cid = buildCidV1Base32(knownContent, code = 0x11, algorithm = "SHA-1")
        assertFalse("a SHA-1 CID whose digest matches verified", verifier.verify(cid, knownContent))
        assertNull(verifier.extractHashAlgorithm(cid))
    }

    @Test
    fun `code 0x14 is sha3-512, so a SHA-512 digest filed under it does not verify`() {
        // multicodec: 0x13 = sha2-512, 0x14 = sha3-512. Hashing 0x14 with SHA-512 made a CID that
        // names one function verify against another.
        val cid = buildCidV1Base32(knownContent, code = 0x14, algorithm = "SHA-512")
        assertFalse("a CID naming sha3-512 verified against a SHA-512 digest", verifier.verify(cid, knownContent))
        assertNull(verifier.extractHashAlgorithm(cid))
    }

    @Test
    fun `a sha3-512 CID is unverifiable, like every code the verifier does not compute`() {
        val sha3 = buildCidV1Base32(knownContent, code = 0x14, algorithm = "SHA3-512")
        assertFalse(verifier.verify(sha3, knownContent))
        // 0x16 = sha3-256: an unknown code, the path 0x11 and 0x14 now take
        val unknown = buildCidV1Base32(knownContent, code = 0x16, algorithm = "SHA3-256")
        assertNull(verifier.extractHashAlgorithm(unknown))
        assertFalse(verifier.verify(unknown, knownContent))
    }

    @Test
    fun `sha2-512 (0x13) verifies`() {
        val cid = buildCidV1Base32(knownContent, code = 0x13, algorithm = "SHA-512")
        assertEquals("SHA-512", verifier.extractHashAlgorithm(cid))
        assertTrue(verifier.verify(cid, knownContent))
        assertFalse(verifier.verify(cid, "Wrong content".toByteArray(Charsets.UTF_8)))
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `verify empty content against CID of empty bytes`() {
        val emptyCid = buildCidV0(ByteArray(0))
        assertTrue(verifier.verify(emptyCid, ByteArray(0)))
    }

    @Test
    fun `verify large content`() {
        val large = ByteArray(1024 * 1024) { it.toByte() }
        val cid = buildCidV0(large)
        assertTrue(verifier.verify(cid, large))
    }

    // -------------------------------------------------------------------------
    // Helpers — build real CIDs from content for offline testing
    // -------------------------------------------------------------------------

    /**
     * Build a CIDv0: Base58( 0x12 || 0x20 || sha256(content) )
     * 0x12 = sha2-256 multihash code, 0x20 = 32 bytes digest length.
     */
    private fun buildCidV0(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val multihash = byteArrayOf(0x12.toByte(), 0x20.toByte()) + digest
        return encodeBase58(multihash)
    }

    /**
     * Build a CIDv1 with base32 multibase:
     *   'b' + base32( version(1) + codec(0x55 = raw) + multihash )
     * The multihash names [code] and carries [algorithm]'s digest of [content] (sha2-256 by default).
     */
    private fun buildCidV1Base32(content: ByteArray, code: Int = 0x12, algorithm: String = "SHA-256"): String {
        val digest = MessageDigest.getInstance(algorithm).digest(content)
        // multihash = function code + digest length + digest (each code and length here < 0x80: one varint byte)
        val multihash = byteArrayOf(code.toByte(), digest.size.toByte()) + digest
        // version=1 (varint 0x01), codec=raw (0x55 as single-byte varint)
        val raw = byteArrayOf(0x01, 0x55) + multihash
        return "b" + encodeBase32(raw)
    }

    // Minimal Base58 encoder (Bitcoin alphabet) for test helpers
    private val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun encodeBase58(input: ByteArray): String {
        var num = java.math.BigInteger(1, input)
        val base = java.math.BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (num > java.math.BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(B58[r.toInt()])
            num = q
        }
        // Leading zero bytes → leading '1' characters
        for (b in input) {
            if (b != 0.toByte()) break
            sb.append('1')
        }
        return sb.reverse().toString()
    }

    // Minimal Base32 encoder (RFC 4648 lowercase, no padding)
    private val B32 = "abcdefghijklmnopqrstuvwxyz234567"

    private fun encodeBase32(input: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var bitsCount = 0
        for (b in input) {
            bits = (bits shl 8) or (b.toInt() and 0xFF)
            bitsCount += 8
            while (bitsCount >= 5) {
                bitsCount -= 5
                sb.append(B32[(bits shr bitsCount) and 0x1F])
            }
        }
        if (bitsCount > 0) sb.append(B32[(bits shl (5 - bitsCount)) and 0x1F])
        return sb.toString()
    }
}
