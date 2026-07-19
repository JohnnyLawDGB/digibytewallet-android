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
 * Tests for [AssetManager.pruneUnownedAssetRows] — the sovereign owned-script
 * phantom prune that clears the residual DigiAsset over-count (e.g. CHANG showing
 * "21" for a supply-10 asset). A row survives iff its scriptPubKey is one of the
 * wallet's own scripts; a not-owned recipient-marker output (from a transfer WE
 * sent) is a delete candidate. Ownership is the sole delete authority — the
 * native spent-state probe is diagnostic only and never gates the delete.
 *
 * Drives [AssetManager.pruneUnownedAssetRowsImpl] rather than the public delegate
 * for the same host-JVM/NativeBridge reason documented on
 * [LegacyChangeAddressHealTest]: the public [AssetManager.pruneUnownedAssetRows]
 * calls buildOwnedScriptHexes()/NativeBridge, which load `core-lib`.
 */
class PruneUnownedAssetRowsTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val unownedScript = byteArrayOf(9, 9, 9)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }

    private fun row(txid: String, script: ByteArray) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = 6000, blockHeight = 0,
        isAsset = true, assetId = "La1", assetQuantity = 7, spent = false, assetSource = AssetSource.BACKEND)

    // Native outpoint tri-state probes: HELD(1) = tx known + unspent, SPENT(0) =
    // in spentOutputs, UNDETECTED(-1) = native has no such tx. All diagnostic only —
    // the delete decision is ownership, never native spent-state.
    private val heldState: suspend (String, Int) -> Int = { _, _ -> 1 }
    private val deadState: suspend (String, Int) -> Int = { _, _ -> -1 }
    private val spentNativeState: suspend (String, Int) -> Int = { _, _ -> 0 }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ── Not-owned (recipient marker) — deleted regardless of native state ──

    @Test fun unowned_row_is_candidate_and_deleted_when_not_dryrun() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, unownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(setOf(ownedHex), dryRun = false, heldState)

        assertEquals(1, res.candidates.size)
        assertEquals(1, res.deleted)
        coVerify(exactly = 1) { utxoDao.deleteAssetUtxo(txid, 0) }
    }

    @Test fun dryrun_logs_but_deletes_nothing() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, unownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(setOf(ownedHex), dryRun = true, heldState)

        // dryRun only gates the delete, never the candidate detection.
        assertEquals(1, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    // ── Owned rows are NEVER deleted here — whatever native reports ──

    @Test fun owned_held_row_never_a_candidate() = runTest {
        val txid = "b".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, ownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(setOf(ownedHex), dryRun = false, heldState)

        assertEquals(0, res.candidates.size)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun owned_dead_tx_row_is_NEVER_deleted() = runTest {
        // THE CRITICAL SAFETY INVARIANT (adversarial-review finding #2): an owned row
        // for a tx native has no record of (state -1) is the STEADY STATE of a real
        // below-scan-floor BACKEND-reconciled holding — indistinguishable from a dead
        // phantom. Deleting it destroys funds. Ownership is the sole delete authority,
        // so this owned row must survive regardless of native tx-absence.
        val txid = "d".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, ownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(setOf(ownedHex), dryRun = false, deadState)

        assertEquals(0, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun owned_spent_row_is_kept() = runTest {
        val txid = "e".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, ownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(setOf(ownedHex), dryRun = false, spentNativeState)

        assertEquals(0, res.candidates.size)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun empty_owned_set_skips_entirely() = runTest {
        // SAFETY: an empty owned-script set (wallet not loaded / native hiccup) must
        // NOT treat every row as unowned and wipe the table — it short-circuits before
        // ever reading the rows.
        val txid = "c".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, unownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(emptySet(), dryRun = false, heldState)

        assertEquals(0, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.getAllAssetUtxosNow() }
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }
}
