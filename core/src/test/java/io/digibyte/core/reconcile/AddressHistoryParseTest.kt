package io.digibyte.core.reconcile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-parser coverage for the address-history backstop (build #1): the JSON
 * shapes the (now taproot-capable) node returns, decoded with no network. The
 * suspend network wrappers [DgbNodeClient.addressHistory]/[DgbNodeClient.fetchRawTx]
 * are thin over these + the existing httpGetJson and are verified on-device.
 */
class AddressHistoryParseTest {

    // ── parseAddressHistory (Task 1) ──────────────────────────────────────────

    @Test fun parses_transactions_with_txid_and_height() {
        val json = JSONObject(
            """
            {"address":"dgb1plr8","txCount":2,"transactions":[
              {"txid":"8096148944b5c4d136d62d6521ae7f85283dce1656de991a77b8faf1b2973a63","height":23877512,"confirmations":2919},
              {"txid":"b42c76de310f5b3fee6d115bcaf2410c844a665d7c5eb2d7f0ecc31a22511636","height":23874652,"confirmations":5779}
            ]}
            """.trimIndent()
        )
        val result = parseAddressHistory(json)
        assertEquals(2, result.size)
        assertEquals("8096148944b5c4d136d62d6521ae7f85283dce1656de991a77b8faf1b2973a63", result[0].txid)
        assertEquals(23877512L, result[0].height)
        assertEquals(23874652L, result[1].height)
    }

    @Test fun missing_transactions_array_yields_empty() {
        assertEquals(0, parseAddressHistory(JSONObject("""{"address":"x","txCount":0}""")).size)
    }

    @Test fun blank_txid_entries_skipped() {
        val json = JSONObject("""{"transactions":[{"txid":"","height":1},{"txid":"aa","height":2}]}""")
        val r = parseAddressHistory(json)
        assertEquals(1, r.size)
        assertEquals("aa", r[0].txid)
    }

    // ── parseRawTxResponse (Task 2) ───────────────────────────────────────────

    @Test fun parses_raw_tx_hex_and_time_with_supplied_height() {
        val json = JSONObject(
            """{"txid":"8096","hex":"07700001aabb","blocktime":1784416468,"confirmations":2919,"blockhash":"00044c37"}"""
        )
        val entry = parseRawTxResponse(json, height = 23877512L)!!
        assertEquals("07700001aabb", entry.hex)
        assertEquals(23877512L, entry.blockHeight)
        assertEquals(1784416468L, entry.blockTime)
    }

    @Test fun blank_hex_yields_null() {
        assertNull(parseRawTxResponse(JSONObject("""{"txid":"x"}"""), 1L))
    }

    @Test fun falls_back_to_time_when_blocktime_absent() {
        val entry = parseRawTxResponse(JSONObject("""{"hex":"aa","time":123}"""), 5L)!!
        assertEquals(123L, entry.blockTime)
    }
}
