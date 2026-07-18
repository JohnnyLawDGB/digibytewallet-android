package io.digibyte.core.asset

import android.util.Log
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AssetManager.healLegacyChangeAddressOrphans] — the one-time,
 * change-address-scoped heal of pre-existing owned-CHANGE-address asset
 * orphans (the legacy "Chang" over-count, C9). This is the ONLY place in the
 * asset-maintenance path allowed to delete an owned row, and only when native
 * has positively lost the tx AND the row's script is a change/internal
 * address — a real EXTERNAL receive can never satisfy the change-script test,
 * so it can never become a candidate.
 *
 * ## Why this drives [AssetManager.healLegacyChangeAddressOrphansImpl] rather
 * than the public `healLegacyChangeAddressOrphans`
 *
 * The public function's tx-absence check calls through the real `NativeBridge`
 * singleton (`NativeBridge.getTransactionOutputsForHash`), a JNI object whose
 * `init` block does `System.loadLibrary("core-lib")` — unavailable on the
 * host JVM unit-test runner. Merely referencing `NativeBridge` to mock it
 * (`mockkObject(NativeBridge)`) throws `UnsatisfiedLinkError` before any
 * stubbing takes effect — the same, pre-existing constraint documented on
 * [AssetManager.pruneRemovedNativeAssetRowsImpl] / `PruneRemovedNativeAssetRowsTest`.
 *
 * So the candidate/delete logic under test here lives in
 * `healLegacyChangeAddressOrphansImpl`, which takes the native tx-absence
 * check as a plain suspend lambda and the change-script set as a plain
 * `Set<String>` built by hand (no `buildChangeScriptHexes` / NativeBridge
 * enumeration involved). The public `healLegacyChangeAddressOrphans(...)` is
 * an untested delegate that wires the real NativeBridge call into that same
 * lambda shape.
 */
class LegacyChangeAddressHealTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val changeScript = byteArrayOf(1, 2, 3)
    private val externalScript = byteArrayOf(9, 9, 9)
    private val changeScriptHex = changeScript.joinToString("") { "%02x".format(it) }

    private fun row(txid: String, script: ByteArray) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = 6000, blockHeight = 0,
        isAsset = true, assetId = "La1", assetQuantity = 7, spent = false, assetSource = AssetSource.BACKEND)

    @Before fun setup() {
        // healLegacyChangeAddressOrphansImpl logs via android.util.Log directly
        // (not gated behind a NativeBridge call), so it needs stubbing on the
        // host JVM's non-Robolectric android.jar — same pattern used by
        // AssetHistoryBackfillTest.
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun change_orphan_txgone_is_candidate_and_deleted_when_not_dryrun() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, changeScript))
        val isTxGone: suspend (String) -> Boolean = { true }   // native no longer has this tx

        val res = mgr.healLegacyChangeAddressOrphansImpl(setOf(changeScriptHex), dryRun = false, isTxGone)

        assertEquals(1, res.candidates.size)
        assertEquals(1, res.deleted)
        coVerify(exactly = 1) { utxoDao.deleteAssetUtxo(txid, 0) }
    }

    @Test fun dryrun_logs_but_deletes_nothing() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, changeScript))
        val isTxGone: suspend (String) -> Boolean = { true }

        val res = mgr.healLegacyChangeAddressOrphansImpl(setOf(changeScriptHex), dryRun = true, isTxGone)

        // Same candidate set as the non-dry-run case — dryRun only gates the
        // delete, never the candidate detection.
        assertEquals(1, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun external_address_row_never_a_candidate() = runTest {
        // A row whose scriptPubKey is a real EXTERNAL receive address (not in
        // the change-script set) must never be eligible, even though its tx is
        // gone — this is the whole safety argument for the heal at all.
        val txid = "b".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, externalScript))
        var callCount = 0
        val isTxGone: suspend (String) -> Boolean = { callCount++; true }

        val res = mgr.healLegacyChangeAddressOrphansImpl(setOf(changeScriptHex), dryRun = false, isTxGone)

        assertEquals(0, res.candidates.size)
        assertEquals(0, res.deleted)
        assertEquals(0, callCount)   // never even queried — script mismatch short-circuits first
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun present_tx_never_a_candidate() = runTest {
        // Change-scripted row, but native still holds the tx (isTxGone=false)
        // — a live owned change output must survive regardless of script match.
        val txid = "c".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, changeScript))
        val isTxGone: suspend (String) -> Boolean = { false }

        val res = mgr.healLegacyChangeAddressOrphansImpl(setOf(changeScriptHex), dryRun = false, isTxGone)

        assertEquals(0, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }
}
