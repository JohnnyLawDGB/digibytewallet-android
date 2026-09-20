package io.digibyte.core.asset

import io.digibyte.core.WalletTxPersister
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.ipfs.AssetMetadataService
import io.digibyte.core.model.AssetOperation
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persist-on-detect tests (task 6 / C6) for [AssetManager.maybePersistAfterDetect].
 *
 * ## Why this drives the extracted seam rather than the public
 * `processIncomingAssetTx`
 *
 * The brief's original skeleton called `processIncomingAssetTx` with
 * `mockkObject(NativeBridge)` stubbing `getTransactionOutputsForHash` /
 * `getTransactionInputsForHash` / `deriveIssuanceAssetId`. That does not run
 * on the host JVM unit-test runner: `NativeBridge` is a JNI object whose
 * `init` block does `System.loadLibrary("core-lib")`, and merely referencing
 * the object (which `mockkObject(NativeBridge)` must do) forces that static
 * initializer to run, throwing `UnsatisfiedLinkError` before any stubbing
 * takes effect. This is the same pre-existing constraint documented on
 * [AssetProvenanceTaggingTest] (task 2) and [AssetSourceFixTest] (task 3), and
 * in `WalletManagerSavedTransactionsDecodeTest` / `WalletWipeTest`.
 *
 * So the persist *decision* is extracted into its own seam,
 * [AssetManager.maybePersistAfterDetect], which touches nothing but the
 * injected [WalletTxPersister] — no `NativeBridge` involved. It's exercised
 * directly with a real (not mocked) [IncomingAssetInfo] standing in for "an
 * asset tx was detected", and `null` standing in for "it wasn't".
 */
class AssetPersistOnDetectTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaSvc = mockk<AssetMetadataService>(relaxed = true)
    private val persister = mockk<WalletTxPersister>(relaxed = true)
    private lateinit var mgr: AssetManager

    private val detected = IncomingAssetInfo(
        header = DecodedAssetHeader(
            version = 2,
            opcode = 0,
            operation = AssetOperation.TRANSFER,
            metadataHash = null,
            metadataCid = null,
            totalQuantity = null,
            divisibility = 0,
            locked = false,
            aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = emptyList(),
        ),
        assetId = "La1",
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        mgr = AssetManager(utxoDao, txDao, metaDao, metaSvc, walletTxPersister = persister)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun persist_called_on_detect_receive_path() {
        mgr.maybePersistAfterDetect(persistAfterDetect = true, detected = detected)
        verify { persister.persist() }
    }

    @Test fun sweep_path_does_not_persist() {
        mgr.maybePersistAfterDetect(persistAfterDetect = false, detected = detected)
        verify(exactly = 0) { persister.persist() }
    }

    @Test fun non_asset_tx_does_not_persist_even_when_flagged() {
        // A received tx that wasn't actually a DigiAsset tx (detection
        // returned null) must not trigger a wallet-state snapshot.
        mgr.maybePersistAfterDetect(persistAfterDetect = true, detected = null)
        verify(exactly = 0) { persister.persist() }
    }

    // ── Protect-on-detect through the "protect this outpoint" seam ─────
    //
    // Every owned output an instruction targets must be held out of the plain-DGB spendable set
    // at detection — explicit target, every output a range names, percent targets, and the
    // implicit-change remainder — while an owned output nothing targets (ordinary DGB change)
    // stays spendable. The decision is exercised through [AssetManager.protectTargetedOutputs]
    // with a recording seam, so it never touches `NativeBridge`.

    private fun transferHeader(instructions: List<TransferInstruction>) = DecodedAssetHeader(
        version = 3, opcode = 0x15, operation = AssetOperation.TRANSFER,
        metadataHash = null, metadataCid = null, totalQuantity = null,
        divisibility = 0, locked = true, aggregation = Aggregation.AGGREGATABLE,
        transferInstructions = instructions,
    )

    @Test fun protects_every_targeted_owned_output_and_not_plain_change() {
        val protectedVouts = mutableListOf<Int>()
        val m = AssetManager(
            utxoDao, txDao, metaDao, metaSvc,
            registerAssetOutpoint = { _, vout -> protectedVouts.add(vout); true },
        )
        // 8 outputs (0..7). Explicit target 0; range 0..3; percent target 5. Output 7 is the
        // last output and carries the implicit-change remainder, which is UNKNOWN here (a
        // percent instruction), so it is held out fail-closed. Outputs 4 and 6 are plain change.
        val header = transferHeader(
            listOf(
                TransferInstruction(skip = false, range = false, percent = false, outputIndex = 0, amount = 10L, isBurn = false),
                TransferInstruction(skip = false, range = true, percent = false, outputIndex = 3, amount = 5L, isBurn = false),
                TransferInstruction(skip = false, range = false, percent = true, outputIndex = 5, amount = 50L, isBurn = false),
            ),
        )
        m.protectTargetedOutputs(
            txHashHex = "aa".repeat(32),
            header = header,
            ownedVouts = listOf(0, 1, 2, 3, 4, 5, 6, 7),
            firstNonOpReturnVout = 0,
            inputUnits = 100L,
            outputCount = 8,
        )
        assertEquals(
            "every owned output an instruction targets (explicit, range, percent, implicit change) is held out",
            listOf(0, 1, 2, 3, 5, 7), protectedVouts.sorted(),
        )
        assertFalse("plain DGB change at vout 4 stays spendable", protectedVouts.contains(4))
        assertFalse("plain DGB change at vout 6 stays spendable", protectedVouts.contains(6))
    }

    private fun header(operation: AssetOperation, opcode: Int, instructions: List<TransferInstruction>) =
        DecodedAssetHeader(
            version = 3, opcode = opcode, operation = operation,
            metadataHash = null, metadataCid = null,
            totalQuantity = if (operation == AssetOperation.ISSUANCE) 100L else null,
            divisibility = 0, locked = true, aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = instructions,
        )

    private fun protectedVoutsFor(h: DecodedAssetHeader, owned: List<Int>, inputUnits: Long?, outputCount: Int): List<Int> {
        val seen = mutableListOf<Int>()
        val m = AssetManager(
            utxoDao, txDao, metaDao, metaSvc,
            registerAssetOutpoint = { _, vout -> seen.add(vout); true },
        )
        m.protectTargetedOutputs(
            txHashHex = "cc".repeat(32), header = h, ownedVouts = owned,
            firstNonOpReturnVout = 0, inputUnits = inputUnits, outputCount = outputCount,
        )
        return seen.sorted()
    }

    @Test fun protects_the_outputs_an_issuance_instruction_targets() {
        // An issuance distributes its units through the same instruction list a transfer uses.
        // Output 0 is the first non-OP_RETURN output; the instruction names output 2.
        val h = header(
            AssetOperation.ISSUANCE, 0x05,
            listOf(TransferInstruction(skip = false, range = false, percent = false, outputIndex = 2, amount = 100L, isBurn = false)),
        )
        assertEquals(
            "an owned output an issuance instruction targets is held out; untargeted outputs stay spendable",
            listOf(0, 2), protectedVoutsFor(h, owned = listOf(0, 1, 2, 3), inputUnits = null, outputCount = 5),
        )
    }

    @Test fun protects_the_outputs_a_burn_transaction_still_sends_units_to() {
        // A burn transaction destroys the units of its burn instruction and still delivers the
        // units of its other instructions. 60 burned + 40 to output 1 = all 100 input units, so
        // no remainder rides on the last output.
        val h = header(
            AssetOperation.BURN, 0x25,
            listOf(
                TransferInstruction(skip = false, range = false, percent = false, outputIndex = 31, amount = 60L, isBurn = true),
                TransferInstruction(skip = false, range = false, percent = false, outputIndex = 1, amount = 40L, isBurn = false),
            ),
        )
        assertEquals(
            "an owned output a burn transaction's non-burn instruction targets is held out",
            listOf(1), protectedVoutsFor(h, owned = listOf(0, 1, 2, 3), inputUnits = 100L, outputCount = 4),
        )
    }

    @Test fun output_31_is_a_target_unless_the_transaction_is_a_burn() {
        // The non-range index 31 means "destroy" only in a BURN transaction. In a TRANSFER with
        // 32 or more outputs it names a real output, which must be held out like any other.
        val to31 = listOf(
            TransferInstruction(skip = false, range = false, percent = false, outputIndex = 31, amount = 10L, isBurn = true),
        )
        assertEquals(
            "output 31 of a TRANSFER is an instruction target and is held out",
            listOf(31),
            protectedVoutsFor(header(AssetOperation.TRANSFER, 0x15, to31), owned = listOf(30, 31, 32), inputUnits = 10L, outputCount = 33),
        )
        assertEquals(
            "GUARD: index 31 of a BURN is the destroy marker, not a target",
            emptyList<Int>(),
            protectedVoutsFor(header(AssetOperation.BURN, 0x25, to31), owned = listOf(30, 31, 32), inputUnits = 10L, outputCount = 33),
        )
    }

    @Test fun replay_protects_a_targeted_row_whose_stored_quantity_is_zero() = runTest {
        val protectedOutpoints = mutableListOf<Pair<String, Int>>()
        val zeroRow = UtxoEntity(
            txid = "bb".repeat(32), vout = 4, scriptPubKey = byteArrayOf(1, 2, 3),
            satoshis = 700, blockHeight = 100, isAsset = true, assetId = "La9",
            assetQuantity = 0L, spent = false, assetSource = AssetSource.NATIVE,
        )
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(zeroRow)
        val m = AssetManager(
            utxoDao, txDao, metaDao, metaSvc,
            registerAssetOutpoint = { txid, vout -> protectedOutpoints.add(txid to vout); true },
            resolveRowTargets = { _, _ -> true }, // the row IS a targeted carrier
        )
        val n = m.replayAssetOutpointExclusions()
        assertEquals("the replay reports the targeted zero-quantity row as newly held out", 1, n)
        assertTrue(
            "a targeted row is held out at startup even though its stored quantity is zero",
            protectedOutpoints.contains(zeroRow.txid to 4),
        )
    }

    private fun zeroQuantityRow(txid: String, vout: Int) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = byteArrayOf(1, 2, 3),
        satoshis = 700, blockHeight = 100, isAsset = true, assetId = "La9",
        assetQuantity = 0L, spent = false, assetSource = AssetSource.NATIVE,
    )

    @Test fun replay_leaves_an_untargeted_zero_quantity_row_spendable() = runTest {
        // GUARD. `isAsset` is set on EVERY owned output of an asset transaction, its plain DGB
        // change included. A row nothing targets must stay spendable: a replay that held out
        // every unspent isAsset row would lock ordinary change out of spending.
        val protectedOutpoints = mutableListOf<Pair<String, Int>>()
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(zeroQuantityRow("dd".repeat(32), 2))
        val m = AssetManager(
            utxoDao, txDao, metaDao, metaSvc,
            registerAssetOutpoint = { txid, vout -> protectedOutpoints.add(txid to vout); true },
            resolveRowTargets = { _, _ -> false }, // nothing targets the row: plain DGB change
        )
        assertEquals("an untargeted zero-quantity row is not held out", 0, m.replayAssetOutpointExclusions())
        assertTrue("plain DGB change is never registered as an asset outpoint", protectedOutpoints.isEmpty())
    }

    @Test fun replay_holds_out_a_zero_quantity_row_it_cannot_decide() = runTest {
        // GUARD. When the targeting question has no answer (the transaction is held but cannot
        // be decoded, or the bridge gave no clean reply), the row is held out: an unswept output
        // is recoverable, a spent asset is not.
        val protectedOutpoints = mutableListOf<Pair<String, Int>>()
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(zeroQuantityRow("ee".repeat(32), 1))
        val m = AssetManager(
            utxoDao, txDao, metaDao, metaSvc,
            registerAssetOutpoint = { txid, vout -> protectedOutpoints.add(txid to vout); true },
            resolveRowTargets = { _, _ -> null },
        )
        assertEquals("an undecidable zero-quantity row is held out", 1, m.replayAssetOutpointExclusions())
        assertTrue("the undecidable row is the one registered", protectedOutpoints.contains("ee".repeat(32) to 1))
    }

    @Test fun replay_leaves_a_row_alone_only_when_native_positively_holds_no_such_transaction() {
        // A row whose transaction native does not hold has nothing to hold out yet: native
        // cannot select an output of a transaction it does not have, and when the transaction
        // arrives, detection protects every owned output an instruction targets on the same
        // path as any fresh receive. That answer is given ONLY on a positive statement --
        // wallet loaded, well-formed txid, a clean reply from the bridge. Every other reason
        // for "no outputs" stays without an answer, and the replay holds such a row out.
        assertEquals(
            "wallet loaded + well-formed txid + clean reply: native holds no such transaction",
            false,
            replayAnswerForAbsentTransaction(walletLoaded = true, txidWellFormed = true, bridgeAnsweredCleanly = true),
        )
        for (loaded in listOf(true, false)) for (wellFormed in listOf(true, false)) for (clean in listOf(true, false)) {
            if (loaded && wellFormed && clean) continue
            assertNull(
                "loaded=$loaded wellFormed=$wellFormed clean=$clean is not a positive statement",
                replayAnswerForAbsentTransaction(loaded, wellFormed, clean),
            )
        }
    }

    @Test fun replay_continues_past_a_row_whose_targets_cannot_be_resolved() = runTest {
        // One row's resolver failing must not end the replay: that row is held out, and every
        // later row -- a resolved carrier with a positive quantity here -- is still held out.
        val protectedOutpoints = mutableListOf<Pair<String, Int>>()
        val failing = zeroQuantityRow("f0".repeat(32), 0)
        val carrier = zeroQuantityRow("f1".repeat(32), 1).copy(assetQuantity = 25L)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(failing, carrier)
        val m = AssetManager(
            utxoDao, txDao, metaDao, metaSvc,
            registerAssetOutpoint = { txid, vout -> protectedOutpoints.add(txid to vout); true },
            resolveRowTargets = { _, _ -> throw IllegalStateException("resolver unavailable") },
        )
        val result = runCatching { m.replayAssetOutpointExclusions() }
        assertTrue("a resolver failure on one row must not end the replay", result.isSuccess)
        assertTrue(
            "the carrier after the failing row is still held out",
            protectedOutpoints.contains(carrier.txid to 1),
        )
        assertTrue(
            "the row whose targets could not be resolved is held out",
            protectedOutpoints.contains(failing.txid to 0),
        )
    }
}
