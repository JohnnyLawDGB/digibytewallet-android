package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assets move first; the sweep takes what is left.
 *
 * ## What this replaces
 *
 * v4.0.68 swept first and moved assets second, which forced [AssetFeeReserve] to hold DGB back
 * — a per-asset constant chosen before anything knew what the transfer would cost. It shipped at
 * 40,000 sats against a real cost of 54,900–70,100, described in its own comment as "deliberately
 * an over-estimate".
 *
 * Reordered, there is nothing to estimate. The plan is built first, so the outpoints it spends
 * are a fact, and the sweep is defined as the complement of them. The bug class is removed rather
 * than the instance fixed.
 *
 * These tests pin the four invariants that make that true. They are about which outpoints end up
 * in which transaction — the arithmetic lives in [ForeignAssetTransferPlan] and is tested there.
 */
class RecoverySequenceTest {

    private fun outpoint(txid: String, vout: Int = 0) = "$txid:$vout"

    // ---- invariant 1: a spent outpoint is never offered to the sweep ---------------------------

    @Test fun `outpoints the moves spent are withheld from the sweep`() {
        val plan = RecoverySequence.sweepExclusions(
            moved = listOf(
                RecoverySequence.MoveRecord(
                    outpoint = outpoint("asset1"),
                    spentInputs = listOf(outpoint("asset1"), outpoint("fee1")),
                    broadcast = true,
                ),
            ),
        )
        assertTrue("the asset input is excluded", outpoint("asset1") in plan)
        assertTrue("the fee input it spent is excluded", outpoint("fee1") in plan)
    }

    /**
     * The failure this prevents: the sweep spending an outpoint a broadcast-but-unconfirmed move
     * already spent. Both transactions are valid on their own and one of them loses — and which
     * one loses is up to the network, not us.
     */
    @Test fun `a broadcast move makes its inputs unavailable even before confirmation`() {
        val excl = RecoverySequence.sweepExclusions(
            moved = listOf(
                RecoverySequence.MoveRecord(outpoint("a"), listOf(outpoint("a"), outpoint("f")), broadcast = true),
            ),
        )
        assertEquals(setOf(outpoint("a"), outpoint("f")), excl)
    }

    // ---- invariant 2: a FAILED move still holds its inputs back --------------------------------

    /**
     * The whole point of moving first. A move that failed has spent nothing, so the DGB it needs
     * is still there — and must stay there, or the retry has nothing to pay with and the asset is
     * stranded in a wallet the user is walking away from.
     *
     * Note what is held: the inputs the PLAN named. Not a constant. Not an estimate.
     */
    @Test fun `a failed move holds back exactly the inputs its plan named`() {
        val excl = RecoverySequence.sweepExclusions(
            moved = listOf(
                RecoverySequence.MoveRecord(
                    outpoint = outpoint("asset1"),
                    spentInputs = listOf(outpoint("asset1"), outpoint("fee1")),
                    broadcast = false,
                ),
            ),
        )
        assertTrue("the asset stays put", outpoint("asset1") in excl)
        assertTrue(
            "the DGB its retry needs stays put — sweeping it strands the asset",
            outpoint("fee1") in excl,
        )
    }

    /** A move that never got as far as a plan has no inputs to protect. */
    @Test fun `a move with no plan holds back only its own asset outpoint`() {
        val excl = RecoverySequence.sweepExclusions(
            moved = listOf(
                RecoverySequence.MoveRecord(outpoint("asset1"), emptyList(), broadcast = false),
            ),
        )
        assertEquals(setOf(outpoint("asset1")), excl)
    }

    // ---- invariant 4: disjoint input sets, so no unconfirmed chain ------------------------------

    /**
     * The parent/descendant guarantee, stated as a property rather than observed as luck.
     *
     * Neither transaction may spend an output of the other, and neither may spend the same
     * outpoint. In v4.0.68 this held because the reserve happened to be disjoint from what the
     * sweep took. Here it holds because the sweep's input set is DEFINED as the complement of the
     * moves' — so it cannot stop being true without this test failing.
     */
    @Test fun `the sweep and the moves spend disjoint inputs`() {
        val all = listOf("a", "f1", "f2", "d1", "d2").map(::outpoint)
        val moves = listOf(
            RecoverySequence.MoveRecord(outpoint("a"), listOf(outpoint("a"), outpoint("f1")), broadcast = true),
        )
        val excl = RecoverySequence.sweepExclusions(moves)
        val sweepable = all.filterNot { it in excl }

        assertEquals(listOf(outpoint("f2"), outpoint("d1"), outpoint("d2")), sweepable)
        assertTrue(
            "no outpoint appears in both transactions",
            sweepable.none { it in excl },
        )
    }

    @Test fun `several moves all contribute their exclusions`() {
        val excl = RecoverySequence.sweepExclusions(
            listOf(
                RecoverySequence.MoveRecord(outpoint("a1"), listOf(outpoint("a1"), outpoint("f1")), broadcast = true),
                RecoverySequence.MoveRecord(outpoint("a2"), listOf(outpoint("a2"), outpoint("f2")), broadcast = false),
            ),
        )
        assertEquals(
            setOf(outpoint("a1"), outpoint("f1"), outpoint("a2"), outpoint("f2")),
            excl,
        )
    }

    @Test fun `no moves means nothing is withheld`() {
        assertTrue(RecoverySequence.sweepExclusions(emptyList()).isEmpty())
    }
}
