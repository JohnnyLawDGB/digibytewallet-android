package io.digibyte.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the "newest transaction always shows 1 confirmation on open" defect.
 *
 * The RED ARM below re-implements the OLD algorithm inline rather than calling [ChainTipPolicy].
 * That is deliberate. A test written alongside its fix will happily assert whatever the fix does
 * and prove nothing; the only way this file can demonstrate a real change is for the old
 * arithmetic to live here in full and be shown to produce the wrong answer on the same inputs the
 * new policy gets right.
 *
 * Old algorithm, from WalletViewModel before this change:
 *     var currentHeight = NativeBridge.getLastBlockHeight()     // 0 at open
 *     if (maxTxHeight > currentHeight) currentHeight = maxTxHeight
 *     confs = if (txHeight > 0 && currentHeight >= txHeight)
 *                 (currentHeight - txHeight + 1).toInt() else 0
 */
class ChainTipPolicyTest {

    private val newestTxHeight = 23_800_000L
    private val olderTxHeight = 23_799_000L
    private val realTip = 23_813_000L

    /** The old algorithm, reproduced exactly. Not wired to [ChainTipPolicy] on purpose. */
    private fun oldConfirmations(txHeight: Long, nativeTip: Long, maxTxHeight: Long): Int {
        var currentHeight = nativeTip
        if (maxTxHeight > currentHeight) currentHeight = maxTxHeight
        return if (txHeight > 0 && currentHeight >= txHeight) {
            (currentHeight - txHeight + 1).toInt()
        } else {
            0
        }
    }

    // ---- RED ARM: the defect, on the old algorithm -------------------------------------------

    @Test
    fun `RED - old algorithm reports exactly 1 confirmation for the newest tx at open`() {
        // At open the native tip is not loaded yet.
        val confs = oldConfirmations(
            txHeight = newestTxHeight,
            nativeTip = 0L,
            maxTxHeight = newestTxHeight,
        )
        assertEquals(
            "the defect: flooring the tip to the newest tx's own height forces " +
                "maxTxHeight - maxTxHeight + 1",
            1,
            confs,
        )
    }

    @Test
    fun `RED - the old artifact is invisible on older txs, which is why it shipped`() {
        // Older transactions get a plausible-looking count off the same bad floor, so only the
        // newest one reads obviously wrong. That is what made this look cosmetic for so long.
        val confs = oldConfirmations(
            txHeight = olderTxHeight,
            nativeTip = 0L,
            maxTxHeight = newestTxHeight,
        )
        assertEquals(1001, confs)
        assertTrue("an older tx looks plausible, so the bug hides", confs > 1)
    }

    // ---- GREEN: the new policy on the same inputs ---------------------------------------------

    @Test
    fun `a persisted tip gives the newest tx its true count at open`() {
        val tip = ChainTipPolicy.effectiveChainTip(
            nativeTip = 0L,                 // not loaded yet
            persistedTip = realTip,         // remembered from last session
            maxTxHeight = newestTxHeight,
        )
        val confs = ChainTipPolicy.confirmationsFor(newestTxHeight, tip)

        assertEquals("must measure against the remembered tip, not the tx's own height", 13_001, confs)
        assertTrue("the whole point: no longer the '1 confirmation' artifact", confs > 1)
    }

    @Test
    fun `the persisted tip wins while the native tip is still climbing from a checkpoint`() {
        // Mid-catch-up the native tip is legitimately BEHIND last session's tip.
        val tip = ChainTipPolicy.effectiveChainTip(
            nativeTip = 23_500_000L,
            persistedTip = realTip,
            maxTxHeight = newestTxHeight,
        )
        assertEquals(realTip, tip)
    }

    @Test
    fun `a live native tip ahead of the persisted one wins`() {
        val ahead = realTip + 5_000L
        val tip = ChainTipPolicy.effectiveChainTip(
            nativeTip = ahead,
            persistedTip = realTip,
            maxTxHeight = newestTxHeight,
        )
        assertEquals(ahead, tip)
    }

    // ---- The one launch where the old artifact survives, asserted so it stays bounded ----------

    @Test
    fun `KNOWN GAP - first launch after upgrade has no persisted tip and still reads 1`() {
        // Nothing has ever been persisted, so maxTxHeight is all there is. This reproduces the old
        // behaviour for exactly one launch, and is asserted rather than left undiscovered: if this
        // ever needs to change, it should change deliberately.
        val tip = ChainTipPolicy.effectiveChainTip(
            nativeTip = 0L,
            persistedTip = ChainTipPolicy.UNKNOWN_TIP,
            maxTxHeight = newestTxHeight,
        )
        assertEquals(
            "one launch only — from the first persisted tip onward this branch is unreachable",
            1,
            ChainTipPolicy.confirmationsFor(newestTxHeight, tip),
        )
    }

    // ---- Guards ------------------------------------------------------------------------------

    @Test
    fun `a pending tx is unconfirmed, never 1`() {
        // The UI normalises native's TX_UNCONFIRMED sentinel to 0 before this is called.
        assertEquals(0, ChainTipPolicy.confirmationsFor(txHeight = 0L, chainTip = realTip))
    }

    @Test
    fun `a tip below the tx reports unconfirmed rather than a negative or a clamped 1`() {
        assertEquals(0, ChainTipPolicy.confirmationsFor(newestTxHeight, chainTip = olderTxHeight))
    }

    @Test
    fun `an unknown tip reports unconfirmed`() {
        assertEquals(0, ChainTipPolicy.confirmationsFor(newestTxHeight, ChainTipPolicy.UNKNOWN_TIP))
    }

    @Test
    fun `the TX_UNCONFIRMED sentinel can never become the tip`() {
        val sentinel = Int.MAX_VALUE.toLong()
        assertFalse(
            "persisting the sentinel would park the tip at ~2.1 billion",
            ChainTipPolicy.shouldPersistTip(candidate = sentinel, stored = realTip),
        )
        assertEquals(
            "and it must not be honoured as a tip if it somehow arrives",
            realTip,
            ChainTipPolicy.effectiveChainTip(sentinel, realTip, newestTxHeight),
        )
    }

    @Test
    fun `the persisted tip only moves forward`() {
        assertTrue(ChainTipPolicy.shouldPersistTip(candidate = realTip, stored = 0L))
        assertTrue(ChainTipPolicy.shouldPersistTip(candidate = realTip + 1, stored = realTip))
        assertFalse(
            "a mid-catch-up or post-reorg regression must not lower the remembered tip",
            ChainTipPolicy.shouldPersistTip(candidate = 23_500_000L, stored = realTip),
        )
        assertFalse(ChainTipPolicy.shouldPersistTip(candidate = 0L, stored = realTip))
        assertFalse(ChainTipPolicy.shouldPersistTip(candidate = -1L, stored = realTip))
    }
}
