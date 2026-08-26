package io.digibyte.core.ipfs

import org.junit.Assert.assertEquals
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
}
