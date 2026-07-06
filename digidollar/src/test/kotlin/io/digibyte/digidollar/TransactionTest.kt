package io.digibyte.digidollar

import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test

class TransactionTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    private fun transactionFrom(result: JSONObject): Transaction {
        val vin = result.getJSONArray("vin")
        val inputs = (0 until vin.length()).map { i ->
            val input = vin.getJSONObject(i)
            TxInput(
                txidHex = input.getString("txid"),
                vout = input.getInt("vout"),
                sequence = input.getLong("sequence"),
            )
        }
        val vout = result.getJSONArray("vout")
        val outputs = (0 until vout.length()).map { i ->
            val output = vout.getJSONObject(i)
            TxOutput(
                valueSats = output.getBigDecimal("value").movePointRight(8).longValueExact(),
                scriptPubKeyHex = output.getJSONObject("scriptPubKey").getString("hex"),
            )
        }
        val witnesses = (0 until vin.length()).map { i ->
            val w = vin.getJSONObject(i).optJSONArray("txinwitness") ?: return@map emptyList()
            (0 until w.length()).map { j -> w.getString(j).hexToByteArray() }
        }
        return Transaction(
            version = result.getInt("version"),
            locktime = result.getLong("locktime"),
            inputs = inputs,
            outputs = outputs,
            witnesses = witnesses,
        )
    }

    // All four Core-built fixtures rebuild byte-for-byte from components:
    // covers a Mint (P2WPKH witness), a Transfer (two key-path witnesses),
    // and the script-path Redemption (3-element witness, CLTV locktime).
    @Test
    fun `serializes all Core-built fixtures byte-for-byte`() {
        for (name in listOf(
            "mint-tx.json",
            "transfer-tx.json",
            "redeem-tx.json",
            "redeem-mint-tx.json",
        )) {
            val result = fixture(name)
            assertEquals(result.getString("hex"), transactionFrom(result).serialize(), name)
        }
    }

    @Test
    fun `computed txids match the Core-built fixtures`() {
        for (name in listOf("mint-tx.json", "redeem-tx.json")) {
            val result = fixture(name)
            assertEquals(result.getString("txid"), transactionFrom(result).txid(), name)
        }
    }
}
