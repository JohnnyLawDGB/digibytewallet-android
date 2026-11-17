package app.aroundtheblock.wallet.core.digiid

import app.aroundtheblock.wallet.core.model.DigiIdRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which identity a site gets: Q4 A (Mark, 2026-11-10), a fixed list of sites, the same on every
 * device. The list cases are iOS's `DigiIdIdentityTests` (digibytewallet-ios develop,
 * `DigiByteWalletTests/DigiIdIdentityTests.swift`), value for value, so both apps hold the same
 * cases; they began as this file's own. The policy takes the domain and nothing else: no history,
 * no pick, no wallet origin.
 */
class IdentityKeyPolicyTest {

    @Test
    fun `the listed digiscope hosts always use the shared identity`() {
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("digiscope.me"))
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose("api.digiscope.me"))
    }

    /** The shared identity goes to the hosts on the list, exactly: a name under digiscope.me is not one of them. */
    @Test
    fun `a digiscope subdomain that is not on the list gets a per-site identity`() {
        for (host in listOf("hub.digiscope.me", "a.digiscope.me", "www.digiscope.me", "x.api.digiscope.me", "api.api.digiscope.me")) {
            assertFalse("$host is treated as a DigiScope host", IdentityKeyPolicy.isDigiScopeDomain(host))
            assertEquals("$host gets the shared identity", IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(host))
        }
    }

    @Test
    fun `a lookalike domain is NOT treated as digiscope`() {
        for (host in listOf("notdigiscope.me", "digiscope.me.other.example", "adigiscope.me", "api-digiscope.me", "digiscope.me.", "api.digiscope.me.")) {
            assertFalse("$host is treated as a DigiScope host", IdentityKeyPolicy.isDigiScopeDomain(host))
            assertEquals("$host gets the shared identity", IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(host))
        }
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("notdigiscope.me"))
    }

    @Test
    fun `a new domain gets a per-site identity`() {
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("example.com"))
    }

    @Test
    fun `the list holds exactly the two digiscope hosts`() {
        assertEquals(setOf("digiscope.me", "api.digiscope.me"), IdentityKeyPolicy.DIGISCOPE_HOSTS)
        assertEquals(2, IdentityKeyPolicy.DIGISCOPE_HOSTS.size)
        assertTrue(IdentityKeyPolicy.DIGISCOPE_HOSTS.all(IdentityKeyPolicy::isDigiScopeDomain))
    }

    /**
     * The domain a request gets is lower-cased by the parser, as on iOS (`domain: host.lowercased()`);
     * the list is matched byte for byte, so the parser's spelling is the one that counts.
     */
    @Test
    fun `the domain comes from the parse`() {
        val r = DigiIdRequest.parse("digiid://API.DigiScope.me/api/digiid/callback?x=n")
        assertNotNull(r)
        assertEquals(IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose(r!!.domain))
        assertEquals("unparsed spellings are not on the list", IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose("API.DigiScope.me"))
    }

    // ── Beyond iOS's cases: the empty host, and the same rule on requests as parsed ─────────────────

    /** As iOS: an empty domain is not on the list, and a request with no host is refused before any choice. */
    @Test
    fun `an empty host gets no shared identity`() {
        assertFalse(IdentityKeyPolicy.isDigiScopeDomain(""))
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(""))
        assertNull(DigiIdRequest.parse("digiid:///callback?x=n"))
        assertNull(DigiIdRequest.parse("digiid:///api/digiid/callback?x=n"))
    }

    /**
     * The listed hosts as a request names them: any letter case (the parse lower-cases it) and a
     * port (the parse keeps it out of the domain) still sign with the shared identity, as on iOS,
     * whose parser reads the same domain from each of these.
     */
    @Test
    fun `a request to a listed host signs with the shared identity however its letters are cased`() {
        for (authority in listOf(
            "digiscope.me", "api.digiscope.me", "API.DigiScope.me", "DigiScope.ME", "DIGISCOPE.ME",
            "digiscope.me:443", "api.digiscope.me:8443",
        )) {
            val r = DigiIdRequest.parse("digiid://$authority/api/digiid/callback?x=n")
            assertNotNull("digiid://$authority is refused", r)
            assertTrue("$authority parses to ${r!!.domain}, not a listed host", r.domain in IdentityKeyPolicy.DIGISCOPE_HOSTS)
            assertEquals(authority, IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose(r.domain))
        }
    }

    /** Every host above that is not on the list, as a request names it: never the shared identity. */
    @Test
    fun `a request to any other host never signs with the shared identity`() {
        // Plain hosts, which both apps' parsers accept: each parses, and gets its per-site identity.
        for (host in listOf(
            "hub.digiscope.me", "a.digiscope.me", "www.digiscope.me", "x.api.digiscope.me", "api.api.digiscope.me",
            "notdigiscope.me", "digiscope.me.other.example", "adigiscope.me", "api-digiscope.me", "example.com",
        )) {
            val r = DigiIdRequest.parse("digiid://$host/api/digiid/callback?x=n")
            assertNotNull("digiid://$host is refused", r)
            assertEquals("digiid://$host gets the shared identity", IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(r!!.domain))
        }
        // A trailing dot is accepted here and refused on iOS: the parser difference of the 2026-11-13
        // hand-off's section 2, which waits for Mark. Either way, neither app signs it with the shared key.
        for (host in listOf("digiscope.me.", "api.digiscope.me.")) {
            val r = DigiIdRequest.parse("digiid://$host/api/digiid/callback?x=n") ?: continue
            assertNotEquals("digiid://$host gets the shared identity", IdentityKeyKind.LEGACY, IdentityKeyPolicy.choose(r.domain))
        }
    }
}
