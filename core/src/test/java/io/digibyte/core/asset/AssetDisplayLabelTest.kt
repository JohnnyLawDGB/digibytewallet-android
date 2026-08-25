package io.digibyte.core.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An asset with no published metadata is not a broken asset.
 *
 * On device, `La4WAqZfAwtxb…` rendered as a bare truncated base58 id with NO subtitle at all —
 * because the "metadata offline" hint was gated on the metadata row being absent, and this
 * asset's row exists (carrying on-chain supply and divisibility) with only a null name. So the
 * one case the wallet understood perfectly looked like the one it had failed at.
 */
class AssetDisplayLabelTest {

    @Test fun `a published name wins`() {
        val l = AssetDisplayLabel.of("La8knZ…", hasMetadataRow = true, name = "DigiScope Test v3", symbol = "DS")
        assertEquals("DigiScope Test v3", l.title)
        assertEquals("DS", l.subtitle)
        assertEquals(AssetDisplayLabel.Kind.NAMED, l.kind)
    }

    /** The real case: row exists, no name, nothing to fetch. */
    @Test fun `a row with no name is an artifact, not a failure`() {
        val l = AssetDisplayLabel.of("La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo", hasMetadataRow = true, name = null)

        assertEquals("Artifact La4WAq", l.title)
        assertEquals("no name published", l.subtitle)
        assertEquals(AssetDisplayLabel.Kind.ARTIFACT, l.kind)
    }

    /** Blank is the same as absent — a published empty string is still no name. */
    @Test fun `a blank name is treated as no name`() {
        assertEquals(AssetDisplayLabel.Kind.ARTIFACT,
            AssetDisplayLabel.of("La4WAq…", hasMetadataRow = true, name = "   ").kind)
    }

    /** No row yet is genuinely different — it may still resolve, so don't call it an artifact. */
    @Test fun `no metadata row is pending, not an artifact`() {
        val l = AssetDisplayLabel.of("La4WAqZfAwtxb", hasMetadataRow = false, name = null)

        assertEquals("metadata offline", l.subtitle)
        assertEquals(AssetDisplayLabel.Kind.PENDING, l.kind)
    }

    @Test fun `an unresolved placeholder keeps its own treatment`() {
        val l = AssetDisplayLabel.of("unresolved:b12f141525cd", hasMetadataRow = false, name = null)

        assertEquals("DigiAsset b12f14", l.title)
        assertEquals(AssetDisplayLabel.Kind.UNRESOLVED, l.kind)
    }
}
