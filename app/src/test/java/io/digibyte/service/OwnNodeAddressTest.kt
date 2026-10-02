package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** [OwnNodeAddress]: with the user's Tor setting on, an own-node host name is never resolved. */
class OwnNodeAddressTest {

    private val lookups = mutableListOf<String>()
    private val recorder: (String) -> List<InetAddress> = { host ->
        lookups += host
        listOf(InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 7)))
    }

    @Test fun `with Tor on a host name is refused and not looked up`() {
        for (host in listOf("node.example.org", "abcdefghijklmnop.onion", "localhost")) {
            assertEquals(OwnNodeAddress.Result.RefusedUnderTor, OwnNodeAddress.resolve(host, torEnabled = true, lookup = recorder))
        }
        assertTrue("a lookup was made with Tor on: $lookups", lookups.isEmpty())
    }

    @Test fun `an IPv4 address is used as given, Tor on or off, with no lookup`() {
        for (tor in listOf(true, false)) {
            assertEquals(OwnNodeAddress.Result.Address("203.0.113.5"), OwnNodeAddress.resolve("203.0.113.5", torEnabled = tor, lookup = recorder))
        }
        assertTrue(lookups.isEmpty())
    }

    @Test fun `with Tor off a host name is resolved as before`() {
        assertEquals(OwnNodeAddress.Result.Address("10.0.0.7"), OwnNodeAddress.resolve("node.example.org", torEnabled = false, lookup = recorder))
        assertEquals(listOf("node.example.org"), lookups)
    }

    @Test fun `with Tor off a lookup that fails is reported, not thrown`() {
        val result = OwnNodeAddress.resolve("node.example.org", torEnabled = false, lookup = { throw java.net.UnknownHostException("nope") })
        assertTrue(result is OwnNodeAddress.Result.Unresolved)
    }

    @Test fun `only a dotted-quad IPv4 address counts as an address`() {
        assertEquals("10.1.2.3", OwnNodeAddress.ipv4Literal("010.1.2.3"))
        assertEquals("255.255.255.255", OwnNodeAddress.ipv4Literal(" 255.255.255.255 "))
        for (bad in listOf("256.1.1.1", "1.2.3", "1.2.3.4.5", "1.2.3.x", "0x7f.0.0.1", "-1.2.3.4", "1..2.3", "", "node.example.org", "1234.1.1.1")) {
            assertNull("accepted $bad", OwnNodeAddress.ipv4Literal(bad))
        }
    }
}
