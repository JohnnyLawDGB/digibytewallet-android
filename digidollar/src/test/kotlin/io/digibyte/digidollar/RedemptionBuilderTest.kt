package io.digibyte.digidollar

import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test

/**
 * RedemptionBuilder KATs against the Core-built exact-burn Redemption
 * (fixtures/redeem-tx.json): the tier-0 Mint's Collateral spent script-path
 * via the normal Redemption leaf, two DD-token burns covering the full $100,
 * and a P2WPKH fee input.
 */
class RedemptionBuilderTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    private val redeem = fixture("redeem-tx.json")
    private val redeemMint = fixture("redeem-mint-tx.json")
    private val transfer = fixture("transfer-tx.json")

    // The redeemed Mint's parameters, parsed from its own OP_RETURN.
    private val mintMeta = CrossCheckParse.mint(
        redeemMint.getJSONArray("vout").getJSONObject(2)
            .getJSONObject("scriptPubKey").getString("hex"),
    )

    private fun sats(source: JSONObject, n: Int): Long =
        source.getJSONArray("vout").getJSONObject(n)
            .getBigDecimal("value").movePointRight(8).longValueExact()

    private fun buildFixtureRedemption(
        extraDd: RedemptionBuilder.DdTokenUtxo? = null,
        shortBurn: Boolean = false,
        feeSats: Long = 15_435_000,
        feeUtxoSats: Long? = null,
    ) = RedemptionBuilder.buildUnsigned(
        collateralUtxo = RedemptionBuilder.CollateralUtxo(
            txidHex = redeemMint.getString("txid"),
            vout = 0,
            valueSats = sats(redeemMint, 0),
            lockHeight = mintMeta.unlockHeight,
            ddCents = mintMeta.ddCents,
        ),
        ddUtxos = listOfNotNull(
            RedemptionBuilder.DdTokenUtxo(transfer.getString("txid"), 0, 3_000, ddSpk(0)),
            RedemptionBuilder.DdTokenUtxo(
                transfer.getString("txid"),
                1,
                if (shortBurn) 6_900 else 7_000,
                ddSpk(1),
            ),
            extraDd,
        ),
        feeUtxo = RedemptionBuilder.FeeUtxo(
            txidHex = transfer.getString("txid"),
            vout = 2,
            valueSats = feeUtxoSats ?: sats(transfer, 2),
            scriptPubKeyHex = transfer.getJSONArray("vout").getJSONObject(2)
                .getJSONObject("scriptPubKey").getString("hex"),
        ),
        feeSats = feeSats,
        ownerKeyHex = mintMeta.ownerKeyHex,
        collateralReturnScriptPubKeyHex = redeem.getJSONArray("vout").getJSONObject(0)
            .getJSONObject("scriptPubKey").getString("hex"),
        dgbChangeScriptPubKeyHex = redeem.getJSONArray("vout").getJSONObject(1)
            .getJSONObject("scriptPubKey").getString("hex"),
        ecOps = BouncyCastleEcOps,
    )

    private fun ddSpk(n: Int): String = transfer.getJSONArray("vout").getJSONObject(n)
        .getJSONObject("scriptPubKey").getString("hex")

    @Test
    fun `unsigned exact-burn Redemption matches the witness-stripped Core fixture`() {
        val built = buildFixtureRedemption()

        val vin = redeem.getJSONArray("vin")
        val vout = redeem.getJSONArray("vout")
        val strippedFixture = Transaction(
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
        ).serialize()

        assertEquals(strippedFixture, built.tx.serialize())
        assertEquals(0, built.ddChangeCents) // exact burn
    }

    // The fixture's vin0 witness stack is [signature, leaf script, control
    // block] — the builder must reproduce elements 1 and 2 byte-for-byte,
    // and the script-path digest over its own prevouts must verify Core's
    // actual signature (element 0) under the untweaked Owner key.
    @Test
    fun `witness materials match the Core witness stack and its signature verifies`() {
        val built = buildFixtureRedemption()
        val stack = redeem.getJSONArray("vin").getJSONObject(0).getJSONArray("txinwitness")

        assertEquals(stack.getString(1), built.leafScriptHex)
        assertEquals(stack.getString(2), built.controlBlockHex)

        val digest = Sighash.taproot(
            built.tx,
            built.prevouts,
            inputIndex = 0,
            leafHash = Taproot.tapLeafHash(built.leafScriptHex),
        )
        kotlin.test.assertTrue(
            TestSchnorr.verify(
                mintMeta.ownerKeyHex.hexToByteArray(),
                digest,
                stack.getString(0).hexToByteArray(),
            ),
        )
    }

    // Burning $110 against a $100 Mint: the $10 change comes back as a
    // zero-value DD-token output to the Owner's key plus the Redemption
    // OP_RETURN, ordered per Core's RedeemTxBuilder — collateral return,
    // DD change, OP_RETURN, DGB change last.
    @Test
    fun `over-burn emits DD change and Redemption metadata in Core's output order`() {
        val built = buildFixtureRedemption(
            extraDd = RedemptionBuilder.DdTokenUtxo("11".repeat(32), 0, 1_000, ddSpk(0)),
        )

        assertEquals(1_000, built.ddChangeCents)
        assertEquals(4, built.tx.outputs.size)
        val ddChange = built.tx.outputs[1]
        assertEquals(0, ddChange.valueSats)
        assertEquals(
            "5120" + Taproot.ddTokenOutputKey(mintMeta.ownerKeyHex, BouncyCastleEcOps),
            ddChange.scriptPubKeyHex,
        )
        assertEquals(
            RedemptionMetadata(1_000),
            CrossCheckParse.redemption(built.tx.outputs[2].scriptPubKeyHex),
        )
        assertEquals(built.dgbChangeSats, built.tx.outputs[3].valueSats)
    }

    // Same dust policy as MintBuilder: DGB change under 0.001 DGB folds
    // into the fee rather than becoming a near-dust output.
    @Test
    fun `near-dust DGB change folds into the fee`() {
        val built = buildFixtureRedemption(
            feeUtxoSats = 15_435_000 + MintBuilder.CHANGE_FOLD_SATS - 1,
        )
        assertEquals(0, built.dgbChangeSats)
        assertEquals(1, built.tx.outputs.size) // collateral return only
    }

    @Test
    fun `rejects partial redemptions and fees below the DigiDollar floor`() {
        // $99 burned against the $100 Mint — full redemption only.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            buildFixtureRedemption(shortBurn = true)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            buildFixtureRedemption(feeSats = DigiDollarLimits.MIN_DD_TX_FEE_SATS - 1)
        }
        // Fee input smaller than the fee itself.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            buildFixtureRedemption(feeUtxoSats = 1_000_000)
        }
    }
}
