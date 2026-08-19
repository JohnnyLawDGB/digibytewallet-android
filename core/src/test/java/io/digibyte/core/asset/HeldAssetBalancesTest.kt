package io.digibyte.core.asset

import android.util.Log
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the NATIVE-AUTHORITATIVE display-balance computation
 * ([AssetManager.isHeldForDisplay] / [AssetManager.computeHeldAssetBalancesImpl])
 * — the fix for the DigiAsset over-count (CHANG showing 21 for a supply of 10).
 *
 * The naive DB SUM over spent=0 rows over-counts because the Room UTXO cache
 * accumulates phantoms. This recomputes the shown balance from the sovereign
 * native view: a row counts only if we OWN its address AND native holds it live,
 * with provenance disambiguating the native-has-no-record case. DISPLAY ONLY — no
 * deletion — so this is the safe way to get the right number.
 *
 * Drives the testable *Impl overloads for the same host-JVM/NativeBridge reason as
 * [PruneUnownedAssetRowsTest]: the public entry points call buildOwnedScriptHexes()/
 * NativeBridge which load `core-lib`.
 */
class HeldAssetBalancesTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val unownedScript = byteArrayOf(9, 9, 9)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }
    private val owned = setOf(ownedHex)

    private fun row(
        txid: String, script: ByteArray, assetId: String, qty: Long,
        spent: Boolean = false, source: String = AssetSource.NATIVE,
        // Rows carry the CONFIRMING height the backend reported (`blockHeight =
        // u.confirmedHeight`). Height 0 means "never confirmed", which is now the signal
        // that separates a below-floor holding from a dead broadcast — so the default
        // fixture has to be a confirmed row, not a 0.
        blockHeight: Long = 700_000L,
    ) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = 6000, blockHeight = blockHeight,
        isAsset = true, assetId = assetId, assetQuantity = qty, spent = spent, assetSource = source)

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ── isHeldForDisplay: the per-row predicate, every branch ──

    @Test fun predicate_not_owned_is_never_held() {
        // A recipient marker from a transfer WE sent — not our address.
        assertEquals(false, mgr.isHeldForDisplay("9999", owned, AssetSource.NATIVE, 1))
        assertEquals(false, mgr.isHeldForDisplay("9999", owned, AssetSource.BACKEND, 1))
    }

    @Test fun predicate_owned_and_native_held_is_held() {
        assertEquals(true, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, 1))
    }

    @Test fun predicate_owned_but_native_spent_is_not_held() {
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, 0))
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, 0))
    }

    @Test fun predicate_owned_native_unknown_native_source_is_dead_send_not_held() {
        // The 8-unit CHANG phantom: native detected then LOST the tx (dropped send).
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, -1))
    }

    @Test fun predicate_owned_but_native_has_no_record_does_not_count() {
        // Was `..._is_below_floor_holding`, asserting BACKEND provenance rescued a
        // no-record outpoint. That rule is what let a phantom (79b27063eab3:2, 8 units)
        // inflate a live wallet from 9 to 17 — a phantom and a below-floor holding are
        // indistinguishable from here, so neither is counted now. See
        // AssetHeldMeansNativeHoldsItTest.
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, -1))
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, -1))
    }

    @Test fun predicate_probe_error_does_not_count() {
        // -99 = outpointSpentState threw. 4.0.39: a failed probe is not an answer, and
        // provenance no longer rescues one — a BACKEND row is not evidence of holding.
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, -99))
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, -99))
    }

    // ── computeHeldAssetBalancesImpl: aggregation over the real CHANG shape ──

    @Test fun chang_shape_sums_to_true_supply() = runTest {
        // Reconstruction of the on-device CHANG dry-run: 21 across 9 rows resolves to
        // the true holding of 10 across 2 UTXOs.
        val a = "La3"
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row("99" + "a".repeat(62), ownedScript, a, 9),                       // owned + HELD → 9
            row("30" + "a".repeat(62), ownedScript, a, 1),                       // owned + HELD → 1
            row("79" + "a".repeat(62), ownedScript, a, 8),                       // owned + dead NATIVE → 0
            row("79" + "b".repeat(62), unownedScript, a, 2),                     // not-owned recipient → 0
            row("15" + "a".repeat(62), ownedScript, a, 10, spent = true),        // Room-spent → skipped
        )
        val state: suspend (String, Int) -> Int = { txid, _ ->
            when {
                txid.startsWith("99") || txid.startsWith("30") -> 1   // HELD
                else -> -1                                            // dead / not-reached
            }
        }

        val held = mgr.computeHeldAssetBalancesImpl(owned, state)!!

        assertEquals(10L, held[a]!!.quantity)
        assertEquals(2, held[a]!!.utxoCount)
    }

    @Test fun a_row_native_has_no_record_of_is_not_counted() = runTest {
        val a = "La6"
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row("aa" + "a".repeat(62), ownedScript, a, 5, source = AssetSource.BACKEND),
        )
        val state: suspend (String, Int) -> Int = { _, _ -> -1 }   // native never saw it

        val held = mgr.computeHeldAssetBalancesImpl(owned, state)!!

        assertEquals(null, held[a])
    }

    @Test fun empty_owned_set_returns_null_for_naive_fallback() = runTest {
        // Ownership unknowable (wallet not loaded) → null so the caller shows the naive
        // SUM rather than blanking every asset to 0.
        val held = mgr.computeHeldAssetBalancesImpl(emptySet()) { _, _ -> 1 }
        assertNull(held)
    }

    @Test fun fully_phantom_asset_resolves_to_zero() = runTest {
        // An asset whose every row is a phantom (all not-owned or dead) sums to 0 and
        // is dropped by the caller's quantity>0 filter.
        val a = "LaPhantom"
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row("79" + "b".repeat(62), unownedScript, a, 5),                 // not-owned
            row("79" + "a".repeat(62), ownedScript, a, 3),                   // owned dead NATIVE
        )
        val state: suspend (String, Int) -> Int = { _, _ -> -1 }

        val held = mgr.computeHeldAssetBalancesImpl(owned, state)!!

        assertTrue(held[a] == null || held[a]!!.quantity == 0L)
    }
}
