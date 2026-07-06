package io.digibyte.digidollar

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * Sighash KATs against the Core-built Redemption fixture — the one
 * transaction whose prevouts are all known from sibling fixtures and whose
 * inputs cover every sighash mode this wallet needs:
 *
 *   vin0 script-path BIP341 (normal Redemption leaf, untweaked Owner key)
 *   vin1/vin2 key-path BIP341 (DD-token burns, tweaked output keys)
 *   vin3 BIP143 P2WPKH (fee input, ECDSA)
 *
 * Verifying Core's own signatures against our computed digests proves the
 * digests without needing Core's nonces.
 */
class SighashTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    private val redeem = fixture("redeem-tx.json")
    private val redeemMint = fixture("redeem-mint-tx.json")
    private val transfer = fixture("transfer-tx.json")

    private fun tx(): Transaction {
        val vin = redeem.getJSONArray("vin")
        val vout = redeem.getJSONArray("vout")
        return Transaction(
            version = redeem.getInt("version"),
            locktime = redeem.getLong("locktime"),
            inputs = (0 until vin.length()).map { i ->
                val input = vin.getJSONObject(i)
                TxInput(input.getString("txid"), input.getInt("vout"), input.getLong("sequence"))
            },
            outputs = (0 until vout.length()).map { i ->
                val output = vout.getJSONObject(i)
                TxOutput(
                    output.getBigDecimal("value").movePointRight(8).longValueExact(),
                    output.getJSONObject("scriptPubKey").getString("hex"),
                )
            },
        )
    }

    private fun prevout(source: JSONObject, n: Int): TxOutput {
        val output = source.getJSONArray("vout").getJSONObject(n)
        return TxOutput(
            output.getBigDecimal("value").movePointRight(8).longValueExact(),
            output.getJSONObject("scriptPubKey").getString("hex"),
        )
    }

    // vin0 spends redeem-mint:0, vin1 transfer:0, vin2 transfer:1, vin3 transfer:2
    private val prevouts = lazy {
        listOf(prevout(redeemMint, 0), prevout(transfer, 0), prevout(transfer, 1), prevout(transfer, 2))
    }

    private fun witness(i: Int): List<ByteArray> {
        val w = redeem.getJSONArray("vin").getJSONObject(i).getJSONArray("txinwitness")
        return (0 until w.length()).map { w.getString(it).hexToByteArray() }
    }

    @Test
    fun `script-path digest verifies the Core signature with the untweaked Owner key`() {
        val meta = MintMetadata.parse(
            redeemMint.getJSONArray("vout").getJSONObject(2)
                .getJSONObject("scriptPubKey").getString("hex"),
        )
        val leafHash = Taproot.tapLeafHash(
            Taproot.normalRedemptionLeafScript(meta.unlockHeight, meta.ddCents, meta.ownerKeyHex),
        )
        val digest = Sighash.taproot(tx(), prevouts.value, inputIndex = 0, leafHash = leafHash)
        assertTrue(
            TestSchnorr.verify(meta.ownerKeyHex.hexToByteArray(), digest, witness(0)[0]),
        )
        // Wrong digest (key-path instead of script-path) must NOT verify.
        val keyPathDigest = Sighash.taproot(tx(), prevouts.value, inputIndex = 0, leafHash = null)
        assertFalse(
            TestSchnorr.verify(meta.ownerKeyHex.hexToByteArray(), keyPathDigest, witness(0)[0]),
        )
    }

    @Test
    fun `key-path digests verify the Core signatures with the output keys`() {
        for (i in listOf(1, 2)) {
            val outputKey = prevouts.value[i].scriptPubKeyHex.substring(4).hexToByteArray()
            val digest = Sighash.taproot(tx(), prevouts.value, inputIndex = i, leafHash = null)
            assertTrue(TestSchnorr.verify(outputKey, digest, witness(i)[0]), "vin$i")
        }
    }

    @Test
    fun `bip143 digest verifies the Core ECDSA signature on the P2WPKH fee input`() {
        val stack = witness(3)
        val derWithHashType = stack[0]
        val pubkey = stack[1]
        val digest = Sighash.bip143(
            tx(),
            inputIndex = 3,
            prevoutValueSats = prevouts.value[3].valueSats,
            pubKeyHash160Hex = prevouts.value[3].scriptPubKeyHex.substring(4),
        )
        assertTrue(
            TestEcdsa.verify(pubkey, digest, derWithHashType.copyOfRange(0, derWithHashType.size - 1)),
        )
    }
}
