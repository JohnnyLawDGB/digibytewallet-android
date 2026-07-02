package io.digibyte.core.asset.send

import io.digibyte.core.db.entity.UtxoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetCoinSelectorTest {

    private fun assetUtxo(txid: String, sats: Long, qty: Long): UtxoEntity =
        UtxoEntity(
            txid = txid,
            vout = 0,
            scriptPubKey = ByteArray(0),
            satoshis = sats,
            blockHeight = 100L,
            isAsset = true,
            assetId = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch",
            assetQuantity = qty,
            spent = false,
        )

    private fun dgbUtxo(txid: String, sats: Long): UtxoEntity =
        UtxoEntity(
            txid = txid,
            vout = 0,
            scriptPubKey = ByteArray(0),
            satoshis = sats,
            blockHeight = 100L,
            isAsset = false,
            assetId = null,
            assetQuantity = 0,
            spent = false,
        )

    @Test
    fun `single asset utxo covers transfer with dgb change`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo("a", 600, 100)),
            dgbUtxos = listOf(dgbUtxo("b", 10_000)),
            assetNeeded = 50,
            feeSats = 500,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.Ok
        assertEquals(1, r.assetInputs.size)
        assertEquals(1, r.dgbInputs.size)
        assertEquals(50L, r.assetChangeQty)
        // inputs=10_600, fee=500, marker=600 → change 9_500
        assertEquals(9_500L, r.dgbChangeSats)
    }

    @Test
    fun `asset utxo sats offset dgb requirement`() {
        // Asset UTXO carries 5000 sats; fee+marker = 700+600 = 1300.
        // We should need NO extra DGB because the asset input covers it.
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo("a", 5_000, 100)),
            dgbUtxos = listOf(dgbUtxo("b", 10_000)),
            assetNeeded = 100,
            feeSats = 700,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.Ok
        assertTrue(r.dgbInputs.isEmpty())
        assertEquals(0L, r.assetChangeQty)
        assertEquals(3_700L, r.dgbChangeSats)
    }

    @Test
    fun `picks largest asset utxos first`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(
                assetUtxo("small", 600, 10),
                assetUtxo("big", 600, 100),
                assetUtxo("med", 600, 50),
            ),
            dgbUtxos = listOf(dgbUtxo("d", 10_000)),
            assetNeeded = 80,
            feeSats = 500,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.Ok
        assertEquals(1, r.assetInputs.size)
        assertEquals("big", r.assetInputs[0].txid)
        assertEquals(20L, r.assetChangeQty)   // 100 picked − 80 needed = 20 change
    }

    @Test
    fun `combines multiple asset utxos when single isn't enough`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(
                assetUtxo("a1", 600, 60),
                assetUtxo("a2", 600, 50),
            ),
            dgbUtxos = listOf(dgbUtxo("d", 10_000)),
            assetNeeded = 100,
            feeSats = 500,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.Ok
        assertEquals(2, r.assetInputs.size)
        assertEquals(10L, r.assetChangeQty)
    }

    @Test
    fun `insufficient asset returns error`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo("a", 600, 10)),
            dgbUtxos = listOf(dgbUtxo("d", 10_000)),
            assetNeeded = 100,
            feeSats = 500,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.InsufficientAsset
        assertEquals(100L, r.required)
        assertEquals(10L, r.available)
    }

    @Test
    fun `insufficient dgb fee returns error`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo("a", 600, 100)),
            dgbUtxos = listOf(dgbUtxo("d", 100)),
            assetNeeded = 50,
            feeSats = 1_000,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.InsufficientDgb
        // Need: fee 1000 + marker 600 − assetInputSats 600 = 1000. Have: 100.
        assertEquals(1_000L, r.required)
        assertEquals(100L, r.available)
    }

    @Test
    fun `picks largest dgb utxos first`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo("a", 600, 100)),
            dgbUtxos = listOf(
                dgbUtxo("small", 500),
                dgbUtxo("big", 100_000),
                dgbUtxo("med", 5_000),
            ),
            assetNeeded = 50,
            feeSats = 2_000,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.Ok
        assertEquals(1, r.dgbInputs.size)
        assertEquals("big", r.dgbInputs[0].txid)
    }

    @Test
    fun `asset change is zero when inputs match exactly`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo("a", 600, 100)),
            dgbUtxos = listOf(dgbUtxo("d", 10_000)),
            assetNeeded = 100,
            feeSats = 500,
            markerOutputSats = 600,
        )
        r as AssetCoinSelector.Result.Ok
        assertEquals(0L, r.assetChangeQty)
    }
}
