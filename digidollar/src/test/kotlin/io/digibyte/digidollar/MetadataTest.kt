package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class MetadataTest {

    // OP_RETURN scriptPubKey of fixtures/mint-tx.json vout 2 (Core-built).
    private val mintFixtureScript =
        "6a024444010102102703f0d40f010320" +
            "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034"

    @Test
    fun `parses the Core mint fixture OP_RETURN`() {
        val meta = CrossCheckParse.mint(mintFixtureScript)
        assertEquals(10_000, meta.ddCents)
        assertEquals(1_037_552, meta.unlockHeight)
        assertEquals(3, meta.lockTier)
        assertEquals(
            "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
            meta.ownerKeyHex,
        )
    }

    // Production parse (issue #10 Positions): collateral positions are
    // recovered from the wallet's own Mint transactions, so the Mint
    // OP_RETURN — OUR build format — must parse in main source too.
    @Test
    fun `MintMetadata parse recovers the Core mint fixture fields`() {
        val meta = MintMetadata.parse(mintFixtureScript)
        assertEquals(10_000, meta.ddCents)
        assertEquals(1_037_552, meta.unlockHeight)
        assertEquals(3, meta.lockTier)
        assertEquals(
            "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
            meta.ownerKeyHex,
        )
        assertEquals(mintFixtureScript, meta.build())
    }

    @Test
    fun `builds the Core mint fixture OP_RETURN byte-for-byte`() {
        val script = MintMetadata(
            ddCents = 10_000,
            unlockHeight = 1_037_552,
            lockTier = 3,
            ownerKeyHex = "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
        ).build()
        assertEquals(mintFixtureScript, script)
    }

    // fixtures/redeem-mint-tx.json vout 2: a tier-0 (1 hour) mint — the tier
    // index is CScriptNum zero, encoded as an EMPTY push (OP_0).
    private val tierZeroFixtureScript =
        "6a024444010102102702280400209c42c105e9be2f6712b0" +
            "04953174a956d9bd7674fd26ccd5d17f5c50e88bd3ef"

    @Test
    fun `tier zero round-trips through the empty-push encoding`() {
        val meta = CrossCheckParse.mint(tierZeroFixtureScript)
        assertEquals(10_000, meta.ddCents)
        assertEquals(1_064, meta.unlockHeight)
        assertEquals(0, meta.lockTier)
        assertEquals(tierZeroFixtureScript, meta.build())
    }

    // fixtures/transfer-tx.json vout 3: $30 to the recipient, $70 DigiDollar
    // change — one CScriptNum per zero-value output, positional.
    private val transferFixtureScript = "6a024444010202b80b02581b"

    @Test
    fun `transfer fixture OP_RETURN round-trips with positional amounts`() {
        val meta = CrossCheckParse.transfer(transferFixtureScript)
        assertEquals(listOf(3_000L, 7_000L), meta.amountsCents)
        assertEquals(transferFixtureScript, meta.build())
    }

    // Redemption metadata exists only when there is DigiDollar change; an
    // exact burn (fixtures/redeem-tx.json) carries no OP_RETURN at all.
    @Test
    fun `redemption change metadata round-trips and rejects non-positive change`() {
        val meta = RedemptionMetadata(ddChangeCents = 2_500)
        assertEquals(meta, CrossCheckParse.redemption(meta.build()))
        assertFailsWith<IllegalArgumentException> {
            RedemptionMetadata(ddChangeCents = 0).build()
        }
    }

    // CScriptNum minimal encoding: a set top bit gets a 0x00 sign-padding
    // byte — 127 is one byte (0x7f), 128 is two (0x80 0x00).
    @Test
    fun `amounts round-trip across the sign-padding boundaries`() {
        for (cents in listOf(1L, 127L, 128L, 255L, 256L, 32_767L, 32_768L, 8_388_607L, 8_388_608L)) {
            val script = TransferMetadata(listOf(cents)).build()
            assertEquals(listOf(cents), CrossCheckParse.transfer(script).amountsCents)
        }
        assertEquals("6a0244440102017f", TransferMetadata(listOf(127)).build())
        assertEquals("6a024444010202" + "8000", TransferMetadata(listOf(128)).build())
    }

    @Test
    fun `parsers reject scripts of the wrong DigiDollar type`() {
        assertFailsWith<IllegalArgumentException> { CrossCheckParse.mint(transferFixtureScript) }
        assertFailsWith<IllegalArgumentException> { CrossCheckParse.transfer(mintFixtureScript) }
        assertFailsWith<IllegalArgumentException> { CrossCheckParse.redemption(mintFixtureScript) }
    }

    // CScriptNum decode conformance (Core rules): 8-byte length bound, sign
    // bit in the top bit of the last byte, minimal encoding required.
    @Test
    fun `decode enforces CScriptNum length, sign, and minimality rules`() {
        assertEquals(-1L, ScriptNum.decode(byteArrayOf(0x81.toByte())))
        assertEquals(-129L, ScriptNum.decode(byteArrayOf(0x81.toByte(), 0x80.toByte())))
        // 9 bytes would silently overflow Long — must throw instead
        assertFailsWith<IllegalArgumentException> { ScriptNum.decode(ByteArray(9) { 1 }) }
        // trailing 0x00 without a preceding sign-bit byte is non-minimal
        assertFailsWith<IllegalArgumentException> { ScriptNum.decode(byteArrayOf(0x01, 0x00)) }
        // zero is the empty push, never a literal 0x00 byte
        assertFailsWith<IllegalArgumentException> { ScriptNum.decode(byteArrayOf(0x00)) }
        // 0x80 0x00 IS minimal (+128 needs the sign-padding byte)
        assertEquals(128L, ScriptNum.decode(byteArrayOf(0x80.toByte(), 0x00)))
    }

    @Test
    fun `mint metadata rejects out-of-range fields at construction`() {
        val key = "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034"
        assertFailsWith<IllegalArgumentException> { MintMetadata(0, 1_037_552, 3, key) }
        assertFailsWith<IllegalArgumentException> { MintMetadata(10_000, 0, 3, key) }
        assertFailsWith<IllegalArgumentException> { MintMetadata(10_000, 1_037_552, 10, key) }
        assertFailsWith<IllegalArgumentException> { MintMetadata(10_000, 1_037_552, -1, key) }
    }
}
