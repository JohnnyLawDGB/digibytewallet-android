package io.digibyte.core.recovery

import io.digibyte.core.asset.DigiAssetEncoder
import io.digibyte.core.asset.send.DA_MARKER_SATS
import io.digibyte.core.reconcile.RawTxEntry
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asset move and the fee-output split sign only inputs whose amount and script their parent
 * transaction states.
 *
 * Driven through the real [ForeignAssetTransferService.moveAssets] with the native touchpoints
 * replaced: parents come from a [TxBook], and the signer only records what it was given.
 */
class ForeignAssetInputProofTest {

    private val dest = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f"
    private val assetAddr = "DAsset1111111111111111111111111111"
    private val feeAddr = "DFee2222222222222222222222222222222"
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"

    private val book = TxBook()
    private val signed = mutableListOf<ForeignAssetTransferPlan.Plan>()
    private var broadcasts = 0

    /** The asset's parent: a transfer marker moving 10 units to vout 0. Read for the units only. */
    private val markerOutputs = listOf(
        ForeignAssetQuantity.Output(0, DA_MARKER_SATS, ByteArray(25) { 0x11 }),
        ForeignAssetQuantity.Output(
            1, 0L,
            DigiAssetEncoder.encodeTransferScript(
                version = 3,
                instructions = listOf(
                    DigiAssetEncoder.TransferInstruction(
                        skip = false, range = false, percent = false, outputIndex = 0, amount = 10L,
                    ),
                ),
            ),
        ),
    )

    private fun utxo(txid: String, addr: String, sats: Long, vout: Int = 0) = UtxoEntry(
        txid = txid, vout = vout, amountSatoshi = sats, address = addr,
        blockHeight = 24_000_000L, scriptPubKeyHex = script,
    )

    private fun result(utxos: List<UtxoEntry>, parents: Map<String, RawTxEntry>) =
        RecoveryScanService.ProfileResult(
            profile = DerivationProfile(
                label = "BIP44", description = "legacy",
                hmacKey = DerivationProfile.HMAC_STANDARD,
                prefixPath = intArrayOf(44, 20, 0), addressFormat = 0, isNative = false,
            ),
            addresses = listOf(assetAddr, feeAddr),
            derivedAddresses = listOf(
                DerivedAddress(assetAddr, chain = 0, index = 4),
                DerivedAddress(feeAddr, chain = 1, index = 2),
            ),
            utxos = utxos,
            rawTxs = parents,
            reachableBackend = true,
        )

    private fun service() = ForeignAssetTransferService(
        assetClassifier = ForeignUtxoAssetClassifier(
            fetchRawTx = { txid -> if (txid.startsWith("asset")) byteArrayOf(1) else byteArrayOf(2) },
            isAssetTx = { it.contentEquals(byteArrayOf(1)) },
            resolveRuleState = { io.digibyte.core.asset.rules.TransferRuleState.NONE },
        ),
        parents = book.binding,
        parseOutputs = { markerOutputs },
        sign = { plan, _, _, _ -> signed += plan; "00ff" },
        broadcast = { broadcasts++; "moved-txid" },
        log = { _, _ -> },
        recordOutgoing = { _, _, _, _, _ -> },
    )

    private fun move(utxos: List<UtxoEntry>, parents: Map<String, RawTxEntry>) = runBlocking {
        service().moveAssets(ByteArray(64) { 7 }, listOf(result(utxos, parents)), dest)
    }

    private val asset = utxo("asset-a", assetAddr, DA_MARKER_SATS)

    /** GUARD: with honest parents the asset moves, spending the amounts the parents state. */
    @Test fun `honest parents move the asset with the stated amounts`() {
        val fee = utxo("fee-a", feeAddr, 300_000L)
        book.honest(asset, fee)
        val r = move(listOf(asset, fee), book.rawTxs(listOf(asset, fee)))

        assertTrue(r.moves.single().moved)
        val plan = signed.single()
        assertEquals(DA_MARKER_SATS, plan.inputs.first { it.txid == asset.txid }.amountSat)
        assertEquals(300_000L, plan.inputs.first { it.txid == fee.txid }.amountSat)
    }

