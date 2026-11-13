package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import app.aroundtheblock.wallet.core.model.AssetOperation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ASSET-PROOF-GUARD-KT. A stored remainder proof is never trusted against the wallet's own rows: it
 * is read only when the inputs' known positive rows sum to 0 or exactly to what the instructions
 * assign. Otherwise — including a sum that overflows — the input total is unknown and every path
 * fails closed: the pass in front of a spend and the startup replay hold the last output, detection
 * reads no total, and the targeting walk resolves the total as unknown.
 *
 * Kotlin mirror of asset_hold_kat prespend_proof_known_units. The wallet holds `t`, a transfer
 * with no instructions (it assigns 0) whose last output `t:2` is ours. Its inputs are `f:7` (an
 * output of an asset transaction with no row: unknown) and `e4:0`, whose row this test sets.
 */
class AssetRemainderProofGuardTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val held = mutableListOf<Pair<String, Int>>()
    private val ours = "0014" + "11".repeat(20)
    private fun id(tag: String) = tag.padEnd(64, '0')
    private val f = id("e1"); private val t = id("e3"); private val e2 = id("e2"); private val e4 = id("e4")
    private val wallet = LinkedHashMap<String, Array<String>>()
    private val inputs = HashMap<String, Array<String>>()

    private val noInstructions = "6a0444410315"
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val tHeader by lazy { requireNotNull(DigiAssetDecoder().decode(bytes(noInstructions))) }

    private fun manager(proofs: RemainderProofStore, replay: Boolean = false): AssetManager {
        lateinit var mgr: AssetManager
        mgr = AssetManager(
            utxoDao = utxoDao, transactionDao = mockk(relaxed = true),
            metadataDao = mockk<AssetMetadataDao>(relaxed = true), metadataService = mockk(relaxed = true),
            registerAssetOutpoint = { txid, vout -> ((txid to vout) !in held).also { if (it) held += txid to vout } },
            resolveRowTargets = if (!replay) null else { txid, vout ->
                mgr.resolveRowTargetsImpl(txid, vout, { wallet[it] }, { inputs[it] }, walletLoaded = { true })
            },
            remainderProofs = proofs,
        )
        return mgr
    }

    private suspend fun AssetManager.pass() = holdAssetOutputsBeforeSpendImpl(
        txHashes = { wallet.keys.toTypedArray() }, outputsOf = { wallet[it] },
        inputsOf = { inputs[it] }, ownedScriptHexes = { setOf(ours) },
    )

    private fun row(txid: String, vout: Int, qty: Long) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = ByteArray(0), satoshis = 700L, blockHeight = 1L,
        isAsset = true, assetId = "La", assetQuantity = qty, spent = false)

    private fun setRow(qty: Long) {
        coEvery { utxoDao.getAssetUtxoAt(e4, 0) } returns row(e4, 0, qty)
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null
        // f: ours | plain OP_RETURN | DA transfer 10 -> output 0 | ours   (undecided under the one rule)
        wallet[f] = arrayOf("0|6000|$ours", "1|0|6a03aabbcc", "2|0|6a0644410115000a", "3|6000|$ours")
        inputs[f] = arrayOf("$e2|0")
        // t: DA transfer (opcode 0x15, v3) with no instructions | ours | ours (last)
        wallet[t] = arrayOf("0|0|$noInstructions", "1|6000|$ours", "2|6000|$ours")
        inputs[t] = arrayOf("$f|7", "$e4|0")
    }
    @After fun tearDown() = unmockkStatic(Log::class)

    private fun proven() = InMemoryRemainderProofStore().apply { markProven(t) }

    // ── The pass in front of a spend ──────────────────────────────────────

    @Test fun `pre-spend - ten known input units contradict the proof - last output stays held`() = runTest {
        setRow(10L)
        manager(proven()).pass()
        assertTrue("t:2 must be held: a row of 10 units entered and nothing is assigned; held=$held", t to 2 in held)
    }

    @Test fun `pre-spend - no proof - last output held`() = runTest {
        setRow(10L)
        manager(InMemoryRemainderProofStore()).pass()
        assertTrue(t to 2 in held)
    }

    @Test fun `pre-spend - zero known units and a proof - released`() = runTest {
        setRow(0L)
        manager(proven()).pass()
        assertFalse(t to 2 in held)
    }

    // ── The startup replay ────────────────────────────────────────────────

    @Test fun `replay - a contradicted proof does not drop the last output from the hold`() = runTest {
        setRow(10L)
        val mgr = manager(proven(), replay = true)
        assertEquals(true, mgr.resolveRowTargetsImpl(t, 2, { wallet[it] }, { inputs[it] }, walletLoaded = { true }))
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(t, 2, 0L))
        mgr.replayAssetOutpointExclusions()
        assertTrue(t to 2 in held)
    }

    @Test fun `replay - zero known units and a proof - not held`() = runTest {
        setRow(0L)
        val mgr = manager(proven(), replay = true)
        assertEquals(false, mgr.resolveRowTargetsImpl(t, 2, { wallet[it] }, { inputs[it] }, walletLoaded = { true }))
    }

    // ── Detection ─────────────────────────────────────────────────────────

    /** What detection reads for `t`: its rows' answer (unknown here: `f:7` has no row and `f` is an
     *  asset transaction), then the proof step. */
    private suspend fun AssetManager.detected(): Long? {
        val ins = listOf(f to 7, e4 to 0)
        val resolved = resolveInputAssetUnits(
            inputs = ins,
            rowQuantity = { tx, v -> utxoDao.getAssetUtxoAt(tx, v)?.assetQuantity },
            isAssetTx = { it == f || it == t },
            rowIsTargeted = { _, _ -> null },
            unresolved = ZeroRowInputs(),
        )
        assertNull(resolved)
        return detectedInputUnits(txHashHex = t, header = tHeader, inputs = ins, resolved = resolved)
    }

    @Test fun `detection - a contradicted proof gives no input total, so no remainder is credited as zero`() = runTest {
        setRow(10L)
        assertNull(manager(proven()).detected())
    }

    @Test fun `detection - zero known units and a proof - the proof's total`() = runTest {
        setRow(0L)
        assertEquals(0L, manager(proven()).detected())
    }

    // ── The targeting walk ────────────────────────────────────────────────

    private fun header(vararg instructions: TransferInstruction) = DecodedAssetHeader(
        version = 3, opcode = 0x15, operation = AssetOperation.TRANSFER,
        metadataHash = null, metadataCid = null, totalQuantity = null,
        divisibility = 0, locked = true, aggregation = Aggregation.AGGREGATABLE,
        transferInstructions = instructions.toList(),
    )
    private fun fixed(out: Int, amount: Long) =
        TransferInstruction(skip = false, range = false, percent = false, outputIndex = out, amount = amount, isBurn = false)

    /** `send` (4 outputs, last = 3) spends `foreign:0` (no row, asset tx: unknown) and the rows given;
     *  a proof is stored for it. Returns the walk's answer for `send:3` and the total it settled. */
    private suspend fun walk(h: DecodedAssetHeader, vararg known: Pair<Pair<String, Int>, Long>): Pair<Boolean?, MutableMap<String, Long?>> {
        val rows = known.toMap()
        val ins = listOf("foreign" to 0) + rows.keys
        val settled = HashMap<String, Long?>()
        val answer = outputIsTargeted(
            txid = "send", vout = 3,
            shapeOf = { if (it == "send") AssetTxShape(h, 0, 4) { ins } else null },
            rowQuantity = { tx, v -> rows[tx to v] },
            isAssetTx = { true },
            settled = settled,
            provenInputUnits = { tx, hh -> if (tx == "send") AssetTxQuantity.assignedUnits(hh) else null },
        )
        return answer to settled
    }

    @Test fun `walk - known rows above the assigned total make the total unknown`() = runTest {
        val (answer, settled) = walk(header(fixed(0, 5)), ("held" to 0) to 10L)
        assertTrue(settled.containsKey("send"))
        assertNull("the proof says 5, a row says 10: unknown", settled["send"])
        assertEquals(true, answer)
    }

    @Test fun `walk - known rows that overflow make the total unknown`() = runTest {
        val (answer, settled) = walk(header(fixed(0, 5)), ("a" to 0) to Long.MAX_VALUE, ("b" to 0) to Long.MAX_VALUE)
        assertNull(settled["send"])
        assertEquals(true, answer)
    }

    @Test fun `walk - known rows equal to the assigned total read the proof`() = runTest {
        val (answer, settled) = walk(header(fixed(0, 5)), ("held" to 0) to 5L)
        assertEquals(5L, settled["send"])
        assertEquals(false, answer)
    }

    @Test fun `walk - no known rows read the proof`() = runTest {
        val (answer, settled) = walk(header(fixed(0, 5)))
        assertEquals(5L, settled["send"])
        assertEquals(false, answer)
    }
}
