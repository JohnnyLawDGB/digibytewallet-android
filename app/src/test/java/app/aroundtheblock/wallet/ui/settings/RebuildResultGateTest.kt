package app.aroundtheblock.wallet.ui.settings

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: ReconcileScreen acts on the rebuild's answer. `rebuildFromChainRescan` and
 * `rebuildFromChainRescanFrom` return false when a step the clears depend on failed and nothing was
 * cleared (BRChainRebuild.h's stop rules). On false the screen must NOT restart the app (there is
 * nothing to restart into, and the user would see the old wallet with no word of why), must say
 * nothing was rebuilt, and asks the sync service to resume near the tip.
 *
 * A gate because ReconcileScreen is a Compose screen this JVM cannot run; the rebuild's answer
 * itself is covered by WalletManagerChainRebuildParityTest.
 */
class RebuildResultGateTest {

    private val gate = KotlinSourceGate.of(
        File("src/main/java/app/aroundtheblock/wallet/ui/settings/ReconcileScreen.kt").readText(),
    )

    private fun rebuildCalls() =
        (gate.calls("walletManager.rebuildFromChainRescan") + gate.calls("walletManager.rebuildFromChainRescanFrom"))
            .sortedBy { it.range.first }

    @Test fun `the screen calls the rebuild at exactly two places`() {
        assertEquals("a new call site must be held to the same rule", 2, rebuildCalls().size)
    }

    @Test fun `each call site branches on the answer and does not restart on false`() {
        for (call in rebuildCalls()) {
            val where = "the call at offset ${call.range.first}"
            // `val name = withContext(Dispatchers.IO) { walletManager.rebuild…(…) }`
            val io = gate.enclosingBlock(call.range.first)
            assertNotNull("$where is not inside a block", io)
            val head = gate.code.substring(0, io!!.first)
            val held = Regex("""(?<!\w)val\s+(\w+)\s*=\s*withContext\s*\(\s*Dispatchers\.IO\s*\)\s*$""").find(head)
            assertNotNull("$where: the answer is not held in a value", held)
            val name = held!!.groupValues[1]

            val launch = gate.enclosingBlock(io.first)
            assertNotNull("$where is not inside the launch", launch)
            val branches = gate.branchesOn("!$name", launch!!).filter { it.first > io.last }
            assertEquals("$where: the screen does not branch once on !$name", 1, branches.size)
            val onFalse = branches.single()
            val onFalseCode = gate.code.substring(onFalse.first, onFalse.last + 1)

            assertTrue("$where: the false branch does not leave the launch", Regex("""(?<!\w)return@launch(?!\w)""").containsMatchIn(onFalseCode))
            assertTrue("$where: the false branch restarts the app", gate.calls("exit", onFalse).isEmpty())
            assertTrue("$where: the false branch restarts the app", gate.calls("startActivity", onFalse).isEmpty())
            assertEquals("$where: the false branch does not resume sync", 1, gate.calls("requestSyncResumeNearTip", onFalse).size)

            // The restart follows the branch, so it runs only on true.
            val exit = gate.calls("exit", launch).single()
            assertTrue("$where: the restart comes before the branch on the answer", exit.range.first > onFalse.last)
        }
    }

    @Test fun `the resume request is the sync service's reload-first rebuild`() {
        val declared = Regex("""fun\s+requestSyncResumeNearTip\s*\(""").find(gate.code)
        assertNotNull("no requestSyncResumeNearTip", declared)
        val body = gate.blockAt(gate.code.indexOf('{', declared!!.range.last))!!
        val sent = gate.written.substring(body.first, body.last + 1)
        assertTrue("the request does not name ACTION_RECREATE_NEAR_TIP", sent.contains("SyncService.ACTION_RECREATE_NEAR_TIP"))
        assertEquals("the request is not delivered", 1, gate.calls("startForegroundService", body).size)
        assertTrue("the request rebuilds the manager itself", gate.calls("forceReconnect", body).isEmpty())
    }
}
