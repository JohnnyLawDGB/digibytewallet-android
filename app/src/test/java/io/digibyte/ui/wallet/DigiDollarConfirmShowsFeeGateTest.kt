package io.digibyte.ui.wallet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate (B235): the DigiDollar confirmation states the fee the send pays, not only that it is
 * "paid in DGB". A tester approving a DigiDollar send on v4.0.81 saw the currency and no amount; the
 * send paid 0.1 DGB. The native builder charges at least the consensus floor
 * (DigiDollarTransferPlan.DD_MIN_FEE_SATS, mirroring DD_MIN_FEE in the core), which is the fee at any
 * practical size, so the row shows that floor.
 */
class DigiDollarConfirmShowsFeeGateTest {

    private val screen = File("src/main/java/io/digibyte/ui/wallet/SendScreen.kt").readText()

    @Test fun `the DigiDollar confirmation shows the fee amount`() {
        val start = screen.indexOf("private fun DigiDollarConfirmationDialog(")
        assertTrue("cannot find the DigiDollar confirmation — the gate is blind, not clean", start >= 0)
        val dialog = screen.substring(start, screen.indexOf("\n}\n", start))
        assertTrue("the fee row does not show the fee floor", dialog.contains("DigiDollarTransferPlan.DD_MIN_FEE_SATS"))
        assertTrue("the fee row does not use the fee-amount string", dialog.contains("R.string.send_dd_fee_value"))
        assertTrue("the fee row still shows only the currency", !dialog.contains("send_paid_in_dgb"))
    }
}
