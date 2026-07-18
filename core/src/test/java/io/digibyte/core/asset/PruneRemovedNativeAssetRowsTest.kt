package io.digibyte.core.asset

import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AssetManager.pruneRemovedNativeAssetRows] — the sovereign
 * replacement for the removed 30s backend refresh (C3). Deletes a
 * NATIVE-tagged asset row once native has POSITIVELY removed its tx, but only
 * after [AssetManager.ABSENCE_DEBOUNCE_THRESHOLD] consecutive absent passes.
 *
 * ## Why this drives [AssetManager.pruneRemovedNativeAssetRowsImpl] rather
 * than the public `pruneRemovedNativeAssetRows`
 *
 * The public function's `isTxGone` check calls through the real `NativeBridge`
 * singleton (`NativeBridge.getTransactionOutputsForHash`), a JNI object whose
 * `init` block does `System.loadLibrary("core-lib")` — unavailable on the
 * host JVM unit-test runner. Merely referencing `NativeBridge` to mock it
 * (`mockkObject(NativeBridge)`, as an earlier draft of this test tried) throws
 * `UnsatisfiedLinkError` before any stubbing takes effect — the same,
 * pre-existing constraint documented on
 * [AssetManager.persistDetectedAssetOutput] / `AssetProvenanceTaggingTest`.
 *
 * So the debounce loop under test here lives in `pruneRemovedNativeAssetRowsImpl`,
 * which takes the native tx-absence check as a plain suspend lambda. The
 * public `pruneRemovedNativeAssetRows()` is an untested one-line delegate that
 * wires the real NativeBridge call into that same lambda shape.
 */
class PruneRemovedNativeAssetRowsTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager

    private fun row(txid: String, source: String) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = ByteArray(0), satoshis = 6000,
        blockHeight = 0, isAsset = true, assetId = "La1", assetQuantity = 3,
        spent = false, assetSource = source
    )

    @Before fun setup() {
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1
    }

    @Test fun native_row_gone_deleted_only_after_debounce() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns listOf(row(txid, AssetSource.NATIVE))
        val isTxGone: suspend (String) -> Boolean = { true }   // native dropped it, every pass

        assertEquals(0, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))   // sweep 1: below threshold
        assertEquals(1, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))   // sweep 2: delete
        coVerify(exactly = 1) { utxoDao.deleteAssetUtxo(txid, 0) }
    }

    @Test fun native_row_present_resets_debounce_before_threshold() = runTest {
        val txid = "c".repeat(64)
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns listOf(row(txid, AssetSource.NATIVE))
        // absent, then present (must reset the count), then absent again — if
        // the implementation summed absences instead of requiring consecutive
        // ones, this sequence (2 "true" answers total) would wrongly delete
        // on the third call. A correct reset keeps every call below threshold.
        val answers = listOf(true, false, true)
        var idx = 0
        val isTxGone: suspend (String) -> Boolean = { answers[idx++] }

        assertEquals(0, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))   // absence count -> 1
        assertEquals(0, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))   // present -> reset to 0
        assertEquals(0, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))   // absence count -> 1 (not 2)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun malformed_txid_never_queried_or_deleted() = runTest {
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns listOf(row("short", AssetSource.NATIVE))
        var callCount = 0
        val isTxGone: suspend (String) -> Boolean = { callCount++; true }

        assertEquals(0, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))
        assertEquals(0, callCount)   // never invoked for a non-64-hex txid
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun queries_only_native_source_backend_rows_untouched() = runTest {
        // Locks the call site to AssetSource.NATIVE: a BACKEND row (a real
        // holding native never scanned, with a null tx) is never even fetched
        // by this method, let alone deleted, regardless of isTxGone.
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns emptyList()
        val isTxGone: suspend (String) -> Boolean = { true }

        assertEquals(0, mgr.pruneRemovedNativeAssetRowsImpl(isTxGone))
        coVerify(exactly = 1) { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) }
        coVerify(exactly = 0) { utxoDao.getAssetUtxosBySourceNow(AssetSource.BACKEND) }
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }
}
