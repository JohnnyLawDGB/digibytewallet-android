package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate (DGB-005): the stranded-send sweep acts on a terminal rejection of a plain DGB send by
 * removing the transaction — which unlocks its coins — and its record. It used to log the verdict and
 * carry on, so a send the network refused for good stayed in the wallet with its coins spent and stayed
 * "pending" in the activity list.
 *
 * The decision is [app.aroundtheblock.wallet.core.sync.PublishOutcome.actionFor], tested in `core`
 * (`PublishOutcomeTest`). This pins that the sweep asks it and acts on it, before any republish, and that
 * the removal is for a plain DGB send only and never reaches into the asset layer: an asset send keeps
 * the CONFLICTED accounting, which reads an abandoned attempt from the wallet.
 */
class StrandedSweepRemovesRejectedGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())

    private val sweep: IntRange
        get() {
            val at = sync.code.indexOf("private suspend fun rebroadcastStrandedSends()")
            assertTrue("rebroadcastStrandedSends not found", at >= 0)
            return at until sync.code.indexOf("\n    }\n", at)
        }

    private val removal: IntRange
        get() = sync.branchesOn("action == app.aroundtheblock.wallet.core.sync.PublishOutcome.Action.REMOVE_AND_UNLOCK", sweep)
            .singleOrNull() ?: throw AssertionError("the sweep has no branch acting on a terminal rejection: it is still only logged")

    @Test fun `the sweep asks what a terminal rejection means for the send`() {
        val asks = sync.calls("outcome.actionFor", sweep)
        assertEquals("the sweep must ask the outcome what to do, exactly once", 1, asks.size)
        assertEquals(
            "the sweep removes on the publish verdict alone: one peer's rejection can unlock the coins",
            listOf("plainDgb = isPlainDgbSend(txid)", "refusedByPeers = refusalHold.observe(txid) { isRefusedByPeers(txid) }"),
            asks.single().arguments,
        )
    }

    /** No single peer's message removes a transaction: the corroboration is the core's, which
     *  counts the rejecting peers' network groups and every peer that relayed or announced it. */
    @Test fun `the corroboration comes from the core's count of rejecting peers`() {
        val helper = sync.range("private fun isRefusedByPeers(", "\n    }\n")
            ?: throw AssertionError("isRefusedByPeers not found: nothing asks the core whether peers corroborate the refusal")
        assertEquals(1, sync.calls("NativeBridge.isRefusedByNetwork", helper).size)
        assertTrue("the helper reads the publish ring instead of the core's peer evidence", sync.calls("NativeBridge.getPublishResult", helper).isEmpty())
    }

    @Test fun `a refused plain DGB send is removed with its record, and is not republished`() {
        val branch = removal
        assertEquals("the refused send is not removed from the wallet", 1, sync.calls("NativeBridge.removeTransaction", branch).size)
        val forget = sync.calls("store.remove", branch)
        assertEquals("the refused send's record is not forgotten", 1, forget.size)
        assertEquals(listOf("txid"), forget.single().arguments)
        assertTrue("the branch goes on to republish what it removed", sync.code.substring(branch).contains("continue"))
        val publish = sync.calls("NativeBridge.publishTransaction", sweep).single()
        assertTrue("the removal is decided after the republish", branch.first < publish.range.first)
    }

    @Test fun `the removal never reaches into the asset layer`() {
        assertTrue(
            "the removal of a refused plain DGB send calls into the asset layer",
            !sync.code.substring(removal).contains("assetManager."),
        )
        val plain = sync.range("private fun isPlainDgbSend(", "\n    }\n")
            ?: throw AssertionError("isPlainDgbSend not found — the gate is blind, not clean")
        assertEquals("a plain DGB send carries no DigiAsset instructions", 1, sync.calls("NativeBridge.isAssetTransaction", plain).size)
        assertEquals("a plain DGB send carries no DigiDollar marker", 1, sync.calls("NativeBridge.digiDollarTxType", plain).size)
    }
}