    @Test fun `a fee input lower than its parent states leaves the asset unmoved and nothing signed`() {
        val fee = utxo("fee-b", feeAddr, 300_000L)
        book.honest(asset, fee)
        val reportedLow = fee.copy(amountSatoshi = 90_000L)
        val r = move(listOf(asset, reportedLow), book.rawTxs(listOf(asset, fee)))

        assertTrue("nothing may be signed, got $signed", signed.isEmpty())
        assertEquals(0, broadcasts)
        val m = r.moves.single()
        assertEquals("${asset.txid}:0", m.outpoint)
        assertFalse(m.moved)
        assertNotNull(m.failureReason)
    }

    @Test fun `an asset input whose parent is another transaction is not signed`() {
        val fee = utxo("fee-c", feeAddr, 300_000L)
        val other = utxo("other-c", feeAddr, DA_MARKER_SATS)
        book.honest(asset, fee, other)
        val parents = book.rawTxs(listOf(fee)) + (asset.txid to book.entry(other.txid))
        val r = move(listOf(asset, fee), parents)

        assertTrue("nothing may be signed, got $signed", signed.isEmpty())
        assertFalse(r.moves.single().moved)
    }

    @Test fun `an asset input without a parent is left in place and named`() {
        val fee = utxo("fee-d", feeAddr, 300_000L)
        book.honest(asset, fee)
        val r = move(listOf(asset, fee), book.rawTxs(listOf(fee)))

        assertTrue("nothing may be signed, got $signed", signed.isEmpty())
        val m = r.moves.single()
        assertEquals("${asset.txid}:0", m.outpoint)
        assertFalse(m.moved)
        assertNotNull(m.failureReason)
    }

    @Test fun `a fee input without a parent is not spent`() {
        // Smallest-first selection would take the smaller one; it has no parent.
        val noParent = utxo("fee-e1", feeAddr, 200_000L)
        val proven = utxo("fee-e2", feeAddr, 300_000L, vout = 1)
        book.honest(asset, noParent, proven)
        val r = move(listOf(asset, noParent, proven), book.rawTxs(listOf(asset, proven)))

        val plan = signed.single()
        assertFalse(
            "an input with no parent was signed: ${plan.inputs.map { it.txid }}",
            plan.inputs.any { it.txid == noParent.txid },
        )
        assertTrue(r.moves.single().moved)
    }

    @Test fun `the split spends only plain inputs whose parent was supplied`() {
        val assets = (1..3).map { utxo("asset-g$it", assetAddr, DA_MARKER_SATS, vout = it) }
        val proven = utxo("plain-g1", feeAddr, 9_000_000L)
        val noParent = utxo("plain-g2", feeAddr, 2_000_000L, vout = 1)
        book.honest(*(assets + proven + noParent).toTypedArray())
        val r = move(assets + proven + noParent, book.rawTxs(assets + proven))

        assertTrue("the split is signed from the proven input, got ${r.fanOut}",
            r.fanOut is ForeignAssetTransferService.FanOut.Broadcast)
        val split = signed.single()
        assertEquals(
            "the split spends only the input its parent states",
            listOf(proven.txid), split.inputs.map { it.txid },
        )
        assertEquals(9_000_000L, split.inputs.single().amountSat)
    }

    @Test fun `the split is not signed when an input it would spend is contradicted`() {
        val assets = (1..3).map { utxo("asset-f$it", assetAddr, DA_MARKER_SATS, vout = it) }
        val plain = utxo("plain-f", feeAddr, 9_000_000L)
        book.honest(*(assets + plain).toTypedArray())
        val reportedLow = plain.copy(amountSatoshi = 5_000_000L)
        val r = move(assets + reportedLow, book.rawTxs(assets + plain))

        assertTrue("nothing may be signed, got $signed", signed.isEmpty())
        assertEquals(0, broadcasts)
        assertEquals(ForeignAssetTransferService.FanOut.NotNeeded, r.fanOut)
        assertEquals("every asset is still accounted for", 3, r.moves.size)
        assertTrue(r.moves.none { it.moved })
    }

