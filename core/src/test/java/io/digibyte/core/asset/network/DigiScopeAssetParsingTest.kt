package io.digibyte.core.asset.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Verbatim from GET /api/digiassets/address/dgb1qlzlx6p8nrnfned9l4luj2x22mw9teeg0pdmhmf */
    private val realHoldings = """
        {"address":"dgb1qlzlx6p8nrnfned9l4luj2x22mw9teeg0pdmhmf",
         "assets":[{"assetIndex":5341,"quantity":1,
                    "assetId":"La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp",
                    "name":"Chang Pablo Escobar","description":null,
                    "mediaUrl":"bafybeigqazrxezd42nhv6mqhnq6qrljmzdev55y7kzcc2fxyrvzlkgjwne",
                    "locked":false,"decimals":0}],
         "count":1}
    """.trimIndent()

    /** Verbatim from the same route for an address holding nothing. */
    private val emptyHoldings =
        """{"address":"dgb1q4znzns4srcpyswslmrmcq04luh43agrkqje223","assets":[],"count":0}"""

    @Test
    fun `holdings parse to assetId to quantity`() {
        val holdings = DigiScopeAssetParsing.holdings(JSONObject(realHoldings))

        assertEquals(mapOf("La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp" to 1L), holdings)
    }

    @Test
    fun `an address holding nothing parses to an empty map, not null`() {
        val holdings = DigiScopeAssetParsing.holdings(JSONObject(emptyHoldings))

        assertEquals(
            "empty is an answer — null would read as 'endpoint failed' and open the circuit",
            emptyMap<String, Long>(), holdings,
        )
    }

    /**
     * The backend answers 200 with count:0 for input that is not an address at all — it does no
     * validation. Parsing must not turn that into a confident "you hold nothing", so callers
     * that care are responsible for asking about real addresses.
     */
    @Test
    fun `an error body parses to null rather than an empty holding`() {
        assertNull(DigiScopeAssetParsing.holdings(JSONObject("""{"error":"Not found"}""")))
    }

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

    /**
     * digistamp's holdings route reports the address's DGB alongside its assets, keyed
     * "DigiByte":
     *
     *   {"La3t7Jdv…":1,"DigiByte":6000}
     *
     * Taken literally that is an asset named DigiByte holding 6000 units — a phantom row in the
     * user's asset list, and the wallet already has a history of those. DGB is not a DigiAsset
     * and its balance comes from the wallet's own UTXO set, never from a third party.
     */
    @Test
    fun `the DigiByte pseudo-asset is not treated as a holding`() {
        val withDgb = JSONObject(
            """{"La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp":1,"DigiByte":6000}"""
        )

        assertEquals(
            mapOf("La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp" to 1L),
            DigiScopeAssetParsing.flatHoldings(withDgb),
        )
    }

    /** `mediaUrl` is present and deliberately unused — this pins that it stays that way. */
    @Test
    fun `holdings parsing ignores server-asserted names and media`() {
        val parsed = DigiScopeAssetParsing.holdings(JSONObject(realHoldings))!!

        assertTrue(
            "holdings must carry quantities only; names come from CID-verified IPFS",
            parsed.values.all { it is Long },
        )
        assertEquals("one entry, keyed by asset id alone", setOf("La3t7Jdvjhf5XGGRBqVdny36VWPJ4gJcWMpAxp"), parsed.keys)
    }
}
