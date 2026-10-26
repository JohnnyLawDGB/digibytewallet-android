package app.aroundtheblock.wallet.core.digiid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityKeyPolicyTest {

    @Test
    fun `the listed digiscope hosts always use the legacy identity`() {
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("digiscope.me", false))
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("api.digiscope.me", false))
    }

    /** The shared identity goes to the hosts on the list, exactly: a name under digiscope.me is not one of them. */
    @Test
    fun `a digiscope subdomain that is not on the list gets a per-site identity`() {
        for (host in listOf("hub.digiscope.me", "a.digiscope.me", "www.digiscope.me", "x.api.digiscope.me", "api.api.digiscope.me")) {
            assertFalse("$host is treated as a DigiScope host", IdentityKeyPolicy.isDigiScopeDomain(host))
            assertEquals("$host gets the shared identity", IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(host, false))
        }
    }

    @Test
    fun `a lookalike domain is NOT treated as digiscope`() {
        for (host in listOf("notdigiscope.me", "digiscope.me.other.example", "adigiscope.me", "api-digiscope.me", "digiscope.me.", "api.digiscope.me.")) {
            assertFalse("$host is treated as a DigiScope host", IdentityKeyPolicy.isDigiScopeDomain(host))
        }
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("notdigiscope.me", false))
    }

    @Test
    fun `a domain with a successful legacy login is grandfathered`() {
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("example.com", true))
        // A subdomain that logged in with the shared identity before the list was exact keeps it.
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("hub.digiscope.me", true))
    }

    @Test
    fun `a new domain gets a per-site identity`() {
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com", false))
    }

    @Test
    fun `the list holds exactly the two digiscope hosts`() {
        assertEquals(setOf("digiscope.me", "api.digiscope.me"), IdentityKeyPolicy.DIGISCOPE_HOSTS)
        assertTrue(IdentityKeyPolicy.DIGISCOPE_HOSTS.all(IdentityKeyPolicy::isDigiScopeDomain))
    }

    // ── 4.1.2 row 16: a restored wallet picks the identity a site already knows ─────────────────

    @Test
    fun `a domain with no successful login on record offers the choice`() {
        assertTrue(IdentityKeyPolicy.offersChoice("example.com", hasAnySuccessfulHistory = false, walletMayHaveHistoryElsewhere = true))
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("example.com", false, false, true, IdentityKeyKind.LEGACY))
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com", false, false, true, IdentityKeyKind.PER_SITE))
    }

    @Test
    fun `with no pick the default is the per-site identity, as the original DigiByte app used`() {
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com", false, false, true, null))
    }

    @Test
    fun `once a login succeeded the history decides and a pick is ignored`() {
        assertFalse(IdentityKeyPolicy.offersChoice("example.com", hasAnySuccessfulHistory = true, walletMayHaveHistoryElsewhere = true))
        // A per-site login on record: a request for the shared key cannot move the site off it.
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com", false, true, true, IdentityKeyKind.LEGACY))
        // A shared-key login on record stays grandfathered.
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("example.com", true, true, true, IdentityKeyKind.PER_SITE))
    }

    @Test
    fun `digiscope never offers a choice and stays on the shared identity`() {
        for (host in IdentityKeyPolicy.DIGISCOPE_HOSTS) {
            assertFalse(IdentityKeyPolicy.offersChoice(host, hasAnySuccessfulHistory = false, walletMayHaveHistoryElsewhere = true))
            assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose(host, false, false, true, IdentityKeyKind.PER_SITE))
        }
    }

    @Test
    fun `a wallet created on this device is never offered the shared identity`() {
        // Its seed has signed nothing elsewhere; the shared key would only link its logins.
        assertFalse(IdentityKeyPolicy.offersChoice("example.com", hasAnySuccessfulHistory = false, walletMayHaveHistoryElsewhere = false))
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com", false, false, false, IdentityKeyKind.LEGACY))
    }
}
