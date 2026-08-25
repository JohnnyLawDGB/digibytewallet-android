package io.digibyte.core.asset.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.digibyte.core.ipfs.IpfsClient
import org.junit.Test

/**
 * Pinned against payloads captured from assets.digistamp.co on 2026-08-25.
 *
 * WHY THIS PROVIDER EXISTS. An asset whose issuance carries no metadata hash has only one route
 * to a name: getAssetData. On device that route had NOWHERE to go — digiscope answers
 * `/api/digiassets/asset/{id}` with 500 (`getassetdata error: Invalid params`), and digistamp,
 * which answers correctly, was not in the rotation at all. The visible symptom was an asset
 * rendering as a bare `La4WAqZf…` with supply and divisibility present (those come from the
 * on-chain header) but no name, description or issuer.
 */
class DigistampAssetClientTest {

    /** Verbatim from GET https://assets.digistamp.co/api/assets/{id}. */
    private val realAsset = """
        {"assetId":"La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC",
         "cid":"ipfs://QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co",
         "issuer":null,"count":100,"decimals":0}
    """.trimIndent()

    @Test fun `asset data parses`() {
        val d = DigiScopeAssetParsing.assetData(JSONObject(realAsset), fallbackAssetId = "ignored")!!

        assertEquals("La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC", d.assetId)
        assertEquals(100L, d.count)
        assertEquals(0, d.decimals)
    }

    /**
     * The cid arrives scheme-PREFIXED here while the issuance-header path yields a bare one.
     * IpfsClient.normalizeCid absorbs that; this pins that the prefixed form survives parsing
     * intact rather than being silently dropped, because a null cid here reads as "this asset
     * has no metadata" — the very state being fixed.
     */
    @Test fun `a scheme-prefixed cid is carried through, not dropped`() {
        val d = DigiScopeAssetParsing.assetData(JSONObject(realAsset), fallbackAssetId = "x")!!

        assertEquals("ipfs://QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co", d.cid)
        assertEquals(
            "and normalises to the bare CID the gateway wants",
            "QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co",
            IpfsClient.normalizeCid(d.cid),
        )
    }

    /** A null issuer is data, not an error — this asset genuinely has none recorded. */
    @Test fun `a null issuer parses to null without failing the whole response`() {
        val d = DigiScopeAssetParsing.assetData(JSONObject(realAsset), fallbackAssetId = "x")!!
        assertNull(d.issuer)
        assertEquals("La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC", d.assetId)
    }

    @Test fun `an error body yields no data`() {
        assertNull(DigiScopeAssetParsing.assetData(JSONObject("""{"error":"Not found"}"""), "x"))
    }
}
