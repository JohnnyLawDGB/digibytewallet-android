package app.aroundtheblock.wallet.ui.recovery

import app.aroundtheblock.wallet.core.recovery.ForeignAssetTransferService.FanOut
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K1-RECOVERY round 2: a DGB split that was refused or failed used to be dropped by
 * RecoverFundsViewModel (it handled only [FanOut.Broadcast]), so a user whose assets could not move
 * for want of DGB was never told why. The log line no longer carries the refusal's detail, so the
 * screen is now the only place it is said.
 */
class AssetFanOutNoticeTest {

    /** K1-RECOVERY r3: the same run sweeps the plain DGB, so the user is shown the FULL amount the old
     *  wallet must hold, never the shortfall (topping up by the shortfall is refused again). */
    @Test
    fun `a split refused for want of DGB shows the full requirement, not the shortfall`() {
        val n = assetFanOutNotice(FanOut.Refused(shortfallSat = 12_345L, detail = "x", requiredInOneCoinSat = 400_000L))
        assertEquals(AssetFanOutNotice(AssetFanOutNotice.Kind.SHORT_OF_DGB, 400_000L), n)
    }

    @Test
    fun `a split refused because coins were unread says how many and the full requirement`() {
        val n = assetFanOutNotice(FanOut.Refused(500L, "x", listOf("a:0", "b:1"), requiredInOneCoinSat = 300_000L))
        assertEquals(AssetFanOutNotice(AssetFanOutNotice.Kind.COINS_UNREAD, 300_000L, 2), n)
    }

    /** The figure shown is enough on its own: after the sweep the old wallet holds nothing, the user
     *  sends exactly that amount, and the next run's split is planned from that one coin. */
    @Test
    fun `the amount shown funds the split after the sweep, and the shortfall alone does not`() {
        val feePerKb = 100_000L
        val coin = { sats: Long -> app.aroundtheblock.wallet.core.recovery.ForeignAssetTransferPlan.Spend(
            txid = "t", vout = 0, amountSat = sats, scriptPubKeyHex = "76a914" + "00".repeat(20) + "88ac", chain = 0, index = 0) }
        val plan = { coins: List<app.aroundtheblock.wallet.core.recovery.ForeignAssetTransferPlan.Spend> ->
            app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.plan(3, coins, "DSource", feePerKb) }
        // Before: one small coin, three assets -> refused.
        val refused = plan(listOf(coin(100_000L))) as app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Refused
        val shown = assetFanOutNotice(FanOut.Refused(refused.shortfallSat, refused.detail, refused.unreadInputs,
            refused.requiredInOneCoinSat))!!.requiredSat
        assertTrue("the full requirement exceeds the shortfall", shown > refused.shortfallSat)
        // After the sweep the old wallet is empty; the user sends what was shown.
        assertTrue("the amount shown must fund the split on its own",
            plan(listOf(coin(shown))) is app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Ok)
        assertTrue("one satoshi less must not", plan(listOf(coin(shown - 1))) is
            app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Refused)
        assertTrue("sending only the shortfall is refused again", plan(listOf(coin(refused.shortfallSat))) is
            app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Refused)
        // A wallet with no plain coin at all: its "needed" was sized for zero inputs; the figure shown is not.
        val none = plan(emptyList()) as app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Refused
        assertTrue(plan(listOf(coin(none.requiredInOneCoinSat))) is app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Ok)
        assertTrue(plan(listOf(coin(none.shortfallSat))) is app.aroundtheblock.wallet.core.recovery.ForeignAssetFanOut.Result.Refused)
    }

    /** K1-RECOVERY r3: "%.8f" in a comma locale wrote "1,00000000", and stripping '.' left "1, DGB". */
    @Test
    fun `DGB amounts format the same in a comma locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMAN)
            assertEquals("1 DGB", formatSatToDgb(100_000_000L))
            assertEquals("0.0012 DGB", formatSatToDgb(120_000L))
            assertEquals("1234.56789012 DGB", formatSatToDgb(123_456_789_012L))
            assertEquals("0 DGB", formatSatToDgb(0L))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `a failed split is reported, a needless or broadcast one is not`() {
        assertEquals(AssetFanOutNotice.Kind.FAILED, assetFanOutNotice(FanOut.Failed("r"))?.kind)
        assertNull(assetFanOutNotice(FanOut.NotNeeded))
        assertNull(assetFanOutNotice(FanOut.Broadcast("t", 3)))
        assertNull(assetFanOutNotice(null))
    }

    /** The view model must carry a refused or failed split to Done, on both recovery paths. */
    @Test
    fun `the view model hands a stopped split to the result screen`() {
        val vm = source("app/src/main/java/app/aroundtheblock/wallet/ui/recovery/RecoverFundsViewModel.kt")
        assertTrue("the outcome does not carry the stopped split",
            vm.contains("RecoveryOutcome(moveResult.moves, swept, dd, fanStopped)"))
        assertEquals("both Done states must pass assetFanOut", 2,
            Regex("""outcome\.digiDollar, outcome\.assetFanOut""").findAll(vm).count())
        val screen = source("app/src/main/java/app/aroundtheblock/wallet/ui/recovery/RecoverFundsScreen.kt")
        assertTrue("the result screen does not render the notice", screen.contains("assetFanOutNotice(assetFanOut)"))
    }

    private fun source(path: String): String =
        listOf(path, "../$path").map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: throw IllegalStateException("$path not found")
}
