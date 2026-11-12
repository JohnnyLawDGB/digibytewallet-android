package app.aroundtheblock.wallet.ui.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the activity list is the shared core's rows (BRTxRows.h through
 * NativeBridge.getTransactionRows), never re-sorted or re-computed in the view model, and a send's
 * amount is never replaced from the app's own record of what it broadcast. A gate because
 * WalletViewModel is an Android component this JVM cannot run; the mapping itself is held by
 * core's TransactionRowsVectorsTest. Fails (never skips) when the code it looks for moves.
 */
class ActivityRowsAreTheCoresGateTest {

    private fun source(rel: String): String = File("src/main/java/app/aroundtheblock/wallet/$rel").let {
        assertTrue("$rel is missing: this gate is watching a file that moved", it.exists())
        it.readText()
    }

    private fun pollBody(): String {
        val vm = source("ui/wallet/WalletViewModel.kt")
        val start = vm.indexOf("val txRows = TransactionRows.parse(NativeBridge.getTransactionRows(")
        val end = vm.indexOf("_txTypedAmounts.value = typedAmounts", start)
        assertTrue("source gate: no txRows = TransactionRows.parse(getTransactionRows ... _txTypedAmounts in WalletViewModel.kt; update the gate",
            start >= 0 && end > start)
        return vm.substring(start, end)
    }

    @Test
    fun `the view model lists the core's rows in the core's order`() {
        val body = pollBody()
        assertTrue("the rows are not parsed by TransactionRows", body.contains("TransactionRows.parse("))
        assertTrue("each row is not mapped by TransactionRows.entity", body.contains("TransactionRows.entity(row, confTip,"))
        for (banned in listOf(".sorted", ".sortBy", ".sortWith", "sortedWith", ".reversed()", "compareBy")) {
            assertFalse("the activity list must keep the core's order ($banned)", body.contains(banned))
        }
        for (banned in listOf("getTransactionDetails", "shouldApplyOutgoingOverride", "sentSats", "feeSats")) {
            assertFalse("the activity list must take the core's figures ($banned)", body.contains(banned))
        }
    }

    @Test
    fun `an unknown fee is no fee line`() {
        val detail = source("ui/wallet/TransactionDetailScreen.kt")
        assertTrue("the detail screen shows a fee the wallet cannot know",
            detail.contains("if (tx.fee != TransactionEntity.FEE_UNKNOWN) {"))
    }
}
