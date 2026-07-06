package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.json.JSONObject
import org.junit.Test

/**
 * WalletSignerFormat serializes a Kotlin-built transaction in breadwallet's
 * unsigned-input wire format so the C wallet signer (BRTransactionParse →
 * BRWalletSignTransaction) can recover each unsigned input's prevout script
 * and amount: the scriptSig slot carries the prevout scriptPubKey and is
 * followed by a non-standard 8-byte LE amount. Signed (witness-carrying)
 * inputs serialize normally.
 */
class WalletSignerFormatTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    private val fundingSpk = "0014" + "73123cca91a2700b75fc7191b62351742c4bf8dd"

    private fun mintLikeTx() = Transaction(
        version = DigiDollarVersion.build(DigiDollarTxType.MINT),
        locktime = 0,
        inputs = listOf(TxInput("aa".repeat(32), 1, sequence = 0xffffffffL)),
        outputs = listOf(TxOutput(5_000_000_000, "5120" + "bb".repeat(32))),
    )

    // The unsigned funding input's scriptSig slot must carry the prevout
    // scriptPubKey (0x16 = 22 bytes for P2WPKH) followed by the 8-byte LE
    // prevout amount, splicing into an otherwise standard serialization.
    @Test
    fun `unsigned input carries prevout script and amount in the scriptSig slot`() {
        val tx = mintLikeTx()
        val standard = tx.serialize()

        val actual = WalletSignerFormat.serialize(
            tx,
            mapOf(0 to TxOutput(7_200_000_000_000, fundingSpk)),
        ).toHex()

        // version(4B) + inCount(1B) + txid(32B) + vout(4B) = 41 bytes before
        // the scriptSig varint; standard form has "00" (empty) there.
        val prefixHex = standard.take(82)
        assertEquals("00", standard.substring(82, 84))
        val amountLe = "004071618c060000" // 7_200_000_000_000 sats LE
        val expected = prefixHex + "16" + fundingSpk + amountLe + standard.drop(84)

        assertEquals(expected, actual)
    }

    // A witness-carrying transaction keeps marker+flag and every signed
    // input's stack; only the designated input gains the script+amount slot.
    @Test
    fun `witnessed tx serializes unsigned fee input alongside signed stacks`() {
        val redeem = fixture("redeem-tx.json")
        val witnessed = fixtureWitnessedTx(redeem)
        val feeIndex = witnessed.inputs.size - 1
        val feePrevout = TxOutput(
            3_000_000_000,
            fixture("transfer-tx.json").getJSONArray("vout").getJSONObject(2)
                .getJSONObject("scriptPubKey").getString("hex"),
        )
        // Strip the fee input's witness — in production it is not yet signed.
        val tx = witnessed.copy(
            witnesses = witnessed.witnesses.mapIndexed { i, stack ->
                if (i == feeIndex) emptyList() else stack
            },
        )
        val standard = tx.serialize()

        val actual = WalletSignerFormat.serialize(tx, mapOf(feeIndex to feePrevout)).toHex()

        // Splice the fee input's empty scriptSig ("00") into script+amount.
        // The fee input is unique by its txid+vout+sequence framing.
        val feeInput = tx.inputs[feeIndex]
        val feeMarker = feeInput.txidHex.hexToByteArray().reversedArray().toHex() +
            "02000000" + "00" + "ffffffff"
        check(standard.indexOf(feeMarker) == standard.lastIndexOf(feeMarker))
        val spliced = standard.replace(
            feeMarker,
            feeMarker.dropLast(10) + // keep txid+vout, drop "00"+sequence
                "16" + feePrevout.scriptPubKeyHex + "005ed0b200000000" + "ffffffff",
        )

        assertEquals(spliced, actual)
    }

    @Test
    fun `rejects out-of-range indexes and inputs that already carry a witness`() {
        val redeem = fixture("redeem-tx.json")
        val witnessed = fixtureWitnessedTx(redeem)

        assertFailsWith<IllegalArgumentException> {
            WalletSignerFormat.serialize(mintLikeTx(), mapOf(1 to TxOutput(1, fundingSpk)))
        }
        assertFailsWith<IllegalArgumentException> {
            // vin0 carries the script-path witness — it is already signed.
            WalletSignerFormat.serialize(witnessed, mapOf(0 to TxOutput(1, fundingSpk)))
        }
        assertFailsWith<IllegalArgumentException> {
            WalletSignerFormat.serialize(mintLikeTx(), emptyMap())
        }
    }

    /** The fixture Redemption with Core's actual witness stacks attached. */
    private fun fixtureWitnessedTx(redeem: JSONObject): Transaction {
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
                val o = vout.getJSONObject(i)
                TxOutput(
                    o.getBigDecimal("value").movePointRight(8).longValueExact(),
                    o.getJSONObject("scriptPubKey").getString("hex"),
                )
            },
            witnesses = (0 until vin.length()).map { i ->
                val stack = vin.getJSONObject(i).getJSONArray("txinwitness")
                (0 until stack.length()).map { j -> stack.getString(j).hexToByteArray() }
            },
        )
    }
}
