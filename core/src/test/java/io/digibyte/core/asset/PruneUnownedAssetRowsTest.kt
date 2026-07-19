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

    // HELD(1): the exact false-positive that inflates the count — native knows the
    // tx and the output isn't in our spentOutputs (because it isn't ours to spend).
    private val heldState: suspend (String, Int) -> Int = { _, _ -> 1 }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1
    }

    @After fun tearDown() = unmockkStatic(Log::class)

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

        // Same candidate set as the non-dry-run case — dryRun only gates the
        // delete, never the candidate detection.
        assertEquals(1, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun owned_row_never_a_candidate() = runTest {
        // A row at one of our own scripts is a real holding — never eligible,
        // regardless of its native spent-state.
        val txid = "b".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, ownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(setOf(ownedHex), dryRun = false, heldState)

        assertEquals(0, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun empty_owned_set_skips_entirely() = runTest {
        // SAFETY: an empty owned-script set (wallet not loaded / native hiccup)
        // must NOT treat every row as unowned and wipe the table — it short-
        // circuits before ever reading the rows.
        val txid = "c".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, unownedScript))

        val res = mgr.pruneUnownedAssetRowsImpl(emptySet(), dryRun = false, heldState)

        assertEquals(0, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.getAllAssetUtxosNow() }
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }
}
