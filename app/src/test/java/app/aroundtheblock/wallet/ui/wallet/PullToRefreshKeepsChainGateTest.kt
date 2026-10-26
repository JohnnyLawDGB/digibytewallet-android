package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: pulling the main screen to refresh never rebuilds the peer manager on its own.
 *
 * A rebuild consumes the saved headers the bridge was given at launch; after the first manager
 * exists that set is empty, so a rebuild that does not reload it first starts from the checkpoint
 * at the wallet's birth and re-syncs every header since. SyncService owns every rebuild and always
 * reloads the recent headers first (RecreateSequence). The pull asks it to, and only nudges sync.
 *
 * A gate because WalletViewModel and SyncService are Android components this JVM cannot run; the
 * rebuild ordering itself is covered by RecreateSequenceTest.
 */
class PullToRefreshKeepsChainGateTest {

    private val appRoot = File("src/main/java/app/aroundtheblock/wallet")
    private fun gate(rel: String) = KotlinSourceGate.of(File(appRoot, rel).readText())

    private fun body(source: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(source.code) ?: error("no fun $name")
        val open = source.code.indexOf('{', declared.range.last)
        return source.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `pull to refresh does not rebuild the peer manager itself`() {
        val vm = gate("ui/wallet/WalletViewModel.kt")
        val refresh = body(vm, "refresh")
        assertTrue(
            "refresh() rebuilds the peer manager without reloading the saved headers first",
            vm.calls("forceReconnect", refresh).isEmpty(),
        )
    }

    @Test fun `pull to refresh at zero peers asks the sync service for its reload-first rebuild`() {
        val vm = gate("ui/wallet/WalletViewModel.kt")
        val refresh = body(vm, "refresh")
        assertEquals(
            "refresh() does not hand a stuck manager to the sync service",
            1, vm.calls("requestRecreateNearTip", refresh).size,
        )
        val request = body(vm, "requestRecreateNearTip")
        val sent = vm.written.substring(request.first, request.last + 1)
        assertTrue("the request does not name the sync service's rebuild action", sent.contains("ACTION_RECREATE_NEAR_TIP"))
        assertEquals("the request is not delivered to the sync service", 1, vm.calls("startForegroundService", request).size)
        assertTrue("the request rebuilds the manager itself", vm.calls("forceReconnect", request).isEmpty())
    }

    @Test fun `the sync service answers that request with the reload-first rebuild`() {
        val sync = gate("service/SyncService.kt")
        val handled = sync.branchesOn("intent?.action == ACTION_RECREATE_NEAR_TIP")
        assertEquals("SyncService does not handle the refresh request exactly once", 1, handled.size)
        assertEquals(
            "the refresh request is not answered by recreatePeerManagerResumingNearTip",
            1, sync.calls("recreatePeerManagerResumingNearTip", handled.single()).size,
        )
        assertTrue(
            "the refresh request rebuilds without the reload",
            sync.calls("forceReconnect", handled.single()).isEmpty(),
        )
    }
}
