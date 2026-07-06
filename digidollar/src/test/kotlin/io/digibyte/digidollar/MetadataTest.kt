package io.digibyte.digidollar

import kotlin.test.assertEquals
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
}
