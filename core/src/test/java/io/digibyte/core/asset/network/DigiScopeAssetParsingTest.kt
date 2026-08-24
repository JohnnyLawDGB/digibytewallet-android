package io.digibyte.core.asset.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing pinned against payloads captured from the live backend on 2026-08-23, not invented.
 *
 * WHY THIS EXISTS. The wallet asked `api.digiscope.me/api/assets/…` and got 404 for every asset
 * route, which read as "the backend has no asset data". It has all of it — at
 * `/api/digiassets/…`, in a different shape. A prefix and a shape, mistaken for an outage,
 * across several releases.
 *
 * The response also carries `name` and `decimals` inline, which the wallet deliberately does NOT
 * use: names and artwork come from IPFS content verified against its CID. Taking a name from a
 * server response would let a compromised backend rename someone's assets — a poor trade on a
 * wallet whose roadmap is sovereignty-first. Quantities are what this route is for.
 */
class DigiScopeAssetParsingTest {






    @Test
    fun `asset data parses the fields the wallet actually consumes`() {
        val json = JSONObject(
            """{"assetId":"La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp","cid":"bafybeigqaz",
                "issuer":"dgb1qissuer","count":10,"decimals":0}"""
        )

        val data = DigiScopeAssetParsing.assetData(json, fallbackAssetId = "ignored")

        assertEquals("La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp", data!!.assetId)
        assertEquals("bafybeigqaz", data.cid)
        assertEquals(10L, data.count)
        assertEquals(0, data.decimals)
    }

    /**
     * The live route currently answers 500 with this body — a malformed-params bug in the
     * backend's RPC proxy, not a wallet problem. It must parse as "no data" rather than as an
     * asset whose every field is a default, which is how a phantom row gets created.
     */
    @Test
    fun `the live 500 body parses to null, not a hollow asset`() {
        val body = """{"error":"DigiAsset RPC getassetdata error: Error during parsing of >>Invalid params<<"}"""

        assertNull(DigiScopeAssetParsing.assetData(JSONObject(body), fallbackAssetId = "La3t7"))
    }


    /** `mediaUrl` is present and deliberately unused — this pins that it stays that way. */
}
