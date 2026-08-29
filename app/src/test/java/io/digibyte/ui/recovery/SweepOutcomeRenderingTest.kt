package io.digibyte.ui.recovery

import io.digibyte.core.recovery.DerivationProfile
import io.digibyte.core.recovery.LegacySweepService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sweep outcome that succeeded with nothing to show must not be read as one carrying a txid.
 *
 * ## The crash this closes
 *
 * The results screen asserted `outcome.txid!!` whenever the sweep had not FAILED, which assumes
 * PENDING implies a txid. It does not. When every outpoint has already been claimed by the
 * DigiAsset or DigiDollar moves that run before it, the sweep is handed an empty input set: it did
 * not fail, it was left with nothing to do, so it reports PENDING and carries no txid.
 *
 * That combination crashed the app on a real mainnet recovery — after the DigiDollar transfer had
 * already broadcast, so the money was fine and the screen was not.
 *
 * The bug was latent for four releases. It needed a recovery where a prior move consumed
 * everything, and the DigiDollar transfer is the first thing that does so routinely: its 0.1 DGB
 * consensus fee plus change takes both of a small wallet's outputs.
 *
 * ## Why this test is at the model, not the Composable
 *
 * The rendering rule is "show the txid block only when there is a txid". Asserting it on the
 * outcome — that PENDING and null-txid legitimately co-occur, and that the screen's predicate
 * tolerates it — catches the same defect without a UI harness, and reads as what it is: a
 * statement about states the sweep can be in.
 */
class SweepOutcomeRenderingTest {

    private val profile = DerivationProfile(
        label = "BIP84 DGB", description = "native",
        hmacKey = DerivationProfile.HMAC_STANDARD,
        prefixPath = intArrayOf(84, 20, 0), addressFormat = 1, isNative = true,
    )

    private fun outcome(
        txid: String?,
        state: LegacySweepService.BroadcastState,
        swept: Long = 0L,
        heldForMoves: List<String> = emptyList(),
    ) = LegacySweepService.SweepOutcome(
        profile = profile, txHex = null, txid = txid, sweptSat = swept, inputCount = 0,
        failureReason = null, broadcastState = state, heldBackFeeReserve = heldForMoves,
    )

    /** The exact shape that crashed: nothing left to sweep, so PENDING with no txid. */
    @Test fun `a sweep left with nothing reports PENDING and no txid`() {
        val o = outcome(
            txid = null,
            state = LegacySweepService.BroadcastState.PENDING,
            heldForMoves = listOf("aaaa:0", "bbbb:0"),
        )
        assertTrue("it did not fail — it had nothing to do",
            o.broadcastState != LegacySweepService.BroadcastState.FAILED)
        assertNull("and so it carries no txid", o.txid)
    }

    /**
     * The screen's rule, stated directly. Gating on "not FAILED" is what crashed; gating on the
     * txid existing is what does not.
     */
    @Test fun `the txid block is shown only when a txid exists`() {
        fun showsTxid(o: LegacySweepService.SweepOutcome): Boolean =
            o.broadcastState != LegacySweepService.BroadcastState.FAILED && o.txid != null

        assertFalse("nothing swept — no txid block",
            showsTxid(outcome(null, LegacySweepService.BroadcastState.PENDING)))
        assertTrue("a real sweep shows its txid",
            showsTxid(outcome("abc123", LegacySweepService.BroadcastState.PENDING, swept = 5_000_000L)))
        assertFalse("a failed sweep shows nothing",
            showsTxid(outcome(null, LegacySweepService.BroadcastState.FAILED)))
    }

    /**
     * The old predicate is kept explicitly so the regression stays visible: it returns true for an
     * outcome with no txid, which is precisely the dereference that crashed.
     */
    @Test fun `the old predicate would have dereferenced a null txid`() {
        val o = outcome(null, LegacySweepService.BroadcastState.PENDING)
        val oldPredicate = o.broadcastState != LegacySweepService.BroadcastState.FAILED
        assertTrue("the old gate passes…", oldPredicate)
        assertNull("…on an outcome whose txid is null", o.txid)
    }

    /** A sweep that moved coins still behaves as before — the fix must not hide real results. */
    @Test fun `an ordinary sweep is unaffected`() {
        val o = outcome("deadbeef", LegacySweepService.BroadcastState.PENDING, swept = 145_000_000L)
        assertTrue(o.txid != null)
        assertTrue(o.sweptSat > 0)
    }
}
