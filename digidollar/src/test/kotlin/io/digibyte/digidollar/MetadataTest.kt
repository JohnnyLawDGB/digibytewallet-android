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
        val meta = MintMetadata.parse(mintFixtureScript)
        assertEquals(10_000, meta.ddCents)
        assertEquals(1_037_552, meta.unlockHeight)
        assertEquals(3, meta.lockTier)
        assertEquals(
            "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
            meta.ownerKeyHex,
        )
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
        val meta = MintMetadata.parse(tierZeroFixtureScript)
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
        val meta = TransferMetadata.parse(transferFixtureScript)
        assertEquals(listOf(3_000L, 7_000L), meta.amountsCents)
        assertEquals(transferFixtureScript, meta.build())
    }

    // Redemption metadata exists only when there is DigiDollar change; an
    // exact burn (fixtures/redeem-tx.json) carries no OP_RETURN at all.
    @Test
    fun `redemption change metadata round-trips and rejects non-positive change`() {
        val meta = RedemptionMetadata(ddChangeCents = 2_500)
        assertEquals(meta, RedemptionMetadata.parse(meta.build()))
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
            assertEquals(listOf(cents), TransferMetadata.parse(script).amountsCents)
        }
        assertEquals("6a0244440102017f", TransferMetadata(listOf(127)).build())
        assertEquals("6a024444010202" + "8000", TransferMetadata(listOf(128)).build())
    }

    @Test
    fun `parsers reject scripts of the wrong DigiDollar type`() {
        assertFailsWith<IllegalArgumentException> { MintMetadata.parse(transferFixtureScript) }
        assertFailsWith<IllegalArgumentException> { TransferMetadata.parse(mintFixtureScript) }
        assertFailsWith<IllegalArgumentException> { RedemptionMetadata.parse(mintFixtureScript) }
    }
}
