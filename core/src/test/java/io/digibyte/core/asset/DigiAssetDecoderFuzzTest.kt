package io.digibyte.core.asset

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Adversarial-input tests for [DigiAssetDecoder].
 *
 * The decoder is the wallet's primary trust boundary on incoming asset
 * transactions: SPV peers can deliver any bytes they want, the decoder is
 * what decides "this is a DigiAsset transfer" and what it carries. Every
 * crash-on-malformed-input is a remote DoS; every "decoder accepted bytes
 * the spec rejects" is a potential mis-attribution bug.
 *
 * Goals these tests enforce:
 *   1. The decoder never throws on any input — bad input returns null.
 *   2. Buffer over-reads are impossible: claimed lengths > actual buffer
 *      size, truncated headers, oversized varints all decline cleanly.
 *   3. Random fuzz doesn't produce a non-null header that would later
 *      crash downstream code (totalQuantity / divisibility / aggregation
 *      stay in their declared ranges when set).
 */
class DigiAssetDecoderFuzzTest {

    private val decoder = DigiAssetDecoder()

    // -------------------------------------------------------------------------
    // Trivial degenerate cases
    // -------------------------------------------------------------------------

    @Test
    fun `empty bytes return null`() {
        assertNull(decoder.decode(ByteArray(0)))
    }

    @Test
    fun `single OP_RETURN byte returns null`() {
        // Just the opcode, no length, no payload.
        assertNull(decoder.decode(byteArrayOf(0x6A)))
    }

