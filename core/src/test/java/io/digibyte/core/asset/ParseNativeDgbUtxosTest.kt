package io.digibyte.core.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parsing the native DGB-UTXO accessor output into fee-selectable rows — the
 * sovereign DGB source that replaced the empty Room is_asset=0 partition
 * ("Not enough DGB for fee: have 0").
 */
class ParseNativeDgbUtxosTest {

    private val txid = "a".repeat(64)

    @Test fun parses_a_valid_line() {
        val utxos = parseNativeDgbUtxos("$txid|2|500000|76a914aabbccddeeff00112233445566778899aabbccdd88ac\n")
        assertEquals(1, utxos.size)
        assertEquals(txid, utxos[0].txid)
        assertEquals(2, utxos[0].vout)
        assertEquals(500000L, utxos[0].satoshis)
        assertEquals(false, utxos[0].isAsset)
        assertEquals(false, utxos[0].spent)
    }

    @Test fun skips_malformed_zero_amount_and_odd_hex() {
        val raw = """
            bad|line
            $txid|0|0|76a914aa88ac
            short|1|100
            ${"b".repeat(64)}|1|100|abc
            $txid|3|100|76a914bb88ac
        """.trimIndent()
        val utxos = parseNativeDgbUtxos(raw)
        assertEquals(1, utxos.size) // only the last line survives
        assertEquals(3, utxos[0].vout)
        assertEquals(100L, utxos[0].satoshis)
    }

    @Test fun empty_or_blank_yields_empty() {
        assertEquals(0, parseNativeDgbUtxos("").size)
        assertEquals(0, parseNativeDgbUtxos("  \n \n").size)
    }
}
