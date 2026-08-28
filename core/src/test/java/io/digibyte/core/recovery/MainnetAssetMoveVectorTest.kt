package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole asset-move pipeline against a REAL mainnet wallet, end to end, offline.
 *
 * ## Where this data came from
 *
 * A throwaway wallet funded on 2026-08-28 specifically to prove this path:
 *
 *   8429d8d9…:0    6,000 sat   a DigiAsset transfer marker  (height 24,111,539)
 *   1cc3baa2…:0    0.05 DGB    plain                        (height 24,111,538)
 *   5fb13338…:1    0.05 DGB    plain                        (height 24,111,538)
 *
 * Every byte below is what the reconcile backend actually returned, not a fixture someone wrote
 * to match the code. The synthetic tests prove the pieces agree with each other; this one proves
 * they agree with the chain — which is the only agreement that pays out.
 *
 * The asset transaction's OP_RETURN is `6a084441031500010208`: a real v3 DigiAsset transfer,
 * emitted by a wallet that is not this one. Our reader has to cope with somebody else's encoder.
 */
class MainnetAssetMoveVectorTest {

    /** Our address on that wallet: legacy P2PKH, hash160 5ff2cdea…. */
    private val script = "76a9145ff2cdea05a3cc8a588cdf03e659421ae2d2f14188ac"

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** The asset transaction's four outputs, exactly as they sit on chain. */
    private val assetTxOutputs = listOf(
        ForeignAssetQuantity.Output(0, 6_000L, bytes(script)),
        ForeignAssetQuantity.Output(1, 0L, bytes("6a084441031500010208")),
        ForeignAssetQuantity.Output(2, 6_000L, bytes("00141bdc36abb6fe5f9e6415275aaa0ccd14f4d1a9f9")),
        ForeignAssetQuantity.Output(3, 39_729_875_600L, bytes("00141bdc36abb6fe5f9e6415275aaa0ccd14f4d1a9f9")),
    )

    private fun utxo(txid: String, vout: Int, sats: Long) = UtxoEntry(
        txid = txid, vout = vout, amountSatoshi = sats,
        address = "DDtRbpmnGenHfxZZy4uDHBEiizQP4tZULE",
        blockHeight = 24_111_539L, scriptPubKeyHex = script,
    )

    private val assetUtxo = utxo("8429d8d9f1a9dfba8d066e52fbc996c862e59f25929cc052210c68607d318d16", 0, 6_000L)
    private val dgbA = utxo("1cc3baa29e98aaa5a4ffd4941386d2e34da77857fa7f06054182e6eb76e49efc", 0, 5_000_000L)
    private val dgbB = utxo("5fb1333851baed6b6f17e3b85907d77e6349bbfbe1c03112817f02bba4a5dc64", 1, 5_000_000L)

    private val dest = "dgb1qtlevm6s950xg5kyvmup7vk2zrt3d9u2pcj8f59"

    // ---- the quantity, read from somebody else's encoder ---------------------------------------

    @Test fun `units are read off the real marker`() {
        val units = ForeignAssetQuantity.unitsOn(assetTxOutputs, vout = 0)
        assertTrue(
            "no units read from the real mainnet marker 6a084441031500010208 — the move would " +
                "refuse with UNKNOWN_QUANTITY on a wallet that demonstrably holds the asset",
            units > 0L,
        )
    }

    /** The change outputs of that same transaction belong to the sender, not us. */
    @Test fun `our marker is the output we hold`() {
        assertEquals(6_000L, assetTxOutputs.first { it.vout == 0 }.sats)
    }

    // ---- the partition -------------------------------------------------------------------------

    @Test fun `the asset marker is held back and the plain DGB is sweepable`() {
        val split = SweepPartition.split(
            utxos = listOf(assetUtxo, dgbA, dgbB),
            carriesAsset = { it.txid == assetUtxo.txid },
            classified = { true },
        )
        assertEquals(listOf(assetUtxo), split.assetBearing)
        assertEquals(listOf(dgbA, dgbB), split.sweepable)
        assertEquals(10_000_000L, split.sweepableSat)
    }

    // ---- the reserve, and what is left to sweep -------------------------------------------------

    @Test fun `one output funds the move and the other is still swept`() {
        val reserve = AssetFeeReserve.reserve(sweepable = listOf(dgbA, dgbB), assetCount = 1)

        assertEquals("exactly one output held back", 1, reserve.reserved.size)
        assertEquals("the other is still swept", 1, reserve.stillSweepable.size)
        assertEquals("nothing is short", 0L, reserve.shortfall)
    }

    // ---- the transfer itself ---------------------------------------------------------------------

    @Test fun `the move plans successfully against the real wallet`() {
        val reserve = AssetFeeReserve.reserve(sweepable = listOf(dgbA, dgbB), assetCount = 1)
        val units = ForeignAssetQuantity.unitsOn(assetTxOutputs, vout = 0)

        fun spend(u: UtxoEntry, chain: Int, index: Int) = ForeignAssetTransferPlan.Spend(
            txid = u.txid, vout = u.vout, amountSat = u.amountSatoshi,
            scriptPubKeyHex = script, chain = chain, index = index,
        )

        val planned = ForeignAssetTransferBatch.plan(
            assets = listOf(ForeignAssetTransferBatch.AssetItem(spend(assetUtxo, 0, 0), units)),
            feePool = reserve.reserved.map { spend(it, 0, 0) },
            destAddress = dest,
            feePerKb = 100_000L,
        )

        val result = planned.single().result
        assertTrue("the real wallet must plan: $result", result is ForeignAssetTransferPlan.Result.Ok)

        val plan = (result as ForeignAssetTransferPlan.Result.Ok).plan
        assertEquals("asset input first", assetUtxo.txid, plan.inputs.first().txid)
        assertEquals("marker, OP_RETURN, change", 3, plan.outputs.size)
        assertEquals("the last output is ours, so residual units come home", dest, plan.outputs.last().address)
        assertTrue("every value output is ours",
            plan.outputs.filter { it.address.isNotEmpty() }.all { it.address == dest })

        // The band the native signer will actually accept for this exact shape.
        val estSize = 10 + plan.inputs.size * 160 + plan.outputs.size * 34
        val expected = (estSize.toLong() * 100_000L) / 1000L
        assertTrue("fee ${plan.feeSat} under the relay floor $expected", plan.feeSat >= expected)
        assertTrue("fee ${plan.feeSat} over 3x $expected — native would refuse", plan.feeSat <= expected * 3)
    }
}
