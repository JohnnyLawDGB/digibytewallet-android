package io.digibyte.core.recovery

import io.digibyte.core.asset.DigiAssetEncoder
import io.digibyte.core.asset.send.DA_MARKER_SATS
import io.digibyte.core.reconcile.RawTxEntry
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driving the asset move: which asset got which money, and what is reported when one fails.
 *
 * The native touchpoints — parsing, signing, broadcasting — are injected, so what is under test
 * here is the orchestration, which is where the interesting failures live. A transfer that
 * fails to sign, fails to broadcast, or cannot be mapped to a signing key must all come back as
 * a NAMED asset that did not move. An asset that quietly disappears from the report reads to
 * the user as one that moved.
 */
class ForeignAssetTransferServiceTest {

    private val dest = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f"
    private val assetAddr = "DAsset1111111111111111111111111111"
    private val feeAddr = "DFee2222222222222222222222222222222"
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"

    /** A transfer marker moving 10 units to vout 0 — what the parent tx looked like. */
    private val parentOutputs = listOf(
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

    private val assetUtxo = utxo("a55e7", assetAddr, DA_MARKER_SATS)
    private val feeUtxo = utxo("feeee", feeAddr, 300_000L)

    private fun profileResult(
        utxos: List<UtxoEntry> = listOf(assetUtxo, feeUtxo),
        derived: List<DerivedAddress> = listOf(
            DerivedAddress(assetAddr, chain = 0, index = 4),
            DerivedAddress(feeAddr, chain = 1, index = 2),
        ),
    ) = RecoveryScanService.ProfileResult(
        profile = DerivationProfile(
            label = "BIP44", description = "legacy",
            hmacKey = DerivationProfile.HMAC_STANDARD,
            prefixPath = intArrayOf(44, 20, 0), addressFormat = 1, isNative = false,
        ),
        addresses = derived.map { it.address },
        derivedAddresses = derived,
        utxos = utxos,
        rawTxs = utxos.associate { it.txid to RawTxEntry(hex = "00", blockHeight = 1L, blockTime = 1L) },
        reachableBackend = true,
    )

    /** Only the asset UTXO's parent carries a marker; the fee UTXO's parent is plain. */
    private fun classifier() = ForeignUtxoAssetClassifier(
        fetchRawTx = { txid -> if (txid == "a55e7") byteArrayOf(1) else byteArrayOf(2) },
        isAssetTx = { it.contentEquals(byteArrayOf(1)) },
    )

    /** Everything handed to OutgoingTxStore, so the self-transfer flag can be asserted. */
    data class Recorded(
        val txid: String, val sentSats: Long, val feeSats: Long,
        val toAddress: String, val isSelfTransfer: Boolean,
    )

    private val recorded = mutableListOf<Recorded>()

    private fun service(
        sign: (ForeignAssetTransferPlan.Plan, ByteArray, DerivationProfile, Long) -> String? =
            { _, _, _, _ -> "00ff" },
        broadcast: (ByteArray) -> String? = { "txid-moved" },
        parse: (ByteArray) -> List<ForeignAssetQuantity.Output>? = { parentOutputs },
    ) = ForeignAssetTransferService(
        assetClassifier = classifier(),
        parseOutputs = parse,
        sign = sign,
        broadcast = broadcast,
        // android.util.Log is an unmocked stub on the JVM and throws when called.
        log = { _, _ -> },
        recordOutgoing = { t, s, f, to, self -> recorded += Recorded(t, s, f, to, self) },
    )

    private fun run(svc: ForeignAssetTransferService, results: List<RecoveryScanService.ProfileResult>) =
        runBlocking { svc.moveAssets(ByteArray(64) { 7 }, results, dest) }

    // ---- the happy path -----------------------------------------------------------------------

    @Test fun `an asset is moved and its txid reported`() {
        val r = run(service(), listOf(profileResult()))
        val move = r.moves.single()
        assertEquals("a55e7:0", move.outpoint)
        assertEquals(10L, move.units)
        assertEquals("txid-moved", move.txid)
        assertTrue(move.moved)
        assertTrue(r.allMoved)
    }

    /** The asset input must be spent, and every output must belong to the destination. */
    @Test fun `the signed plan spends the asset and pays everything to the destination`() {
        var seen: ForeignAssetTransferPlan.Plan? = null
        run(service(sign = { p, _, _, _ -> seen = p; "00ff" }), listOf(profileResult()))

        val plan = seen!!
        assertEquals("a55e7", plan.inputs.first().txid)
        assertTrue("the reserved fee UTXO paid for it", plan.inputs.any { it.txid == "feeee" })
        assertTrue(plan.outputs.filter { it.address.isNotEmpty() }.all { it.address == dest })
        assertEquals(dest, plan.outputs.last().address)
    }

    /** The signing key position comes from DerivedAddress, not from a list index. */
    @Test fun `each input carries its own derivation position`() {
        var seen: ForeignAssetTransferPlan.Plan? = null
        run(service(sign = { p, _, _, _ -> seen = p; "00ff" }), listOf(profileResult()))

        val asset = seen!!.inputs.first { it.txid == "a55e7" }
        assertEquals(0, asset.chain)
        assertEquals(4, asset.index)
        val fee = seen!!.inputs.first { it.txid == "feeee" }
        assertEquals(1, fee.chain)
        assertEquals(2, fee.index)
    }

    // ---- the failures, each of which must NAME the asset ---------------------------------------

    @Test fun `a native signing refusal is reported against the asset`() {
        val r = run(service(sign = { _, _, _, _ -> null }), listOf(profileResult()))
        val move = r.moves.single()
        assertNull(move.txid)
        assertFalse(move.moved)
        assertTrue(move.failureReason!!.contains("sign"))
    }

    @Test fun `a broadcast failure is reported against the asset`() {
        val r = run(service(broadcast = { null }), listOf(profileResult()))
        assertFalse(r.moves.single().moved)
        assertTrue(r.moves.single().failureReason!!.contains("roadcast"))
    }

    @Test fun `malformed signed hex is reported, not broadcast`() {
        var broadcasts = 0
        val r = run(
            service(sign = { _, _, _, _ -> "not-hex" }, broadcast = { broadcasts++; "x" }),
            listOf(profileResult()),
        )
        assertEquals("nothing was broadcast", 0, broadcasts)
        assertFalse(r.moves.single().moved)
    }

    /**
     * An asset whose address is missing from the derivation list cannot be signed for. Dropping
     * it from the report would show the user a clean run that left an asset behind.
     */
    @Test fun `an asset with no signing key is reported, not silently dropped`() {
        val r = run(
            service(),
            listOf(profileResult(derived = listOf(DerivedAddress(feeAddr, chain = 1, index = 2)))),
        )
        assertEquals("the asset is still accounted for", 1, r.moves.size)
        assertEquals("a55e7:0", r.moves.single().outpoint)
        assertFalse(r.moves.single().moved)
    }

    /** A quantity we could not read is refused — never moved on a guessed number. */
    @Test fun `an unreadable quantity is refused`() {
        val r = run(service(parse = { null }), listOf(profileResult()))
        assertFalse(r.moves.single().moved)
        assertTrue(r.moves.single().failureReason!!.contains("UNKNOWN_QUANTITY"))
    }

    @Test fun `a profile with no assets produces no moves`() {
        val plainOnly = profileResult(
            utxos = listOf(feeUtxo),
            derived = listOf(DerivedAddress(feeAddr, chain = 1, index = 2)),
        )
        val r = run(service(), listOf(plainOnly))
        assertTrue(r.moves.isEmpty())
        assertFalse("an empty batch moved nothing, so it is not 'all moved'", r.allMoved)
    }

    /**
     * The move must be recorded as a SELF transfer.
     *
     * Observed on mainnet 2026-08-28: the asset arrived and the balance went 90 -> 91, and the
     * activity list showed it as "Sent". The destination is this wallet's own receive address, so
     * the C core categorizes the transaction as a receive; recording it as an ordinary outgoing
     * send makes the list override that categorization and show a balance-INCREASING transaction
     * as money leaving. The swept DGB rendered correctly in the same run because
     * LegacySweepService passes the flag and this did not.
     *
     * See OutgoingTxStore.shouldApplyOutgoingOverride — the rule already existed; this path just
     * never opted into it.
     */
    @Test fun `the move is recorded as a self transfer, not an outgoing send`() {
        run(service(), listOf(profileResult()))

        val rec = recorded.single()
        assertEquals("txid-moved", rec.txid)
        assertEquals(dest, rec.toAddress)
        assertTrue(
            "recorded as an external send — the activity list will render this " +
                "balance-increasing asset move as \"Sent\"",
            rec.isSelfTransfer,
        )
    }
}
