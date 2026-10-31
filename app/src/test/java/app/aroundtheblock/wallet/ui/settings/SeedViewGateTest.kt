package app.aroundtheblock.wallet.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recovery-phrase pass (4.1.3, review R18): a lock ends every pass, and the phrase is drawn only while unlocked. */
class SeedViewGateTest {
    @Test fun `a spent pass is live until a lock`() {
        SeedViewGate.revoke()
        SeedViewGate.grant()
        val token = SeedViewGate.spend()
        assertTrue(SeedViewGate.isLive(token))
        assertTrue(SeedViewGate.mayShow(token, unlocked = true))
        SeedViewGate.revoke()   // the lock
        assertFalse("a pass spent before a lock is still live after it", SeedViewGate.isLive(token))
        assertFalse(SeedViewGate.mayShow(token, unlocked = true))
    }

    @Test fun `a live pass draws nothing while locked`() {
        SeedViewGate.revoke()
        SeedViewGate.grant()
        val token = SeedViewGate.spend()
        assertFalse(SeedViewGate.mayShow(token, unlocked = false))
    }

    @Test fun `no grant, no pass, and a grant is spent once`() {
        SeedViewGate.revoke()
        assertEquals("", SeedViewGate.spend())
        assertFalse(SeedViewGate.isLive(""))
        SeedViewGate.grant()
        assertTrue(SeedViewGate.spend().isNotEmpty())
        assertEquals("", SeedViewGate.spend())
    }
}
