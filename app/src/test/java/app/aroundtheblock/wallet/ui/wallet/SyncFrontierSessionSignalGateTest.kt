package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: the main screen's sync frontier is derived with the
 * "reached Synced this session" signal, so `deriveSyncFrontier`'s lead rule (SYNC_TARGET_MAX_LEAD)
 * is live there and not left on its default.
 *
 * The rule itself is behaviour and is tested as behaviour in `core`'s `SyncFrontierTest`. What no
 * behaviour test can reach — no `app` unit test builds this view model — is that the call site
 * actually passes the signal, and passes the RIGHT one: a per-process flag that starts false and
 * is latched from the frontier this same call derived. That pairing is what makes the signal mean
 * "this session": a flag restored from storage would defeat the cold-start case the rule is gated
 * for, and a flag latched before the call would answer for the current emission instead of the
 * previous one.
 */
class SyncFrontierSessionSignalGateTest {

    private val viewModel: KotlinSourceGate =
        File("src/main/java/app/aroundtheblock/wallet/ui/wallet/WalletViewModel.kt").let {
            assertTrue("WalletViewModel.kt is missing — this gate is watching a file that moved", it.exists())
            KotlinSourceGate.of(it.readText())
        }

    private fun KotlinSourceGate.theCall(callee: String): KotlinSourceGate.Call {
        val found = calls(callee)
        assertEquals("`$callee(` is called ${found.size} times — the gate reads one call, and it has to be the only one", 1, found.size)
        return found.single()
    }

    @Test fun `the frontier is derived with the session signal`() {
        val call = viewModel.theCall("deriveSyncFrontier")
        assertEquals(
            "deriveSyncFrontier is not handed the session signal, so its lead rule stays on the default and is inert",
            "hasReachedSyncedOnce", call.named["reachedSyncedThisSession"],
        )
    }

    @Test fun `the session signal starts false in every process and is latched from the derived frontier`() {
        assertTrue(
            "hasReachedSyncedOnce is not a per-process flag starting false",
            Regex("""private\s+var\s+hasReachedSyncedOnce\s*:\s*Boolean\s*=\s*false""").containsMatchIn(viewModel.code),
        )
        val call = viewModel.theCall("deriveSyncFrontier")
        val latch = viewModel.branchesOn("frontier.stage == SyncStage.Synced || frontier.abandonedBandHolding")
        assertEquals("the latch on the derived frontier is written ${latch.size} times; the gate reads one", 1, latch.size)
        assertEquals(
            "the latch branch does not set the flag",
            "{ hasReachedSyncedOnce = true }", KotlinSourceGate.squeeze(viewModel.code.substring(latch.single())),
        )
        assertTrue(
            "the flag is latched before the call that reads it — the signal would answer for this emission, not the previous one",
            latch.single().first > call.range.last,
        )
        val block = viewModel.enclosingBlock(call.range.first)
        assertTrue(
            "the latch does not stand in the same block as the call it is latched from",
            block != null && latch.single().first in block,
        )
    }
}
