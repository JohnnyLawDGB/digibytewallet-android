package io.digibyte.core.ipfs

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
     */
    private fun buildCidV1Base32(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val multihash = byteArrayOf(0x12.toByte(), 0x20.toByte()) + digest
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
