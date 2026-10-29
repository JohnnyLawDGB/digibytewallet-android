package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: every screen that shows sync status derives its frontier
 * through the ONE process-wide session (`WalletManager.syncFrontierSession`), so the "reached
 * Synced this session" signal that `deriveSyncFrontier`'s lead rule (SYNC_TARGET_MAX_LEAD) reads is
 * the same for all of them.
 *
 * The rule and the session's latch are behaviour, tested as behaviour in `core`'s
 * `SyncFrontierTest` and `SyncFrontierSessionTest`. What no behaviour test can reach — no `app`
 * unit test builds these view models — is that the call sites go through the session. When the
 * main card kept its own latch and Network Info called `deriveSyncFrontier` without one, Network
 * Info could read Syncing while the main card read Synced from the same inputs.
 */
class SyncFrontierSessionSignalGateTest {

    private val mainDir = File("src/main/java")

    private fun gate(path: String): KotlinSourceGate =
        File("src/main/java/app/aroundtheblock/wallet/ui/$path").let {
            assertTrue("$path is missing — this gate is watching a file that moved", it.exists())
            KotlinSourceGate.of(it.readText())
        }

    private val sessionDerive = "walletManager.syncFrontierSession.derive"

    @Test fun `the main card derives through the shared session`() {
        val found = gate("wallet/WalletViewModel.kt").calls(sessionDerive)
        assertEquals("WalletViewModel derives through the shared session ${found.size} times; it has to be once", 1, found.size)
    }

    @Test fun `Network Info derives through the shared session`() {
        val found = gate("settings/SettingsViewModel.kt").calls(sessionDerive)
        assertEquals("SettingsViewModel (Network Info) derives through the shared session ${found.size} times; it has to be once", 1, found.size)
    }

    @Test fun `no app screen derives the frontier outside the session`() {
        assertTrue("src/main/java is missing — run from the app module", mainDir.isDirectory)
        val direct = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { KotlinSourceGate.of(it.readText()).calls("deriveSyncFrontier").isNotEmpty() }
            .map { it.path }.toList()
        assertEquals(
            "these files call deriveSyncFrontier directly, with their own (or no) session latch: $direct",
            emptyList<String>(), direct,
        )
    }

    @Test fun `going to the background clears the latch, for every wallet`() {
        // The reset itself is behaviour (core WalletManagerSyncSessionResetTest); this is the one
        // call no JVM test can reach: onStop hands it over before, and outside, the PIN-only lock.
        val activity = File("src/main/java/app/aroundtheblock/wallet/MainActivity.kt").let {
            assertTrue("MainActivity.kt is missing — this gate is watching a file that moved", it.exists())
            KotlinSourceGate.of(it.readText())
        }
        val onStop = activity.range("override fun onStop()", "override fun ")
        assertTrue("MainActivity.onStop is missing", onStop != null)
        val reset = activity.calls("walletManager.enterBackground", onStop!!)
        assertEquals("onStop calls walletManager.enterBackground() ${reset.size} times; it has to be once", 1, reset.size)
        val lock = activity.calls("walletManager.lockUi", onStop)
        assertTrue("enterBackground must come before, and outside, the Unlocked-only lockUi branch",
            lock.isEmpty() || reset.single().range.first < lock.first().range.first)
    }

    @Test fun `the main card's balance guard reads the shared latch`() {
        val vm = gate("wallet/WalletViewModel.kt")
        assertTrue(
            "hasReachedSyncedOnce is not read from the shared session",
            Regex("""private\s+val\s+hasReachedSyncedOnce\s*:\s*Boolean\s+get\(\)\s*=\s*walletManager\.syncFrontierSession\.reachedSynced""")
                .containsMatchIn(vm.code),
        )
    }
}
