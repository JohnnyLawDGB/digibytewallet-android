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

    @Test
    fun `a split refused for want of DGB tells the user the shortfall`() {
        val n = assetFanOutNotice(FanOut.Refused(shortfallSat = 12_345L, detail = "x"))
        assertEquals(AssetFanOutNotice(AssetFanOutNotice.Kind.SHORT_OF_DGB, 12_345L), n)
    }

    @Test
    fun `a split refused because coins were unread says how many and the shortfall`() {
        val n = assetFanOutNotice(FanOut.Refused(500L, "x", unreadInputs = listOf("a:0", "b:1")))
        assertEquals(AssetFanOutNotice(AssetFanOutNotice.Kind.COINS_UNREAD, 500L, 2), n)
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
