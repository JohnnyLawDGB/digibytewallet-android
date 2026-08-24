package io.digibyte.core.ipfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CIDs arrive from several providers and not all of them are bare.
 *
 * digistamp's `/api/assets/{id}` returns `"cid":"ipfs://bafybeigqaz…"`, while the issuance-header
 * path produces a bare `bafybeigqaz…`. [IpfsClient.tryGateway] substitutes the value straight
 * into a gateway URL, so an un-normalised prefix becomes
 * `https://…/api/ipfs/ipfs://bafybei…` — a 404 that reads as "metadata offline", which is a
 * failure mode this codebase has already paid for twice.
 */
class IpfsCidNormalizeTest {

    @Test fun `a bare cid is unchanged`() {
        assertEquals(
            "bafybeigqazrxezd42nhv6mqhnq6qrljmzdev55y7kzcc2fxyrvzlkgjwne",
            IpfsClient.normalizeCid("bafybeigqazrxezd42nhv6mqhnq6qrljmzdev55y7kzcc2fxyrvzlkgjwne"),
        )
    }

    /** The exact shape digistamp returns. */
    @Test fun `an ipfs scheme prefix is stripped`() {
        assertEquals(
            "bafybeigqazrxezd42nhv6mqhnq6qrljmzdev55y7kzcc2fxyrvzlkgjwne",
            IpfsClient.normalizeCid("ipfs://bafybeigqazrxezd42nhv6mqhnq6qrljmzdev55y7kzcc2fxyrvzlkgjwne"),
        )
    }

    @Test fun `a gateway path prefix is stripped`() {
        assertEquals("QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co",
            IpfsClient.normalizeCid("/ipfs/QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co"))
    }

    @Test fun `surrounding whitespace is trimmed`() {
        assertEquals("bafkreiexample", IpfsClient.normalizeCid("  ipfs://bafkreiexample \n"))
    }

    /**
     * A CID is the integrity check — content is verified against it. Anything that could steer
     * the fetch somewhere else must be refused rather than cleaned up and used.
     */
    @Test fun `anything that is not a plain cid is refused`() {
        assertNull(IpfsClient.normalizeCid(null))
        assertNull(IpfsClient.normalizeCid(""))
        assertNull(IpfsClient.normalizeCid("   "))
        assertNull(IpfsClient.normalizeCid("https://evil.example/bafkreiexample"))
        assertNull(IpfsClient.normalizeCid("bafkrei/../../etc/passwd"))
        assertNull(IpfsClient.normalizeCid("bafkrei?format=raw&x=1"))
        assertNull(IpfsClient.normalizeCid("bafkrei example"))
    }
}
