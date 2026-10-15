package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate (DGB-003, NEW-B01, NEW-B02, DGB-005): the send screen tells the truth about the fee and
 * about delivery.
 *
 * The behaviour is tested in `core` (`FeeQuoteTest`, `PreparedSendTest`, `PublishOutcomeTest`). This
 * covers what those cannot reach, because no `app` unit test builds a ViewModel or a composable:
 *
 *  - no typical-size constant is left in either view model to compute a fee from — the confirmation
 *    shows the fee of the transaction built for the send, which is the fee signed;
 *  - no warning names a state the builder cannot produce ("below relay minimum": the builder raises a
 *    low custom fee to the floor);
 *  - review opens the confirmation only with a prepared send, and the send signs that prepared send;
 *  - "Sent" is shown only for a send a peer has taken; otherwise a distinct, blocking
 *    "sent, not yet relayed" screen.
 */
class FeeShownIsFeeSignedGateTest {

    private val uiRoot = File("src/main/java/app/aroundtheblock/wallet/ui")

    private fun gate(rel: String): KotlinSourceGate = File(uiRoot, rel).let {
        assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
        KotlinSourceGate.of(it.readText())
    }

    private val vm by lazy { gate("wallet/SendViewModel.kt") }
    private val screen by lazy { gate("wallet/SendScreen.kt") }
    private val assetVm by lazy { gate("asset/AssetViewModel.kt") }
    private val assetScreen by lazy { gate("asset/AssetSendScreen.kt") }

    private fun KotlinSourceGate.section(from: String, until: String): IntRange =
        range(from, until) ?: throw AssertionError("cannot find `$from` … `$until` — the gate is blind, not clean")

    @Test fun `the send view model computes no fee from a typical transaction size`() {
        for (left in listOf("TYPICAL_TX_VSIZE", "141L", "defaultFeeSat")) {
            assertFalse("SendViewModel still computes a fee from `$left`: the confirmation shows a guess, not the fee signed", vm.code.contains(left))
        }
    }

    @Test fun `the asset view model computes no fee from a typical transaction size`() {
        for (left in listOf("ASSET_TYPICAL_VSIZE", "613L", "defaultFeeSat")) {
            assertFalse("AssetViewModel still computes a fee from `$left`", assetVm.code.contains(left))
        }
    }

    @Test fun `no screen warns of a fee state the builder cannot produce`() {
        for ((name, g) in listOf("SendViewModel" to vm, "SendScreen" to screen, "AssetViewModel" to assetVm, "AssetSendScreen" to assetScreen)) {
            assertFalse(
                "$name still has a below-relay fee warning: the builder raises a low custom fee to the floor, so that warning describes a transaction that cannot be built",
                g.code.contains("BelowRelay"),
            )
        }
    }

    @Test fun `review opens the confirmation only with a send prepared for it`() {
        val review = vm.section("fun requestConfirm(", "fun cancelConfirm(")
        assertEquals(
            "review does not build and quote the send before the confirmation",
            1, vm.calls("transactionBuilder.prepareTransaction", review).size,
        )
        val opens = vm.calls("SendState.Confirming")
        assertTrue("nothing opens the confirmation — the gate is blind, not clean", opens.isNotEmpty())
        for (open in opens) {
            assertTrue(
                "the confirmation is opened without the prepared send it shows: ${open.text}",
                open.arguments.size == 2 && open.arguments[1] == "prepared",
            )
        }
    }

    @Test fun `the send signs the prepared send and nothing else`() {
        val send = vm.section("fun send(", "fun resetState(")
        assertEquals("send() does not hand the prepared send to the builder", 1, vm.calls("transactionBuilder.sendPrepared", send).size)
        assertTrue("send() still builds a new transaction after the prompt", vm.calls("transactionBuilder.sendTransaction", send).isEmpty())
    }

    @Test fun `Sent is shown only for a send a peer has taken`() {
        val send = vm.section("fun send(", "fun resetState(")
        val body = vm.code.substring(send)
        assertTrue("send() does not tell a relayed send from one no peer has taken", body.contains("Delivery.RELAYED"))
        assertTrue("send() has no state for a send no peer has taken yet", vm.calls("SendState.NotYetRelayed", send).isNotEmpty())
        // The overlay for it blocks the screen as success and failure do: a branch of its own that returns.
        val branch = screen.branchesOn("sendState is SendState.NotYetRelayed").singleOrNull()
            ?: throw AssertionError("SendScreen does not show the not-yet-relayed state in a branch of its own")
        assertTrue("the not-yet-relayed screen does not block the form", Regex("""\breturn\b""").containsMatchIn(screen.code.substring(branch)))
        // And "Sent" itself is only the relayed state's.
        val sent = screen.branchesOn("sendState is SendState.Success").singleOrNull()
            ?: throw AssertionError("SendScreen does not show the sent state in a branch of its own")
        assertTrue("the sent screen is not told the send was relayed", screen.code.substring(sent).contains("relayed = true"))
    }

    /**
     * GUARD: the DGB send's only signer is the one that checks the prepared bytes against the
     * approved fee. The unchecked bridge signer stays declared, unused; nothing in `core` or `app`
     * calls it.
     */
    @Test fun `nothing signs a DGB send without the fee check`() {
        val roots = listOf(File("src/main/java"), File("../core/src/main/java"))
        val callers = roots.flatMap { root ->
            assertTrue("$root is missing — the gate is blind, not clean", root.isDirectory)
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { KotlinSourceGate.of(it.readText()).calls("NativeBridge.signTransaction").isNotEmpty() }
                .map { it.name }.toList()
        }
        assertTrue("the unchecked signer is called from: $callers", callers.isEmpty())
    }

    @Test fun `the confirmation states a fee raised above the custom total`() {
        val dialog = screen.calls("SendConfirmationDialog").singleOrNull()
            ?: throw AssertionError("cannot find the one SendConfirmationDialog call — the gate is blind, not clean")
        assertEquals("approved.feeEstimateSats", dialog.named["feeEstimate"])
        assertTrue(
            "the confirmation does not say when the fee is above the custom total typed: ${dialog.text}",
            dialog.named["raisedFromSats"]?.contains("quote") == true,
        )
    }
}
