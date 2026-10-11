package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate: a DigiDollar send goes ahead only for the approval on screen, and only
 * once — the guard the DGB `send()` has had since 1.4. A second submit while one is in flight, or a
 * submit for an approval whose confirmation was dismissed or whose credential prompt was refused, finds
 * nothing to send. Each DigiDollar send is a transfer and a 0.1 DGB fee of its own.
 *
 * The guard is [app.aroundtheblock.wallet.core.model.SendConfirmation] — open, claim once, close — whose
 * behaviour `core` tests (`AssetSendBindingTest`). No `app` unit test can build the view model, so this
 * pins that the DigiDollar path uses it.
 */
class DigiDollarSendInFlightGateTest {

    private val vm = KotlinSourceGate.of(source("ui/wallet/SendViewModel.kt"))
    private val screen = KotlinSourceGate.of(source("ui/wallet/SendScreen.kt"))

    private fun source(rel: String): String = File("src/main/java/app/aroundtheblock/wallet/$rel").let {
        assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
        it.readText()
    }

    private fun KotlinSourceGate.section(from: String, until: String): IntRange =
        range(from, until) ?: throw AssertionError("cannot find `$from` … `$until` — the gate is blind, not clean")

    @Test fun `the DigiDollar send goes ahead only when it claims the approval on screen`() {
        val send = vm.section("fun sendDigiDollar(", "companion object")
        val claims = vm.calls("ddConfirmation.claim", send)
        assertEquals("sendDigiDollar() does not claim the approval on screen before sending", 1, claims.size)
        assertEquals(listOf("approved"), claims.single().arguments)
        val guarded = vm.guardedBy(claims.single())
            ?: throw AssertionError("the DigiDollar send does not stand under the claim: a second submit still sends")
        val transfer = vm.calls("transactionBuilder.sendDigiDollar", send).single()
        assertTrue("the transfer is made outside the claim", transfer.range.first in guarded)
    }

    @Test fun `the claim is released when the send ends, whatever its outcome`() {
        val send = vm.code.substring(vm.section("fun sendDigiDollar(", "companion object"))
        val fin = send.indexOf("finally")
        assertTrue("sendDigiDollar() has no finally to release the claim", fin >= 0)
        assertTrue("the claim is not released in the finally", send.indexOf("ddConfirmation.close()", fin) > fin)
    }

    @Test fun `an approval is put on screen through the confirmation, and taken off it the same way`() {
        assertTrue(
            "the DigiDollar approval is not opened on the confirmation",
            vm.calls("ddConfirmation.open", vm.section("fun approveDigiDollar(", "fun sendDigiDollar(")).isNotEmpty(),
        )
        assertTrue(
            "nothing closes the DigiDollar confirmation when it is dismissed",
            vm.calls("ddConfirmation.close", vm.section("fun cancelDigiDollar(", "fun sendDigiDollar(")).isNotEmpty(),
        )
        // The screen closes it when the dialog is dismissed and when the credential prompt is refused.
        val dialog = screen.calls("DigiDollarConfirmationDialog").singleOrNull()
            ?: throw AssertionError("cannot find the DigiDollar confirmation — the gate is blind, not clean")
        val cancels = screen.calls("viewModel.cancelDigiDollar", dialog.range)
        assertEquals("the dialog's cancel and the refused prompt must both close the confirmation", 2, cancels.size)
    }
}
