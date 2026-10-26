package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
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
 * Asset-row maintenance without a third party.
 *
 * The old `refreshAssetUtxosFromNetwork` POSTed the wallet's ENTIRE address set to an
 * indexer in 500-address chunks — the same disclosure the restore spec exists to remove,
 * on the ordinary path rather than only during restore. It has also been dead for some
 * time: the routes it calls (`/api/assets/unspent` and friends) 404, because the backend
 * serves `/api/digiassets/…`. Because it bailed at the first failed chunk, the two useful
 * things it did afterwards — the sovereign phantom prune and the spent-state reconcile —
 * never ran at all.
 *
 * Those two survive here as a local-only pass: ownership judged against the wallet's own
 * address set, spent-ness against the native wallet. No network, no address disclosure.
 */
class AssetLocalReconcileTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val foreignScript = byteArrayOf(9, 9, 9)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }

    private fun row(txid: String, script: ByteArray, qty: Long = 5L) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = 6000, blockHeight = 0,
        isAsset = true, assetId = "asset1", assetQuantity = qty, spent = false,
        assetSource = AssetSource.NATIVE,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    /** A row at an address we do not own is a phantom — chiefly the recipient marker of a
     *  send we made — and is deleted. */
    @Test fun prunes_rows_at_addresses_we_do_not_own() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns
            listOf(row("aa", ownedScript), row("bb", foreignScript))
        // deleteAssetUtxo is scoped to is_asset = 1 and reports rows actually removed, which
        // is what the count reflects — an owned DGB output at the same outpoint is never hit.
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1

        val pruned = mgr.reconcileAssetRowsLocallyImpl(
            ownedScriptHexes = setOf(ownedHex),
            spentState = { _, _ -> AssetSpentState.HELD },
        )

        assertEquals(1, pruned)
        coVerify(exactly = 1) { utxoDao.deleteAssetUtxo("bb", 0) }
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo("aa", 0) }
    }

    /** An empty owned set means the lookup failed, not that we own nothing. Deleting on
     *  that reading would erase real holdings, so the pass does nothing at all. */
    @Test fun an_unknown_owned_set_deletes_nothing() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row("bb", foreignScript))

        val pruned = mgr.reconcileAssetRowsLocallyImpl(
            ownedScriptHexes = emptySet(),
            spentState = { _, _ -> AssetSpentState.HELD },
        )

        assertEquals(0, pruned)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    /** A row the backend returned but whose script we could not derive is a real holding we
     *  cannot judge — never a phantom. */
    @Test fun a_row_with_no_script_is_never_pruned() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row("cc", ByteArray(0)))

        val pruned = mgr.reconcileAssetRowsLocallyImpl(
            ownedScriptHexes = setOf(ownedHex),
            spentState = { _, _ -> AssetSpentState.HELD },
        )

        assertEquals(0, pruned)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    /** The spent-state reconcile runs in the same pass — it is the only thing that can
     *  un-set a stale spent flag, and a stale `spent = true` HIDES a real holding. */
    @Test fun reconciles_the_spent_flag_from_the_native_wallet() = runTest {
        val held = row("aa", ownedScript).copy(spent = true)
        val spent = row("dd", ownedScript)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(held, spent)

        mgr.reconcileAssetRowsLocallyImpl(
            ownedScriptHexes = setOf(ownedHex),
            spentState = { txid, _ ->
                if (txid == "aa") AssetSpentState.HELD else AssetSpentState.SPENT
            },
        )

        coVerify(exactly = 1) { utxoDao.setSpent("aa", 0, false) }
        coVerify(exactly = 1) { utxoDao.setSpent("dd", 0, true) }
    }

    /** UNDETECTED means the funding tx has not synced yet. Writing a flag on that guess is
     *  how a mid-sync wallet hides a real holding. */
    @Test fun an_undetected_outpoint_leaves_its_flag_alone() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row("aa", ownedScript))

        mgr.reconcileAssetRowsLocallyImpl(
            ownedScriptHexes = setOf(ownedHex),
            spentState = { _, _ -> AssetSpentState.UNDETECTED },
        )

        coVerify(exactly = 0) { utxoDao.setSpent(any(), any(), any()) }
    }

    /** While the wallet is still re-deriving its history (a rescan from a floor), its address
     *  set covers only the fresh derivation window. Ownership then says nothing about a row at a
     *  higher index, so the ownership prune waits for the maintenance gate; the spent-state
     *  pass still runs. */
    @Test fun ownership_prune_waits_while_not_allowed() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row("bb", foreignScript))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1

        val pruned = mgr.reconcileAssetRowsLocallyImpl(
            ownedScriptHexes = setOf(ownedHex),
            spentState = { _, _ -> AssetSpentState.SPENT },
            pruneUnowned = false,
        )

        assertEquals(0, pruned)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
        coVerify(exactly = 1) { utxoDao.setSpent("bb", 0, true) }
    }

    /** Callers outside the service's gated maintenance tick never prune by ownership. */
    @Test fun reconcile_callers_outside_the_gated_tick_do_not_prune_by_ownership() {
        val src = java.io.File("src/main/java/app/aroundtheblock/wallet/core/reconcile/ChainReconciliationService.kt").readText()
        val calls = Regex("""reconcileAssetRowsLocally\(([^)]*)\)""").findAll(src).map { it.groupValues[1].trim() }.toList()
        assertEquals("scanner is blind", 2, calls.size)
        for (args in calls) assertEquals("pruneUnowned = false", args)
    }
}
