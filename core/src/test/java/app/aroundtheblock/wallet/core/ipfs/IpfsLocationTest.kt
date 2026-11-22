package app.aroundtheblock.wallet.core.ipfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IpfsLocation] is the one copy of "which IPFS object does this metadata text name": the picture
 * loader builds its model from it and the media model reads its CID from it, so the two cannot
 * disagree about whether two spellings are one file (review F3).
 */
class IpfsLocationTest {

    private val V1 = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
    private val V0 = "QmXibaWxG1xR479kfwkwCQEPabi9R81Z6DJaetaoJGNYHm"

    @Test fun `every spelling of one file is one location`() {
        for (cid in listOf(V0, V1)) {
            val all = listOf(
                cid, " $cid\n", "ipfs://$cid", "/ipfs/$cid", "ipfs://$cid/", "ipfs://$cid?filename=a.png", "ipfs://$cid#x",
                "https://ipfs.io/ipfs/$cid", "http://gw.example/ipfs/$cid?download=1", "HTTPS://gw.example/ipfs/$cid",
            ).map { it to IpfsLocation.parse(it)!! }
            for ((text, loc) in all) {
                assertEquals(text, cid, loc.cid)
                assertFalse(text, loc.hasPath)
                assertTrue(text, all.all { it.second.sameFileAs(loc) })
            }
        }
        // The subdomain form: the host is case-insensitive and a base32 CID survives lower-casing.
        val sub = IpfsLocation.parse("https://$V1.ipfs.dweb.link/")!!
        assertEquals(V1, sub.cid)
        assertFalse(sub.hasPath)
    }

    @Test fun `a path is part of which file it is`() {
        val bare = IpfsLocation.parse(V1)!!
        for (text in listOf("ipfs://$V1/logo.png", "/ipfs/$V1/logo.png", "https://gw.example/ipfs/$V1/logo.png?x=1", "https://$V1.ipfs.gw.example/logo.png")) {
            val loc = IpfsLocation.parse(text)!!
            assertEquals(text, V1, loc.cid)
            assertTrue(text, loc.hasPath)
            assertEquals(text, "/logo.png", loc.path)
            assertFalse("$text is not the bare CID's file", loc.sameFileAs(bare))
            assertTrue(loc.sameFileAs(IpfsLocation.parse("ipfs://$V1/logo.png")!!))
            assertFalse(loc.sameFileAs(IpfsLocation.parse("ipfs://$V1/other.png")!!))
        }
        assertFalse(IpfsLocation.parse(V0)!!.sameFileAs(bare))
    }

    @Test fun `the uri is what the picture loader has always been given`() {
        assertEquals("ipfs://$V1", IpfsLocation.parse(V1)!!.uri)
        assertEquals("ipfs://$V1", IpfsLocation.parse("/ipfs/$V1")!!.uri)
        assertEquals("ipfs://$V1/logo.png?x=1", IpfsLocation.parse("ipfs://$V1/logo.png?x=1")!!.uri)
        assertEquals("ipfs://$V1/logo.png", IpfsLocation.parse("https://ipfs.issuer.example/ipfs/$V1/logo.png?x=1")!!.uri)
        assertEquals("ipfs://$V1/logo.png", IpfsLocation.parse("https://$V1.ipfs.issuer.example/logo.png")!!.uri)
    }

    @Test fun `what names no IPFS object is no location`() {
        for (text in listOf(
            null, "", "  ", "null", "ipfs://null", "ipfs://", "/ipfs/", "ipfs://hello/world", "https://example.com/logo.png",
            "http://example.com/ipfs/notacid", "https://example.com/$V1", "data:image/png;base64,AAAA", "ar://x", "javascript:alert(1)",
            "file:///sdcard/a.png", "content://x/y", "IPFS://$V1",
        )) assertNull("'$text'", IpfsLocation.parse(text))
    }
}
