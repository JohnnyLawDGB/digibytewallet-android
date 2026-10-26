package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.AssetOperation
import org.junit.Assert.assertEquals
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
 *   1. The decoder never throws on any input. Input that is not a DigiAsset
 *      carrier returns null; a carrier tagged "DA" that cannot be read
 *      returns an UNCLASSIFIABLE header (fail closed: the hold rule then
 *      keeps every owned output of the transaction out of DGB spends).
 *   2. Buffer over-reads are impossible: claimed lengths > actual buffer
 *      size, truncated headers, oversized varints all decline cleanly.
 *   3. Random fuzz doesn't produce a non-null header that would later
 *      crash downstream code (totalQuantity / divisibility / aggregation
 *      stay in their declared ranges when set).
 */
class DigiAssetDecoderFuzzTest {

    private val decoder = DigiAssetDecoder()

    /** A tagged carrier the decoder cannot read: an UNCLASSIFIABLE header with nothing in it. */
    private fun assertUnclassifiable(header: DecodedAssetHeader?) {
        assertNotNull("a tagged carrier must not read as 'not an asset'", header)
        assertEquals(AssetOperation.UNCLASSIFIABLE, header!!.operation)
        assertTrue("an unclassifiable carrier carries no instructions", header.transferInstructions.isEmpty())
    }

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
    fun `truncated DA payload after magic is unclassifiable`() {
        // OP_RETURN, push len = 2, payload = 0x44 0x41 — magic only, no version+opcode.
        val script = byteArrayOf(0x6A, 0x02, 0x44, 0x41)
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `a tagged push that runs past the script is unclassifiable`() {
        // OP_RETURN says push 30 bytes; the "DA" tag and 4 more follow.
        val script = byteArrayOf(0x6A, 30, 0x44, 0x41, 0x03, 0x15, 0x00, 0x0A)
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `OP_PUSHDATA2 and OP_PUSHDATA4 carriers are unclassifiable, never plain`() {
        // DA payloads use a single-byte push or PUSHDATA1. A tagged payload behind a bigger
        // pushdata code is not parsed — but it is not "no asset" either: which outputs the
        // protocol credits cannot be known, so the transaction's outputs are held.
        val pd2 = byteArrayOf(0x6A, 0x4D, 0x02, 0x00, 0x44, 0x41, 0x03, 0x15)
        val pd4 = byteArrayOf(0x6A, 0x4E, 0x02, 0x00, 0x00, 0x00, 0x44, 0x41, 0x03, 0x15)
        assertUnclassifiable(decoder.decode(pd2))
        assertUnclassifiable(decoder.decode(pd4))
    }

    @Test
    fun `OP_PUSHDATA2 or OP_PUSHDATA4 without the DA tag is not a carrier`() {
        val pd2 = byteArrayOf(0x6A, 0x4D, 0x02, 0x00, 0x44, 0x42, 0x03, 0x15)
        val pd4 = byteArrayOf(0x6A, 0x4E, 0x02, 0x00, 0x00, 0x00, 0x44, 0x42)
        assertNull(decoder.decode(pd2))
        assertNull(decoder.decode(pd4))
        assertNull("a declared length too short to hold a tag", decoder.decode(byteArrayOf(0x6A, 0x4D, 0x01, 0x00, 0x44, 0x41)))
        assertNull("a length field the script ends inside", decoder.decode(byteArrayOf(0x6A, 0x4E, 0x02, 0x00)))
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
    fun `version zero is unclassifiable`() {
        val payload = byteArrayOf(0x44, 0x41, 0x00, 0x15) + ByteArray(8) { 0x00 }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `unknown opcode is unclassifiable`() {
        // 0xFF isn't a known DA op (not 0x01-0x05, 0x15, 0x25).
        val payload = byteArrayOf(0x44, 0x41, 0x03, 0xFF.toByte()) + ByteArray(8) { 0x00 }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `issuance opcode missing metadata hash bytes is unclassifiable`() {
        // Opcode 1 needs a 32-byte metadata hash; we provide 4 bytes.
        // Decoder MUST detect underflow rather than reading past the end.
        val payload = byteArrayOf(0x44, 0x41, 0x03, 0x01) + ByteArray(4) { 0xAA.toByte() }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `v1 v2 issuance with truncated SHA1 padding is unclassifiable`() {
        // Old opcodes 1-2 carry a 20-byte SHA1 region that v3 dropped.
        // Truncate it — decoder must refuse, not read uninitialized bytes.
        val payload = byteArrayOf(0x44, 0x41, 0x02, 0x01) + ByteArray(10) { 0xAA.toByte() }
        val script = byteArrayOf(0x6A, payload.size.toByte()) + payload
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `an issuance amount the payload ends inside is unclassifiable`() {
        // Opcode 5 (no metadata); the amount header selects the 7-byte form, 1 byte follows.
        val script = byteArrayOf(0x6A, 0x05, 0x44, 0x41, 0x02, 0x05, 0xE0.toByte())
        assertUnclassifiable(decoder.decode(script))
    }

    @Test
    fun `a transfer instruction the payload ends inside is unclassifiable`() {
        // A flags byte with no amount after it.
        assertUnclassifiable(decoder.decode(byteArrayOf(0x6A, 0x05, 0x44, 0x41, 0x02, 0x15, 0x03)))
        // A 2-byte amount with one byte left.
        assertUnclassifiable(decoder.decode(byteArrayOf(0x6A, 0x06, 0x44, 0x41, 0x02, 0x15, 0x00, 0x20)))
        // A range instruction with no second index byte.
        assertUnclassifiable(decoder.decode(byteArrayOf(0x6A, 0x05, 0x44, 0x41, 0x02, 0x15, 0x40)))
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
            // UI render) relies on without re-checking. An unclassifiable
            // carrier carries nothing at all.
            if (header.operation == AssetOperation.UNCLASSIFIABLE) {
                assertTrue("unclassifiable carries no instructions", header.transferInstructions.isEmpty())
                assertNull("unclassifiable carries no quantity", header.totalQuantity)
                return@repeat
            }
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
