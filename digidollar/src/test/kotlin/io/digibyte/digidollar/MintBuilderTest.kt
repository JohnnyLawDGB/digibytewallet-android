package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.json.JSONObject
import org.junit.Test

class MintBuilderTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    // The Core-built mint's session parameters, reconstructed from the
    // fixture and the discovery record: funded by 72,000 DGB at vout 0 of
    // 4a60f2cb..., fee 0.119 DGB, built at tip height 651 (regtest stand
    // mines 651 blocks; unlockHeight 1,037,552 = 651 + 1 + 100 + tier-3
    // lock blocks), price 13,420 micro-USD.
    @Test
    fun `unsigned Mint build matches the witness-stripped Core fixture`() {
        val mint = fixture("mint-tx.json")
        val meta = CrossCheckParse.mint(
            mint.getJSONArray("vout").getJSONObject(2)
                .getJSONObject("scriptPubKey").getString("hex"),
        )

        val built = MintBuilder.buildUnsigned(
            fundingUtxo = MintBuilder.FundingUtxo(
                txidHex = mint.getJSONArray("vin").getJSONObject(0).getString("txid"),
                vout = 0,
                valueSats = 7_200_000_000_000,
            ),
            ddCents = meta.ddCents,
            tier = LockTiers.byIndex(meta.lockTier),
            oraclePriceMicroUsd = 13_420,
            tipHeight = 651,
            feeSats = 11_900_000,
            ownerKeyHex = meta.ownerKeyHex,
            changePubKeyHash160Hex = "73123cca91a2700b75fc7191b62351742c4bf8dd",
            ecOps = BouncyCastleEcOps,
        )

        assertEquals(meta.unlockHeight, built.unlockHeight)
        assertEquals(2_634_128_166_915, built.collateralSats)

        // Byte-parity with the fixture, witnesses stripped (the signature is
        // the only part an unsigned build cannot reproduce).
        val vin = mint.getJSONArray("vin").getJSONObject(0)
        val vout = mint.getJSONArray("vout")
        val strippedFixture = Transaction(
            version = mint.getInt("version"),
            locktime = mint.getLong("locktime"),
            inputs = listOf(
                TxInput(vin.getString("txid"), vin.getInt("vout"), vin.getLong("sequence")),
            ),
            outputs = (0 until vout.length()).map { i ->
                val o = vout.getJSONObject(i)
                TxOutput(
                    o.getBigDecimal("value").movePointRight(8).longValueExact(),
                    o.getJSONObject("scriptPubKey").getString("hex"),
                )
            },
        ).serialize()
        assertEquals(strippedFixture, built.tx.serialize())
    }

    @Test
    fun `rejects mints outside consensus limits and insufficient funding`() {
        val utxo = MintBuilder.FundingUtxo("00".repeat(32), 0, 1_000_000_000)
        val base = { ddCents: Long, funding: MintBuilder.FundingUtxo ->
            MintBuilder.buildUnsigned(
                fundingUtxo = funding,
                ddCents = ddCents,
                tier = LockTiers.byIndex(3),
                oraclePriceMicroUsd = 13_420,
                tipHeight = 651,
                feeSats = MintBuilder.DEFAULT_FEE_SATS,
                ownerKeyHex = "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
                changePubKeyHash160Hex = "73123cca91a2700b75fc7191b62351742c4bf8dd",
                ecOps = BouncyCastleEcOps,
            )
        }
        // Below the $100 minimum and above the $100k maximum.
        assertFailsWith<IllegalArgumentException> { base(9_999, utxo) }
        assertFailsWith<IllegalArgumentException> { base(10_000_001, utxo) }
        // 1,000 sats cannot cover a $100 mint's collateral.
        assertFailsWith<IllegalArgumentException> {
            base(10_000, MintBuilder.FundingUtxo("00".repeat(32), 0, 1_000))
        }
    }

    // digidollar-js parity: change under 0.001 DGB never becomes an output —
    // it folds into the fee, and the change vout is omitted entirely.
    @Test
    fun `near-dust change folds into the fee instead of creating an output`() {
        val collateral = Collateral.requiredSats(
            ddCents = 10_000,
            tier = LockTiers.byIndex(3),
            oraclePriceMicroUsd = 13_420,
        )
        val built = MintBuilder.buildUnsigned(
            fundingUtxo = MintBuilder.FundingUtxo(
                "00".repeat(32),
                0,
                collateral + MintBuilder.DEFAULT_FEE_SATS + MintBuilder.CHANGE_FOLD_SATS - 1,
            ),
            ddCents = 10_000,
            tier = LockTiers.byIndex(3),
            oraclePriceMicroUsd = 13_420,
            tipHeight = 651,
            feeSats = MintBuilder.DEFAULT_FEE_SATS,
            ownerKeyHex = "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
            changePubKeyHash160Hex = "73123cca91a2700b75fc7191b62351742c4bf8dd",
            ecOps = BouncyCastleEcOps,
        )
        assertEquals(0, built.changeSats)
        assertEquals(3, built.tx.outputs.size) // collateral, DD token, OP_RETURN — no change vout
    }

    @Test
    fun `a malformed EcOps result is rejected, not silently mis-sliced`() {
        val truncatingEcOps = EcOps { key, _ -> key } // 32 bytes, no parity byte
        assertFailsWith<IllegalArgumentException> {
            Taproot.ddTokenOutputKey(
                "c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034",
                truncatingEcOps,
            )
        }
    }
}
