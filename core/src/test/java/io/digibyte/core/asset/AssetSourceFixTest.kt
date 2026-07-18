package io.digibyte.core.asset

import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.ipfs.AssetMetadataService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Source-fix (C2) tests: the periodic sweep must NOT persist the owned
 * change-marker of an UNCONFIRMED OUTGOING asset send — that change-marker
 * is the dominant self-renewing phantom that over-counts the balance, since
 * the un-decremented spending input already reflects the pre-send balance.
 *
 * ## Why this drives [AssetManager.persistDetectedAssetOutput] directly
 * rather than the public `processIncomingAssetTx`
 *
 * The brief's original skeleton called `processIncomingAssetTx` with
 * `mockkObject(NativeBridge)` stubbing `getTransactionOutputsForHash` /
 * `getTransactionInputsForHash` / `deriveIssuanceAssetId`. That does not run
 * on the host JVM unit-test runner: `NativeBridge` is a JNI object whose
 * `init` block does `System.loadLibrary("core-lib")`, and merely referencing
 * the object (which `mockkObject(NativeBridge)` must do) forces that static
 * initializer to run, throwing `UnsatisfiedLinkError` before any stubbing
 * takes effect. This is the same pre-existing constraint documented on
 * [AssetProvenanceTaggingTest] (task 2) and in
 * `WalletManagerSavedTransactionsDecodeTest` / `WalletWipeTest`.
 *
 * So, per the task-3 ambiguity resolution, `isOutgoingUnconfirmed` is
 * threaded down to the Task-2 seam [AssetManager.persistDetectedAssetOutput]
 * itself (early-return, persisting nothing, when true) rather than gated by
 * a `continue` inside `processIncomingAssetTx`'s NativeBridge-calling loop.
 * That keeps the seam the SAME production code both tests exercise, and lets
 * this test reuse the exact working harness from [AssetProvenanceTaggingTest]
 * — mock only [UtxoDao], no `NativeBridge` involved.
 *
 * The pure per-row sweep decision ([isOutgoingUnconfirmedRow] — sent>0 AND
 * blockHeight>=TX_UNCONFIRMED) is unit-tested separately in
 * [IsOutgoingUnconfirmedRowTest].
 */
class AssetSourceFixTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaSvc = mockk<AssetMetadataService>(relaxed = true)
    private lateinit var mgr: AssetManager

    private val txid = "b".repeat(64)

    @Before fun setup() {
        mgr = AssetManager(utxoDao, txDao, metaDao, metaSvc)
    }

    @Test fun outgoing_unconfirmed_does_not_persist_owned_change() = runTest {
        mgr.persistDetectedAssetOutput(
            txHashHex = txid,
            vout = 1,
            scriptPubKey = ByteArray(0),
            sats = 6000L,
            blockHeight = 0L,
            placeholderAssetId = "La1",
            computedQty = 10L,
            isOutgoingUnconfirmed = true,
        )

        coVerify(exactly = 0) { utxoDao.insertAll(any()) }
        coVerify(exactly = 0) { utxoDao.markAssetSource(any(), any(), any()) }
    }

    @Test fun confirmed_outgoing_persists_owned_output() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(txid, 1) } returns null

        mgr.persistDetectedAssetOutput(
            txHashHex = txid,
            vout = 1,
            scriptPubKey = ByteArray(0),
            sats = 6000L,
            blockHeight = 0L,
            placeholderAssetId = "La1",
            computedQty = 10L,
            isOutgoingUnconfirmed = false,
        )

        coVerify { utxoDao.insertAll(any()) }
    }

    @Test fun incoming_unconfirmed_receive_still_persists() = runTest {
        // sent == 0 (an incoming receive) must NOT be gated even though the
        // tx is unconfirmed — only outgoing (sent > 0) + unconfirmed skips.
        // The caller (the sweep) is responsible for computing this false
        // for a receive; here we exercise the seam with that same false
        // value to prove it persists normally, mirroring
        // AssetProvenanceTaggingTest's "new output inserted" case but
        // through the isOutgoingUnconfirmed-bearing signature.
        coEvery { utxoDao.getAssetUtxoAt(txid, 1) } returns null

        mgr.persistDetectedAssetOutput(
            txHashHex = txid,
            vout = 1,
            scriptPubKey = ByteArray(0),
            sats = 6000L,
            blockHeight = 0L,
            placeholderAssetId = "La1",
            computedQty = 10L,
            isOutgoingUnconfirmed = false,
        )

        coVerify {
            utxoDao.insertAll(withArg { list ->
                assert(list.any { it.vout == 1 })
            })
        }
    }

    @Test fun outgoing_unconfirmed_does_not_retag_existing_row_either() = runTest {
        // Re-detection path: even when a row already exists (the case that
        // would normally re-tag provenance via markAssetSource), the
        // outgoing-unconfirmed gate must short-circuit before that lookup
        // has any persistence effect.
        coEvery { utxoDao.getAssetUtxoAt(txid, 1) } returns UtxoEntity(
            txid = txid, vout = 1, scriptPubKey = ByteArray(0), satoshis = 6000,
            blockHeight = 800000, isAsset = true, assetId = "La1", assetQuantity = 10,
            spent = false, assetSource = AssetSource.BACKEND
        )

        mgr.persistDetectedAssetOutput(
            txHashHex = txid,
            vout = 1,
            scriptPubKey = ByteArray(0),
            sats = 6000L,
            blockHeight = 0L,
            placeholderAssetId = "La1",
            computedQty = 10L,
            isOutgoingUnconfirmed = true,
        )

        coVerify(exactly = 0) { utxoDao.markAssetSource(any(), any(), any()) }
        coVerify(exactly = 0) { utxoDao.updateAssetQuantity(any(), any(), any()) }
        coVerify(exactly = 0) { utxoDao.insertAll(any()) }
    }
}

/**
 * Direct unit tests for the pure sweep-row predicate [isOutgoingUnconfirmedRow]
 * (source-fix / C2): `sent > 0 && blockHeight >= Int.MAX_VALUE` (the
 * `TX_UNCONFIRMED` sentinel jni_wallet's `getTransactionDetails` reports for
 * an unmined tx). No mocking needed — pure function of two Longs.
 */
class IsOutgoingUnconfirmedRowTest {
    @Test fun outgoing_and_unconfirmed_is_true() {
        assert(isOutgoingUnconfirmedRow(sent = 700L, blockHeight = Int.MAX_VALUE.toLong()))
    }

    @Test fun incoming_receive_is_false_even_if_unconfirmed() {
        assert(!isOutgoingUnconfirmedRow(sent = 0L, blockHeight = Int.MAX_VALUE.toLong()))
    }

    @Test fun confirmed_outgoing_is_false() {
        assert(!isOutgoingUnconfirmedRow(sent = 700L, blockHeight = 800_000L))
    }
}
