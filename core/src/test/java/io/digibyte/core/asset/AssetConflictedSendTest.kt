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
import org.junit.Before
import org.junit.Test

/**
 * A send that got stuck and was re-sent leaves TWO asset-change outputs behind: the live
 * one, and one belonging to the abandoned attempt. The abandoned transaction is still in
 * the native wallet's transaction set — it is merely CONFLICTED, because the replacement
 * spent its input — so `outpointSpentState` answered HELD for its change output and the
 * wallet counted the change twice.
 *
 * Measured live on an S25 Ultra: asset La3t7Jdv… ("Chang Pablo Escobar", supply 10). One
 * unit was sent, the attempt stuck, the send was repeated. Chain and DigiAsset Core agree
 * the wallet holds 9. The wallet reported `La3t7Jdv=18(4u)` — 9 + 9 plus the two
 * zero-quantity DGB-change rows.
 *
 * The signal that separates the two is VALIDITY, not spent-ness: nothing ever spent the
 * abandoned attempt's change, so a spentOutputs lookup can never see it. CONFLICTED is the
 * state native reports for an output of a transaction `BRWalletTransactionIsValid` rejects.
 */
class AssetConflictedSendTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }
    private val owned = setOf(ownedHex)

    private fun row(txid: String, qty: Long, source: String = AssetSource.NATIVE) = UtxoEntity(
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

    @Test fun a_conflicted_transactions_output_is_not_held() {
        assertEquals(
            false,
            mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.CONFLICTED),
        )
    }

    /**
     * BACKEND provenance exists to rescue an outpoint native has simply never SEEN — a
     * below-scan-floor holding restored by reconcile. It must not rescue a conflicted one:
     * there native has looked and given a definite answer.
     */
    @Test fun backend_provenance_does_not_rescue_a_conflicted_output() {
        assertEquals(
            false,
            mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, AssetSpentState.CONFLICTED),
        )
    }

    /**
     * CONFLICTED must not be written into the row's persisted `spent` flag. Nothing spent
     * that output, and while the replacement is itself unconfirmed the abandoned attempt
     * could still win. Leaving the flag alone keeps the display gate the only thing acting
     * on it, and the next reconcile flips it back once the transaction is valid again.
     */
    @Test fun conflicted_leaves_the_persisted_spent_flag_alone() {
        assertNull(decideAssetSpent(AssetSpentState.CONFLICTED))
    }

    /** The live shape: the abandoned attempt's 9 and the real 9 both sit in the table, and
     *  only the real one may be counted. */
    @Test fun the_stuck_attempts_change_is_excluded_from_the_balance() = runTest {
        val dead = "de00000000000000000000000000000000000000000000000000000000000000"
        val live = "11ve000000000000000000000000000000000000000000000000000000000000"
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(dead, 9L), row(live, 9L))

        val balances = mgr.computeHeldAssetBalancesImpl(owned) { txid, _ ->
            if (txid == dead) AssetSpentState.CONFLICTED else AssetSpentState.HELD
        }

        assertEquals(9L, balances?.get("La3t7Jdv")?.quantity)
        assertEquals(1, balances?.get("La3t7Jdv")?.utxoCount)
    }

    /** The existing states keep their meanings — this must not become a blanket exclusion. */
    @Test fun the_other_states_are_unchanged() {
        assertEquals(true, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.HELD))
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.NATIVE, AssetSpentState.SPENT))
        // 4.0.39: no-record no longer counts, whatever the provenance — see
        // AssetHeldMeansNativeHoldsItTest for why the rescue had to go.
        assertEquals(false, mgr.isHeldForDisplay(ownedHex, owned, AssetSource.BACKEND, AssetSpentState.UNDETECTED))
        assertEquals(true, decideAssetSpent(AssetSpentState.SPENT))
        assertEquals(false, decideAssetSpent(AssetSpentState.HELD))
        assertNull(decideAssetSpent(AssetSpentState.UNDETECTED))
    }
}
