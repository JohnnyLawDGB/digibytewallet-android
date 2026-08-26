package io.digibyte.core.ipfs

import io.digibyte.core.asset.network.DigiScopeAssetParsing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wallet must never present a *claimed* issuer as attribution.
 *
 * WHAT WENT WRONG. `AssetDetailScreen` rendered a row labelled "Issuer Address", copyable, whose
 * value came out of the IPFS metadata document — a file the minter wrote. It read three keys in
 * turn: `issuerAddress`, `issuer.address`, then bare `issuer`, which in practice is often a
 * *username* rather than an address. So a row labelled "Address" could show a handle, and every
 * one of those values was chosen by whoever minted the asset.
 *
 * WHY THAT IS WORSE THAN SHOWING NOTHING. Attribution is exactly what a forger wants to inherit:
 * copy an asset, re-mint it, write the original creator's address into the metadata, and the
 * claim travels with the image. A wallet that renders it has not merely failed to help — it has
 * made the forgery more convincing than it would have been on its own. The same file already
 * says as much about this data ("issuer-controlled IPFS metadata is fully attacker-controlled in
 * the worst case"); it was sanitised for display safety and then trusted for meaning.
 *
 * WHAT REPLACES IT. The provider now reports the issuer the chain proves: the owner of `input[0]`
 * of the issuance transaction. That outpoint is what the assetId is derived from, so an asset and
 * its issuer are one fact written twice, and the claim cannot be copied — it is whoever actually
 * paid for the issuance. When that cannot be established the answer is **null**, and the row
 * simply does not render. Absent beats wrong.
 *
 * These tests exist to stop the fallback growing back. The failure they guard against is one
 * `?:` away, and would look entirely reasonable in review.
 */
class AssetIssuerProvenanceTest {

    private val provenAddress = "dgb1qsrfh5u99zmc6f8p5wmxmw2vpzhwh6jmjmhg482"
    private val someoneElsesAddress = "DQTjL9vfXVbMfCgbmoZDdMBxNs2Dqmy7yD"

    @Test fun `a proven issuer is used`() {
        assertEquals(
            provenAddress,
            AssetMetadataService.provenIssuerOnly(proven = provenAddress, claimed = null),
        )
    }

    /** The regression this file exists for: no proof, a claim present, answer is still null. */
    @Test fun `a claimed issuer alone yields nothing`() {
        assertNull(AssetMetadataService.provenIssuerOnly(proven = null, claimed = someoneElsesAddress))
    }

    /** The forgery shape: a claim that contradicts the proof must not win, or even show up. */
    @Test fun `a claim never overrides or supplements the proof`() {
        assertEquals(
            provenAddress,
            AssetMetadataService.provenIssuerOnly(proven = provenAddress, claimed = someoneElsesAddress),
        )
    }

    /** Their mint writes a username here when the creator has one — never an address. */
    @Test fun `a claimed username yields nothing`() {
        assertNull(AssetMetadataService.provenIssuerOnly(proven = null, claimed = "chopperbriano"))
    }

    @Test fun `blank and whitespace proof count as no proof`() {
        assertNull(AssetMetadataService.provenIssuerOnly(proven = "", claimed = someoneElsesAddress))
        assertNull(AssetMetadataService.provenIssuerOnly(proven = "   ", claimed = someoneElsesAddress))
    }

    @Test fun `nothing at all yields nothing`() {
        assertNull(AssetMetadataService.provenIssuerOnly(proven = null, claimed = null))
    }

    // ---- a real, confirmed forgery ----------------------------------------------------------

    /**
     * DORRO #5, `assets.digistamp.co/listing/cmrxzrkhd02avdo7s7ymb93ue`, verified a forgery by the
     * wallet owner: a **new issuance** pointing at the **original's IPFS CID**.
     *
     * That is the shape worth remembering. Reusing the CID means every metadata-derived field is
     * byte-identical to the original — same name, same image, same description — and our own
     * [io.digibyte.core.ipfs.CidVerifier] passes cleanly, because the content genuinely does hash
     * to that CID. **Content verification proves integrity, never authorship.** Nothing derived
     * from the document can separate this from the original; only the chain can.
     *
     * And it does, flatly. Captured live 2026-08-26:
     *
     *   listing page, from the metadata:  DGr3ns1diMzbio2gvU98i6y4gAxkVpweGw
     *   GET /api/assets/<id>, from chain: DAUsKjc7FK37HPQsKyAsrcVBDqs2Kfmyvh
     *
     * Before this fix the wallet showed the first of those, under a label reading "Issuer
     * Address" — handing the forgery the one thing it could not manufacture for itself.
     */
    @Test fun `the DORRO 5 forgery resolves to the address that paid for the issuance`() {
        // Verbatim response body, GET https://assets.digistamp.co/api/assets/La2rJL6S…, 2026-08-26.
        val live = JSONObject(
            """{"assetId":"La2rJL6S35GjytTaNyZVGxmA1mSVfzUhP7PDex",
                "cid":"QmX4J9pzWpL1DUAGHwPwH2R7AeacmPqBZcdDGy8xWVfkYu",
                "issuer":"DAUsKjc7FK37HPQsKyAsrcVBDqs2Kfmyvh","count":1,"decimals":0}"""
        )
        val claimedInMetadata = "DGr3ns1diMzbio2gvU98i6y4gAxkVpweGw"

        val parsed = DigiScopeAssetParsing.assetData(live, fallbackAssetId = "ignored")!!
        assertEquals("DAUsKjc7FK37HPQsKyAsrcVBDqs2Kfmyvh", parsed.issuer)

        val shown = AssetMetadataService.provenIssuerOnly(
            proven = parsed.issuer,
            claimed = claimedInMetadata,
        )
        assertEquals("DAUsKjc7FK37HPQsKyAsrcVBDqs2Kfmyvh", shown)
        assertNotEquals(
            "the wallet displayed the address the minter claimed — the forgery's whole purpose",
            claimedInMetadata, shown,
        )
    }

    /**
     * The residual, stated so it is not mistaken for solved: showing the proven issuer stops the
     * wallet AMPLIFYING a forgery, but it does not DETECT one. A holder of this asset now sees a
     * true address they have no way to evaluate, beside artwork identical to the original.
     *
     * The detectable signature is the reuse itself — two different assetIds sharing one metadata
     * CID. The wallet can only see that across assets it holds, and `assets.digistamp.co` exposes
     * no CID-collision lookup (probed 2026-08-26: no such route). Detection needs either a local
     * duplicate check across held assets or a signal from the provider, which sees the whole index.
     */
    @Test fun `the forged issuance has a different assetId but the original's cid`() {
        val forgery = JSONObject(
            """{"assetId":"La2rJL6S35GjytTaNyZVGxmA1mSVfzUhP7PDex",
                "cid":"QmX4J9pzWpL1DUAGHwPwH2R7AeacmPqBZcdDGy8xWVfkYu","count":1,"decimals":0}"""
        )
        val parsed = DigiScopeAssetParsing.assetData(forgery, fallbackAssetId = "ignored")!!

        // The CID is what travelled; the assetId is what could not. An assetId is derived from
        // input[0] of its own issuance, so a copy necessarily gets a new one.
        assertEquals("QmX4J9pzWpL1DUAGHwPwH2R7AeacmPqBZcdDGy8xWVfkYu", parsed.cid)
        assertNotNull("the cid is the field a forger reuses verbatim", parsed.cid)
    }
}
