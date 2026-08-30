package io.digibyte.core.digiid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityKeyPolicyTest {

    @Test
    fun `digiscope domains always use the legacy identity`() {
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("digiscope.me", false))
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("api.digiscope.me", false))
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("hub.digiscope.me", false))
    }

    @Test
    fun `a lookalike domain is NOT treated as digiscope`() {
        assertFalse(IdentityKeyPolicy.isDigiScopeDomain("notdigiscope.me"))
        assertFalse(IdentityKeyPolicy.isDigiScopeDomain("digiscope.me.evil.com"))
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("notdigiscope.me", false))
    }

    @Test
    fun `a domain with a successful legacy login is grandfathered`() {
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("example.com", true))
    }

    @Test
    fun `a new domain gets a per-site identity`() {
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com", false))
    }

    @Test
    fun `subdomain matching requires the dot boundary`() {
        assertTrue(IdentityKeyPolicy.isDigiScopeDomain("a.digiscope.me"))
        assertFalse(IdentityKeyPolicy.isDigiScopeDomain("adigiscope.me"))
    }
}
