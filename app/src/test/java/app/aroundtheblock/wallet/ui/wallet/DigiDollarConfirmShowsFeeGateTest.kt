package app.aroundtheblock.wallet.ui.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the DigiDollar confirmation shows, as a number, the fee of the transfer that is signed.
 *
 * B235 (v4.0.83) put a fee on the confirmation, but it was the 0.1 DGB floor printed as "≈ 0.10000000
 * DGB" whatever the transfer paid — and the transfer pays more whenever a DGB change below the dust
 * line is folded into its fee. Review now builds the transfer and reads its own fee
 * ([app.aroundtheblock.wallet.core.TransactionBuilder.prepareDigiDollar]); the approval carries it
 * ([app.aroundtheblock.wallet.core.model.ApprovedSend.DigiDollar.feeSats]), the dialog shows it, and
 * native code signs the rebuilt transfer only at exactly that fee (dd_send_preview_kat).
 */
class DigiDollarConfirmShowsFeeGateTest {

    private val screen = File("src/main/java/app/aroundtheblock/wallet/ui/wallet/SendScreen.kt").readText()
    private val vm = File("src/main/java/app/aroundtheblock/wallet/ui/wallet/SendViewModel.kt").readText()

    private fun section(text: String, from: String, until: String): String {
        val start = text.indexOf(from)
        assertTrue("cannot find `$from` — the gate is blind, not clean", start >= 0)
        val end = text.indexOf(until, start + from.length)
        assertTrue("cannot find `$until` after `$from` — the gate is blind, not clean", end > start)
        return text.substring(start, end)
    }

    @Test fun `the confirmation shows the reviewed transfer's own fee as an exact number`() {
        val dialog = section(screen, "private fun DigiDollarConfirmationDialog(", "\n}\n")
        assertTrue("the dialog is not handed the fee", dialog.contains("feeSats: Long"))
        assertTrue("the fee row does not show the fee it is handed", dialog.contains("BigDecimal.valueOf(feeSats, 8)"))
        assertTrue("the fee row is not an exact amount in DGB", dialog.contains("\"%.8f DGB\""))
        assertFalse("the fee row still shows the floor, not the transfer's fee", dialog.contains("DD_MIN_FEE_SATS"))
        assertFalse("the fee row still says the fee is approximate", dialog.contains("send_dd_fee_value"))
    }

    @Test fun `the fee shown is the approval's, which review made from the transfer it built`() {
        val call = section(screen, "DigiDollarConfirmationDialog(\n", "onConfirm")
        assertTrue("the dialog's fee is not the approval's", call.contains("feeSats = approvedDd.feeSats"))
        val review = section(vm, "suspend fun approveDigiDollar(", "fun cancelDigiDollar(")
        assertTrue("review does not build the transfer to read its fee", review.contains("transactionBuilder.prepareDigiDollar("))
        assertTrue("the approval does not carry the reviewed fee", review.contains(".withFee(prepared.feeSats)"))
    }
}
