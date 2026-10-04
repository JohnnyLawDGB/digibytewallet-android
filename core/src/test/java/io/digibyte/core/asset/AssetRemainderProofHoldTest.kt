package io.digibyte.core.asset

import android.util.Log
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A recorded proof that an asset transaction's last output carries no units is read by every hold
 * path, so that output stays in the plain-coin set — and nothing else changes: the outputs an
 * instruction names stay held, and without a proof the last output is held as before.
 *
 * The wallet holds `received`, an asset transfer from someone else: its inputs are the sender's
 * outputs, which the wallet does not hold, so what they carried is unknown. It pays 1 unit to our
 * output 0; output 2 is ours and is the transfer's last output. `next` is a later send of the
 * wallet's own that spends `received:2` for its fee.
 */
class AssetRemainderProofHoldTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val held = mutableListOf<Pair<String, Int>>()

    private val ours = "0014" + "11".repeat(20)
    private val theirs = "0014" + "22".repeat(20)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fixed(out: Int, amount: Long) =
        DigiAssetEncoder.TransferInstruction(skip = false, range = false, percent = false, outputIndex = out, amount = amount)

    private fun id(tag: String) = tag.padEnd(64, '0')
    private val received = id("c1")
    private val next = id("d1")
    private val resolved = id("f1")

    private val wallet = LinkedHashMap<String, Array<String>>()
    private val inputs = HashMap<String, Array<String>>()

    private fun row(txid: String, vout: Int, quantity: Long) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = ByteArray(0), satoshis = 700L, blockHeight = 1L,
        isAsset = true, assetId = "La", assetQuantity = quantity, spent = false,
    )

    private fun manager(proofs: RemainderProofStore, replay: Boolean = false): AssetManager {
        lateinit var mgr: AssetManager
        mgr = AssetManager(
            utxoDao = utxoDao,
            transactionDao = mockk(relaxed = true),
            metadataDao = mockk<AssetMetadataDao>(relaxed = true),
            metadataService = mockk(relaxed = true),
            registerAssetOutpoint = { txid, vout -> ((txid to vout) !in held).also { if (it) held += txid to vout } },
            resolveRowTargets = if (!replay) null else { txid, vout ->
                mgr.resolveRowTargetsImpl(txid, vout, { wallet[it] }, { inputs[it] }, walletLoaded = { true })
            },
            remainderProofs = proofs,
        )
        return mgr
    }

    private suspend fun AssetManager.pass(): Int = holdAssetOutputsBeforeSpendImpl(
        txHashes = { wallet.keys.toTypedArray() },
        outputsOf = { wallet[it] },
        inputsOf = { inputs[it] },
        ownedScriptHexes = { setOf(ours) },
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null

        val oneToZero = hex(DigiAssetEncoder.encodeTransferScript(3, listOf(fixed(0, 1))))
        wallet[received] = arrayOf("0|6000|$ours", "1|0|$oneToZero", "2|4945300|$ours")
        inputs[received] = arrayOf("${id("a0")}|0", "${id("a1")}|1")          // the sender's: not held
        val fiveAndChange = hex(DigiAssetEncoder.encodeTransferScript(3, listOf(fixed(0, 5), fixed(2, 5))))
        wallet[next] = arrayOf("0|700|$theirs", "1|0|$fiveAndChange", "2|700|$ours", "3|4900000|$ours")
        inputs[next] = arrayOf("$resolved|0", "$received|2")

        val rows = listOf(row(received, 0, 1L), row(received, 2, 0L), row(resolved, 0, 10L), row(next, 2, 5L), row(next, 3, 0L))
        for (r in rows) coEvery { utxoDao.getAssetUtxoAt(r.txid, r.vout) } returns r
        coEvery { utxoDao.getAllAssetUtxosNow() } returns rows.filter { it.txid != resolved }
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun proven(vararg txids: String) = InMemoryRemainderProofStore().apply { txids.forEach { markProven(it) } }

    @Test fun `without a proof the unknown remainder is held, as before`() = runTest {
        manager(InMemoryRemainderProofStore()).pass()
        assertTrue(received to 2 in held)
        assertTrue(received to 0 in held)
        assertTrue("next's change rests on received:2, whose units are unknown", next to 3 in held)
    }

    @Test fun `with a proof the pass in front of a spend leaves the last output spendable`() = runTest {
        manager(proven(received)).pass()
        assertFalse(received to 2 in held)
        assertTrue("an output an instruction names stays held", received to 0 in held)
    }

    @Test fun `with a proof the startup replay does not hold the last output out again`() = runTest {
        manager(proven(received), replay = true).replayAssetOutpointExclusions()
        assertFalse(received to 2 in held)
        assertTrue(received to 0 in held)
    }

    @Test fun `without a proof the startup replay holds it`() = runTest {
        manager(InMemoryRemainderProofStore(), replay = true).replayAssetOutpointExclusions()
        assertTrue(received to 2 in held)
    }

    @Test fun `a later send that spends the proven output is resolved through the proof`() = runTest {
        manager(proven(received)).pass()
        assertFalse("next's inputs are now known (10 + 0), all assigned: its change is plain", next to 3 in held)
        assertTrue(next to 2 in held)
    }

    @Test fun `a proof is recorded and read back by id, in any case`() {
        val store = InMemoryRemainderProofStore()
        val mgr = manager(store)
        assertFalse(mgr.hasRemainderProof(received))
        mgr.recordRemainderProof(received.uppercase())
        assertTrue(mgr.hasRemainderProof(received))
        assertTrue(store.isProven(received))
        assertEquals(false, store.isProven(next))
    }
}
