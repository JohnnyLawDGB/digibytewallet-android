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
import org.junit.Before
import org.junit.Test

/**
 * The displayed asset balance is what the wallet can SEE it holds: an outpoint counts iff
 * the native wallet holds it. No provenance heuristic.
 *
 * Two earlier attempts tried to separate a phantom from a below-scan-floor holding by
 * inference — conflicted status (4.0.37), then confirming height (4.0.38) — and both were
 * wrong, because when native has no record of the funding transaction the two cases are
 * genuinely indistinguishable. The per-row diagnostic on a live S25 Ultra settled it:
 *
 *   row 79b27063eab3:2 qty=8 h=0 src=BACKEND state=-1  -> phantom
 *   row 79b27063eab3:3 qty=0 h=0 src=BACKEND state=-1  -> phantom sibling
 *   row 30872f2c2462:0 qty=1 h=0 src=NATIVE  state=1   -> real
 *   row 3afac554fe4c:2 qty=8 h=0 src=NATIVE  state=1   -> real
 *
 * Chain truth for that wallet: 8 at dgb1qrgnc0n… plus 1 at dgb1qcxjvnzt…, both its own
 * addresses — 9 held, displayed as 17.
 */
class AssetHeldMeansNativeHoldsItTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }
    private val owned = setOf(ownedHex)

    private fun row(txid: String, qty: Long, source: String) = UtxoEntity(
        txid = txid, vout = 2, scriptPubKey = ownedScript, satoshis = 6000, blockHeight = 0,
        isAsset = true, assetId = "La3t7Jdv", assetQuantity = qty, spent = false,
        assetSource = source,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun native_holds_it_so_it_counts() {
        assertEquals(true, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.HELD))
        assertEquals(true, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, AssetSpentState.HELD))
    }

    /** The rule that let phantoms in: provenance no longer rescues a no-record outpoint. */
    @Test fun no_record_does_not_count_whatever_the_provenance() {
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, AssetSpentState.UNDETECTED))
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.UNDETECTED))
    }

    /** A failed probe is not evidence of holding either — it is simply not an answer. */
    @Test fun a_probe_error_does_not_count() {
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, AssetSpentState.PROBE_ERROR))
    }

    @Test fun spent_and_conflicted_still_do_not_count() {
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.SPENT))
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.CONFLICTED))
    }

    /** An address we do not own never counts, whatever native says about the outpoint. */
    @Test fun an_address_we_do_not_own_never_counts() {
        assertEquals(false, mgr.isHeldForDisplay("dead", owned, AssetSource.NATIVE, AssetSpentState.HELD))
    }

    /** The live wallet, end to end: 9 held, not 17. */
    @Test fun the_ultra_wallet_reads_nine() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row("79b27063eab3".padEnd(64, '0'), 8L, AssetSource.BACKEND),   // phantom
            row("3afac554fe4c".padEnd(64, '0'), 8L, AssetSource.NATIVE),    // real change
            row("30872f2c2462".padEnd(64, '0'), 1L, AssetSource.NATIVE),    // real, sent to self
        )

        val balances = mgr.computeHeldAssetBalancesImpl(owned) { txid, _ ->
            if (txid.startsWith("79b27063")) AssetSpentState.UNDETECTED else AssetSpentState.HELD
        }

        assertEquals(9L, balances?.get("La3t7Jdv")?.quantity)
        assertEquals(2, balances?.get("La3t7Jdv")?.utxoCount)
    }
}
