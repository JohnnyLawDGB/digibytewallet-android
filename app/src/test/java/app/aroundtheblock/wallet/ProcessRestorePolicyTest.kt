package app.aroundtheblock.wallet

import app.aroundtheblock.wallet.ui.settings.SeedViewGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOCK-PD: state saved before a process death must not reopen the app behind its lock.
 *
 * Reproduced on 4.1.1 (2026-10-24, emulator, release build): unlock → Settings → HOME → the
 * process is killed → launcher. The restored back stack showed Settings and the wallet with the
 * state Locked; with the recovery phrase open at the kill, all 12 words came back with no PIN.
 * The device check of the fix is the same sequence (see the 4.1.2 evidence); these tests pin the
 * two decisions it rests on.
 */
class ProcessRestorePolicyTest {

    @Test
    fun `state written by this process is restored`() {
        assertEquals("saved-nav-state",
            ProcessRestorePolicy.stateToRestore("saved-nav-state", ProcessRestorePolicy.processToken))
    }

    @Test
    fun `state written by a process that has died is dropped`() {
        assertNull(ProcessRestorePolicy.stateToRestore("saved-nav-state", java.util.UUID.randomUUID().toString()))
    }

    @Test
    fun `state saved without a token is dropped`() {
        // State written by a build from before this fix, restored once after the update.
        assertNull(ProcessRestorePolicy.stateToRestore("saved-nav-state", null))
    }

    @Test
    fun `a cold start with nothing saved stays empty`() {
        assertNull(ProcessRestorePolicy.stateToRestore(null as String?, null))
        assertNull(ProcessRestorePolicy.stateToRestore(null as String?, ProcessRestorePolicy.processToken))
    }

    @Test
    fun `a phrase pass spent in this process is live`() {
        SeedViewGate.grant()
        val token = SeedViewGate.spend()
        assertTrue(SeedViewGate.isLive(token))
    }

    @Test
    fun `a pass saved by another process is never live`() {
        // What rememberSaveable hands back after a process death: the dead process's token.
        assertFalse(SeedViewGate.isLive("00000000-0000-0000-0000-000000000000"))
        assertFalse(SeedViewGate.isLive(java.util.UUID.randomUUID().toString()))
    }

    @Test
    fun `no pass spends to an empty token that is not live`() {
        SeedViewGate.revoke()
        val token = SeedViewGate.spend()
        assertEquals("", token)
        assertFalse(SeedViewGate.isLive(token))
    }

    @Test
    fun `a pass is spent once`() {
        SeedViewGate.grant()
        assertTrue(SeedViewGate.isLive(SeedViewGate.spend()))
        assertFalse(SeedViewGate.isLive(SeedViewGate.spend()))
    }
}
