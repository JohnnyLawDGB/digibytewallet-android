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
 * A send that was broadcast, never confirmed, and then dropped from the wallet's
 * transaction set leaves an asset-change row behind that NOTHING could clear.
 *
 * Measured on an S25 Ultra running v4.0.36: asset La3t7Jdv… displayed 17 against a
 * chain truth of 8. The extra 9 belongs to `eacb2f6de366c653…`, which the node has never
 * heard of and which the wallet itself reports as
 * `Dandelion recovery: … not in wallet tx set — can't re-publish`.
 *
 * Three gaps interlocked to keep it alive:
 *  1. `clearDeadAssetSend` reads the dead tx's outputs BEFORE removing it, so once the tx
 *     is gone from the wallet there is nothing left to enumerate and the cleanup can never
 *     run for it.
 *  2. The display gate sees UNDETECTED (no record of the funding tx) and trusts the row
 *     because its provenance is BACKEND — a rule written for below-scan-floor holdings.
 *  3. The prune that would delete it only inspects NATIVE rows.
 *
 * The signal that separates the two cases is CONFIRMATION, not provenance: a below-floor
 * holding was confirmed on-chain and carries a real height, while a dead broadcast never
 * confirmed and carries height 0. Nothing else distinguishes them sovereignly.
 */
class AssetDeadBroadcastRowTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }
    private val owned = setOf(ownedHex)

    private fun row(txid: String, qty: Long, height: Long, source: String) = UtxoEntity(
        txid = txid, vout = 2, scriptPubKey = ownedScript, satoshis = 6000,
        blockHeight = height, isAsset = true, assetId = "La3t7Jdv", assetQuantity = qty,
        spent = false, assetSource = source,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    /**
     * CURRENT SHIPPED BEHAVIOUR, and deliberately not the behaviour I first wanted.
     *
     * v4.0.37 made this `false` on the theory that a never-confirmed BACKEND row is a dead
     * broadcast. On the live wallet that hid rows worth 8 units while leaving the phantom
     * in place, so it was reverted: hiding real holdings is a worse failure than displaying
     * a phantom one. The per-row diagnostic added in 4.0.38 exists to establish what these
     * rows actually are before the rule is changed again.
     */
    @Test fun an_undetected_backend_row_is_still_counted_pending_diagnosis() {
        assertEquals(true, mgr.isHeldForDisplay(
            ownedHex, owned, AssetSource.BACKEND, AssetSpentState.UNDETECTED, everConfirmed = false,
        ))
    }

    /**
     * The case the BACKEND rule exists for MUST survive: a holding that confirmed on-chain
     * below the wallet's scan floor, which native therefore cannot see. Killing this would
     * hide real assets, which is worse than displaying a phantom.
     */
    @Test fun a_confirmed_below_floor_holding_is_still_held() {
        assertEquals(true, mgr.isHeldForDisplay(
            ownedHex, owned, AssetSource.BACKEND, AssetSpentState.UNDETECTED, everConfirmed = true,
        ))
    }

    /** A never-confirmed row native still HOLDS is a legitimate 0-conf receive — keep it. */
    @Test fun a_never_confirmed_row_native_still_holds_is_held() {
        assertEquals(true, mgr.isHeldForDisplay(
            ownedHex, owned, AssetSource.NATIVE, AssetSpentState.HELD, everConfirmed = false,
        ))
    }

    /** The probe throwing is not evidence of death — treat it like UNDETECTED. */
    @Test fun a_probe_error_on_a_confirmed_row_is_still_held() {
        assertEquals(true, mgr.isHeldForDisplay(
            ownedHex, owned, AssetSource.BACKEND, AssetSpentState.PROBE_ERROR, everConfirmed = true,
        ))
    }

    /**
     * End to end on the Ultra's shape. With the display rule reverted, the dead row is
     * still counted — this pins the CURRENT total so a future change to the rule shows up
     * here as an intentional diff rather than a silent one. The prune below is what
     * actually removes such a row.
     */
    @Test fun the_dead_broadcasts_change_is_still_counted_until_it_is_pruned() = runTest {
        val dead = "eacb2f6de366c653869a46f93f7dd20daa69f8f9623f05b1e194da869bac570d"
        val live = "3afac554fe4c35b078b35e65acff74221ff7bb2022eaf66ed31bc81147caab06"
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row(dead, 9L, height = 0L, source = AssetSource.BACKEND),
            row(live, 8L, height = 24_050_000L, source = AssetSource.NATIVE),
        )

        val balances = mgr.computeHeldAssetBalancesImpl(owned) { txid, _ ->
            if (txid == dead) AssetSpentState.UNDETECTED else AssetSpentState.HELD
        }

        assertEquals(17L, balances?.get("La3t7Jdv")?.quantity)
        assertEquals(2, balances?.get("La3t7Jdv")?.utxoCount)
    }

    /** Hiding it is not enough — the row must actually be deleted, whatever its provenance,
     *  or it lingers in the table forever waiting to be miscounted again. */
    @Test fun a_never_confirmed_orphan_row_is_pruned_regardless_of_provenance() = runTest {
        val dead = "eacb2f6de366c653869a46f93f7dd20daa69f8f9623f05b1e194da869bac570d"
        coEvery { utxoDao.getAllAssetUtxosNow() } returns
            listOf(row(dead, 9L, height = 0L, source = AssetSource.BACKEND))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1

        var deleted = 0
        repeat(2) {
            deleted = mgr.pruneDeadBroadcastRowsImpl(isTxGone = { true })
        }

        assertEquals(1, deleted)
        coVerify { utxoDao.deleteAssetUtxo(dead, 2) }
    }

    /** A confirmed row is never pruned by this pass, even if native can't see its tx —
     *  that is the below-floor holding again. */
    @Test fun a_confirmed_row_is_never_pruned_by_this_pass() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns
            listOf(row("aa".repeat(32), 5L, height = 23_000_000L, source = AssetSource.BACKEND))

        repeat(2) { mgr.pruneDeadBroadcastRowsImpl(isTxGone = { true }) }

        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    /** A tx the wallet still knows is not dead — no deletion, however many passes run. */
    @Test fun a_row_whose_tx_is_still_present_is_never_pruned() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns
            listOf(row("bb".repeat(32), 9L, height = 0L, source = AssetSource.BACKEND))

        repeat(2 + 2) { mgr.pruneDeadBroadcastRowsImpl(isTxGone = { false }) }

        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }
}
