package io.digibyte.core.asset.network

import io.digibyte.core.asset.send.StackEntry
import io.digibyte.core.asset.send.StackLookup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reply of `GET /digiassets/txout/:txid/:vout`. Anything malformed or about another output is
 *  "no answer", never an empty stack (which would read as "holds no asset"). */
class TxOutStackParsingTest {

    private val txid = "ab".repeat(32)
    private fun parse(json: String, vout: Int = 2) = DigiScopeAssetParsing.txOutStack(JSONObject(json), txid, vout)

    @Test fun the_stack_is_read_in_order() {
        val r = parse("""{"txid":"$txid","vout":2,"confirmations":4,"assets":[
            {"assetId":"La1","assetIndex":7,"count":5,"decimals":0},
            {"assetId":"Ua2","assetIndex":9,"count":3000000000000,"decimals":2}]}""")
        assertEquals(StackLookup.Found(listOf(StackEntry("La1", 5L), StackEntry("Ua2", 3_000_000_000_000L))), r)
    }

    @Test fun an_empty_stack_is_an_answer() {
        assertEquals(StackLookup.Found(emptyList()), parse("""{"txid":"$txid","vout":2,"assets":[]}"""))
    }

    @Test fun anything_else_is_no_answer() {
        val bad = listOf(
            """{"error":"indexer unavailable"}""",
            """{"txid":"${"cd".repeat(32)}","vout":2,"assets":[]}""",
            """{"txid":"$txid","vout":3,"assets":[]}""",
            """{"txid":"$txid","assets":[]}""",
            """{"txid":"$txid","vout":2}""",
            """{"txid":"$txid","vout":2,"assets":[{"count":5}]}""",
            """{"txid":"$txid","vout":2,"assets":[{"assetId":"La1"}]}""",
            """{"txid":"$txid","vout":2,"assets":[{"assetId":"La1","count":1.5}]}""",
            """{"txid":"$txid","vout":2,"assets":[{"assetId":"La1","count":-1}]}""",
            """{"txid":"$txid","vout":2,"assets":[{"assetId":"La1","count":"5"}]}""",
            """{"txid":"$txid","vout":2,"assets":[{"assetId":null,"count":5}]}""",
        )
        for (b in bad) assertEquals(b, StackLookup.Unavailable, parse(b))
    }

    /** A 404 means "not unspent" only when it is the endpoint's own answer; any other 404 (the route
     *  not deployed, a proxy page) is no answer, so the send says it could not check. */
    @Test fun only_the_endpoints_own_404_means_not_unspent() {
        assertEquals(StackLookup.NotUnspent, DigiScopeAssetParsing.notFound("""{"unspent":false}"""))
        for (b in listOf(null, "", "Cannot GET /api/digiassets/txout/x/0", """{"error":"not found"}""", """{"unspent":true}""", "<html>404</html>")) {
            assertEquals("$b", StackLookup.Unavailable, DigiScopeAssetParsing.notFound(b))
        }
    }
}