    // ---- a refused split says which of its two reasons applies ----------------------------------

    private fun refusal(r: ForeignAssetTransferService.Result): ForeignAssetTransferService.FanOut.Refused {
        val no = r.fanOut as? ForeignAssetTransferService.FanOut.Refused
        assertNotNull("expected a refused split, got ${r.fanOut}", no)
        assertTrue("nothing may be signed, got $signed", signed.isEmpty())
        assertEquals(0, broadcasts)
        return no!!
    }

    @Test fun `a split whose plain coins all lack a readable parent names them instead of reporting none`() {
        val assets = (1..3).map { utxo("asset-h$it", assetAddr, DA_MARKER_SATS, vout = it) }
        val plain1 = utxo("plain-h1", feeAddr, 9_000_000L)
        val plain2 = utxo("plain-h2", feeAddr, 2_000_000L, vout = 1)
        book.honest(*(assets + plain1 + plain2).toTypedArray())
        // The third asset has no parent either: it is not a plain coin, so it is not named.
        val r = move(assets + plain1 + plain2, book.rawTxs(assets.take(2)))

        val no = refusal(r)
        assertEquals(ForeignAssetTransferService.FanOut.Refused.Reason.PARENTS_UNREAD, no.reason)
        assertEquals(listOf("plain-h1:0", "plain-h2:1"), no.unreadInputs)
        assertTrue("the detail must say the parents could not be read: ${no.detail}",
            no.detail.contains("could not be read"))
        assertFalse("the detail must not say the wallet has no DGB: ${no.detail}",
            no.detail.contains("this wallet has 0"))
    }

    @Test fun `a split short even with one plain coin read still names the unread one`() {
        val assets = (1..3).map { utxo("asset-i$it", assetAddr, DA_MARKER_SATS, vout = it) }
        val small = utxo("plain-i1", feeAddr, 100_000L)
        val unread = utxo("plain-i2", feeAddr, 9_000_000L, vout = 1)
        book.honest(*(assets + small + unread).toTypedArray())
        val r = move(assets + small + unread, book.rawTxs(assets + small))

        val no = refusal(r)
        assertEquals(ForeignAssetTransferService.FanOut.Refused.Reason.PARENTS_UNREAD, no.reason)
        assertEquals(listOf("plain-i2:1"), no.unreadInputs)
        assertTrue("the detail states what the read coins hold: ${no.detail}",
            no.detail.contains("100000"))
        assertTrue("the detail names the unread coin: ${no.detail}", no.detail.contains("plain-i2:1"))
    }

    /** GUARD: every parent read and too little DGB is a shortfall, and nothing is named unread. */
    @Test fun `a split short of DGB with every parent read is a plain shortfall`() {
        val assets = (1..3).map { utxo("asset-j$it", assetAddr, DA_MARKER_SATS, vout = it) }
        val small = utxo("plain-j1", feeAddr, 100_000L)
        book.honest(*(assets + small).toTypedArray())
        val r = move(assets + small, book.rawTxs(assets + small))

        val no = refusal(r)
        assertEquals(ForeignAssetTransferService.FanOut.Refused.Reason.NOT_ENOUGH_DGB, no.reason)
        assertTrue(no.unreadInputs.isEmpty())
        assertTrue("the shortfall must be stated", no.shortfallSat > 0)
        assertTrue(no.detail, no.detail.contains("this wallet has 100000"))
    }

    /** GUARD: no plain coin at all is a shortfall of the whole amount, and nothing is named unread. */
    @Test fun `a wallet with no plain coin at all is a plain shortfall`() {
        val assets = (1..3).map { utxo("asset-k$it", assetAddr, DA_MARKER_SATS, vout = it) }
        book.honest(*assets.toTypedArray())
        val r = move(assets, book.rawTxs(assets))

        val no = refusal(r)
        assertEquals(ForeignAssetTransferService.FanOut.Refused.Reason.NOT_ENOUGH_DGB, no.reason)
        assertTrue(no.unreadInputs.isEmpty())
        assertTrue(no.detail, no.detail.contains("this wallet has 0"))
    }
}
