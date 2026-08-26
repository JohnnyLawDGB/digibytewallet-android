package io.digibyte.core.asset

import io.digibyte.core.model.AssetMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detects the signature of a copied asset: **one metadata CID, more than one assetId**.
 *
 * DORRO #5 (`assets.digistamp.co/listing/cmrxzrkhd02avdo7s7ymb93ue`, confirmed a forgery) is the
 * shape this exists for — a new issuance pointing at the original's IPFS CID. Because the CID is
 * reused, every metadata-derived field is byte-identical to the original: same name, same image,
 * same description. `CidVerifier` passes too, since the content really does hash to that CID.
 * Content verification proves integrity, never authorship.
 *
 * So nothing *inside* the document can separate a copy from its original. Two things outside it
 * can. The chain-proven issuer is one (see [io.digibyte.core.ipfs.AssetMetadataService]); the
 * reuse itself is the other, and this is that one.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. It does not decide which asset is genuine, and it must never
 * be worded as though it had. Ordering by issuance height would be a guess — the earliest issuance
 * this wallet happens to hold is not necessarily the first that exists. It reports a fact ("these
 * assetIds publish identical metadata") and leaves the judgement to the person, next to the
 * verified issuer that lets them make it.
 *
 * SCOPE, stated plainly because it is easy to over-trust: the wallet only sees assets it holds. A
 * holder of only the copy learns nothing here. Whole-index detection needs the provider, which
 * currently exposes no CID-collision lookup.
 */
class AssetDuplicateDetectorTest {

    private val originalCid = "QmX4J9pzWpL1DUAGHwPwH2R7AeacmPqBZcdDGy8xWVfkYu"
    private val forgedAssetId = "La2rJL6S35GjytTaNyZVGxmA1mSVfzUhP7PDex"
    private val originalAssetId = "La3AHjYVgrNLRrzYhW6cFcBT7PjVvZKXWcZRqZ"

    private fun meta(assetId: String, cid: String?, name: String? = "DORRO #5") = AssetMetadata(
        assetId = assetId,
        name = name,
        symbol = null,
        description = null,
        decimals = 0,
        totalSupply = 1,
        issuerAddress = null,
        imageUrl = null,
        metadataCid = cid,
    )

    // ---- the case this was built for --------------------------------------------------------

    @Test fun `two assetIds sharing one cid are reported to each other`() {
        val held = listOf(meta(originalAssetId, originalCid), meta(forgedAssetId, originalCid))

        assertEquals(
            listOf(forgedAssetId),
            AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held),
        )
        assertEquals(
            listOf(originalAssetId),
            AssetDuplicateDetector.assetsSharingMetadata(forgedAssetId, held),
        )
    }

    @Test fun `three copies each see the other two`() {
        val third = "La9zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        val held = listOf(
            meta(originalAssetId, originalCid),
            meta(forgedAssetId, originalCid),
            meta(third, originalCid),
        )

        val others = AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held)
        assertEquals(2, others.size)
        assertTrue(others.containsAll(listOf(forgedAssetId, third)))
    }

    // ---- what must NOT be reported ----------------------------------------------------------

    @Test fun `an asset never reports itself`() {
        val held = listOf(meta(originalAssetId, originalCid))
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held))
    }

    @Test fun `different cids are not duplicates`() {
        val held = listOf(
            meta(originalAssetId, originalCid),
            meta(forgedAssetId, "QmDIFFERENTdifferentDIFFERENTdifferentDIFFERENT"),
        )
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held))
    }

    /**
     * The trap. Assets with no CID are the *common* case — anything whose metadata never
     * resolved. Treating null as a matchable value would group every unresolved asset in the
     * wallet into one giant "duplicate" cluster and flag them all, which is both wrong and the
     * fastest way to teach someone to ignore the warning.
     */
    @Test fun `assets with no cid are never duplicates of each other`() {
        val held = listOf(meta(originalAssetId, null), meta(forgedAssetId, null))
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held))
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(forgedAssetId, held))
    }

    @Test fun `a blank cid is treated as no cid`() {
        val held = listOf(meta(originalAssetId, ""), meta(forgedAssetId, "   "))
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held))
    }

    @Test fun `an asset not in the list yields nothing rather than throwing`() {
        val held = listOf(meta(forgedAssetId, originalCid))
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata("La-not-held", held))
    }

    @Test fun `an empty wallet yields nothing`() {
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, emptyList()))
    }

    /** Duplicate rows for one assetId (a re-fetch) must not make it its own duplicate. */
    @Test fun `a repeated row for the same assetId is not a duplicate`() {
        val held = listOf(meta(originalAssetId, originalCid), meta(originalAssetId, originalCid))
        assertEquals(emptyList<String>(), AssetDuplicateDetector.assetsSharingMetadata(originalAssetId, held))
    }
}