    @Test
    fun `non-OP_RETURN script returns null`() {
        // Standard P2WPKH-looking script: OP_0 0x14 <20 bytes>
        val script = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xAB.toByte() }
        assertNull(decoder.decode(script))
    }

    // -------------------------------------------------------------------------
    // Length-mismatch attacks
    // -------------------------------------------------------------------------

    @Test
    fun `claimed push length larger than buffer returns null`() {
        // OP_RETURN says push 30 bytes, only 4 follow.
        val script = byteArrayOf(0x6A, 30) + ByteArray(4) { 0x44 }
        assertNull(decoder.decode(script))
    }

    @Test
    fun `OP_PUSHDATA1 with declared length over buffer returns null`() {
        // OP_RETURN OP_PUSHDATA1 0x80 (128 bytes) — but only 6 actually follow.
        val script = byteArrayOf(0x6A, 0x4C, 0x80.toByte()) + ByteArray(6) { 0x44 }
        assertNull(decoder.decode(script))
    }

    @Test
    fun `truncated DA payload after magic returns null`() {
        // OP_RETURN, push len = 2, payload = 0x44 0x41 — magic only, no version+opcode.
        val script = byteArrayOf(0x6A, 0x02, 0x44, 0x41)
        assertNull(decoder.decode(script))
    }

    @Test
    fun `OP_PUSHDATA2 and OP_PUSHDATA4 are refused`() {
        // DA spec uses single-byte push or PUSHDATA1 only. Bigger pushdata
        // codes shouldn't be honored — refuse rather than leaking
        // exploitable parser surface.
        val pd2 = byteArrayOf(0x6A, 0x4D, 0x02, 0x00, 0x44, 0x41, 0x03, 0x15)
        val pd4 = byteArrayOf(0x6A, 0x4E, 0x02, 0x00, 0x00, 0x00, 0x44, 0x41, 0x03, 0x15)
        assertNull(decoder.decode(pd2))
        assertNull(decoder.decode(pd4))
    }

    // -------------------------------------------------------------------------
    // Magic / version / opcode sanity
    // -------------------------------------------------------------------------

    @Test
    fun `wrong magic prefix returns null`() {
        // 0x44 0x42 ("DB") instead of 0x44 0x41 ("DA"). Should be rejected.
        val payload = byteArrayOf(0x44, 0x42, 0x03, 0x15) + ByteArray(8) { 0x00 }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertNull(decoder.decode(script))
    }

    @Test
    fun `version zero is refused`() {
        val payload = byteArrayOf(0x44, 0x41, 0x00, 0x15) + ByteArray(8) { 0x00 }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertNull(decoder.decode(script))
    }

    @Test
    fun `unknown opcode returns null`() {
        // 0xFF isn't a known DA op (not 0x01-0x05, 0x15, 0x25).
        val payload = byteArrayOf(0x44, 0x41, 0x03, 0xFF.toByte()) + ByteArray(8) { 0x00 }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertNull(decoder.decode(script))
    }

    @Test
    fun `issuance opcode missing metadata hash bytes returns null`() {
        // Opcode 1 needs a 32-byte metadata hash; we provide 4 bytes.
        // Decoder MUST detect underflow rather than reading past the end.
        val payload = byteArrayOf(0x44, 0x41, 0x03, 0x01) + ByteArray(4) { 0xAA.toByte() }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertNull(decoder.decode(script))
    }

    @Test
    fun `v1 v2 issuance with truncated SHA1 padding returns null`() {
        // Old opcodes 1-2 carry a 20-byte SHA1 region that v3 dropped.
        // Truncate it — decoder must refuse, not read uninitialized bytes.
        val payload = byteArrayOf(0x44, 0x41, 0x02, 0x01) + ByteArray(10) { 0xAA.toByte() }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertNull(decoder.decode(script))
    }

    // -------------------------------------------------------------------------
    // Random fuzz — nothing should crash, anything that decodes must be sane
    // -------------------------------------------------------------------------

    @Test
    fun `random scripts do not crash and produce sane output`() {
        // Deterministic seed so failures reproduce.
        val rng = Random(0xDA1A_F1A7L)
        repeat(2_000) {
            val len = rng.nextInt(0, 200)
            val script = ByteArray(len) { rng.nextInt(0, 256).toByte() }

            val header = runCatching { decoder.decode(script) }
                .onFailure { t ->
                    throw AssertionError(
                        "decoder threw on random input (len=$len): ${t.javaClass.simpleName} ${t.message}",
                        t,
                    )
                }
                .getOrNull() ?: return@repeat

            // If the decoder returned a header, it MUST satisfy basic
            // invariants — these are what downstream code (M3 walk,
            // UI render) relies on without re-checking.
            assertTrue("version in declared range", header.version in 1..255)
            assertTrue("divisibility 0..7", header.divisibility in 0..7)
            // totalQuantity is nullable (null on transfer/burn). When set
            // for issuance, must be non-negative (Long can be > 0 here).
            header.totalQuantity?.let {
                assertTrue("totalQuantity non-negative: $it", it >= 0L)
            }
            // Aggregation enum value is one of the three known values
            // (the type system enforces this, but assertNotNull catches
            // any future enum-from-int hot-paths.)
            assertNotNull(header.aggregation)
        }
    }

    @Test
    fun `random OP_RETURN-shaped scripts with valid magic do not crash`() {
        // Constrain to (OP_RETURN | len | DA | random) to drive the parser
        // deeper rather than rejecting at the magic check. Tests the BitIO
        // / fixed-precision varint paths against junk payloads.
        val rng = Random(0xFA77E55EL)
        repeat(2_000) {
            val payloadLen = rng.nextInt(2, 75) // 2 magic bytes + extra
            val payload = ByteArray(payloadLen).also {
                it[0] = 0x44; it[1] = 0x41
                for (i in 2 until payloadLen) {
                    it[i] = rng.nextInt(0, 256).toByte()
                }
            }
            val script = byteArrayOf(0x6A, payloadLen.toByte()) + payload

            runCatching { decoder.decode(script) }
                .onFailure { t ->
                    throw AssertionError(
                        "decoder threw on DA-magic random payload (len=$payloadLen): " +
                            "${t.javaClass.simpleName} ${t.message}",
                        t,
                    )
                }
        }
    }

    // -------------------------------------------------------------------------
    // containsAsset is a fast-path probe; same robustness contract.
    // -------------------------------------------------------------------------

    @Test
    fun `containsAsset never crashes on adversarial input`() {
        val rng = Random(0xC0FFEEL)
        repeat(1_000) {
            val script = ByteArray(rng.nextInt(0, 80)) { rng.nextInt(0, 256).toByte() }
            runCatching { decoder.containsAsset(script) }
                .onFailure { t ->
                    throw AssertionError(
                        "containsAsset threw on random input (len=${script.size}): " +
                            "${t.javaClass.simpleName} ${t.message}",
                        t,
                    )
                }
        }
    }
}
