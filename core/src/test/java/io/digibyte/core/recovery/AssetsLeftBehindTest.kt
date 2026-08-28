package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which assets the result screen may describe as "left behind".
 *
 * ## Observed on mainnet, 2026-08-28
 *
 * A recovery that moved both assets successfully still printed:
 *
 *     2 left behind — DigiAssets
 *     "…they were left in the old wallet along with enough DGB to move them later."
 *
 * two lines above "2 of 2 assets moved". Both statements came from the same run and only one
 * was true.
 *
 * The cause is a word that changed meaning under the reorder. [SweepPartition] excludes
 * asset-bearing outpoints from the sweep — correct, spending one as plain DGB destroys the asset
 * — and the screen read "excluded from the sweep" as "left behind". Under the old order those
 * were the same thing. Since assets now move BEFORE the sweep, they are not.
 *
 * Someone reading that on a wallet they have just emptied concludes the recovery half-failed and
 * goes looking for coins that already arrived. It is the same defect as the passphrase copy: the
 * words describe a world the code no longer lives in.
 */
class AssetsLeftBehindTest {

    private fun move(outpoint: String, moved: Boolean) =
        ForeignAssetTransferService.Move(
            outpoint = outpoint,
            units = 1L,
            txid = if (moved) "tx-$outpoint" else null,
            failureReason = if (moved) null else "broadcast failed",
        )

    private val a = "aaaa:0"
    private val b = "bbbb:0"

    @Test fun `an asset that moved is not left behind`() {
        val left = RecoverySequence.assetsLeftBehind(
            assetBearing = listOf(a, b),
            moves = listOf(move(a, moved = true), move(b, moved = true)),
        )
        assertTrue("both moved, so nothing was left behind — got $left", left.isEmpty())
    }

    @Test fun `an asset that failed to move IS left behind`() {
        val left = RecoverySequence.assetsLeftBehind(
            assetBearing = listOf(a, b),
            moves = listOf(move(a, moved = true), move(b, moved = false)),
        )
        assertEquals(listOf(b), left)
    }

    /**
     * An asset-bearing outpoint the move phase never produced a record for was not attempted at
     * all. It is still sitting in the old wallet, so it is genuinely left behind — and saying so
     * is the fail-closed direction: over-reporting sends someone to look at a wallet that has
     * something in it, under-reporting tells them it is empty when it is not.
     */
    @Test fun `an asset the move phase never reported is left behind`() {
        val left = RecoverySequence.assetsLeftBehind(
            assetBearing = listOf(a, b),
            moves = listOf(move(a, moved = true)),
        )
        assertEquals(listOf(b), left)
    }

    @Test fun `no assets means nothing to report`() {
        assertTrue(RecoverySequence.assetsLeftBehind(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `order is preserved so the list reads as the wallet does`() {
        val left = RecoverySequence.assetsLeftBehind(
            assetBearing = listOf(a, b, "cccc:0"),
            moves = listOf(move(b, moved = true)),
        )
        assertEquals(listOf(a, "cccc:0"), left)
    }
}
