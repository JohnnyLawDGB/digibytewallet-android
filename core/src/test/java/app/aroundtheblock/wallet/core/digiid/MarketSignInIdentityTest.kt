package app.aroundtheblock.wallet.core.digiid

import app.aroundtheblock.wallet.core.digistamp.DigistampUris
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * MARKET-SIGNIN-ID-HOST: what the wallet shows, checks and signs for the Digi-ID request DigiStamp's
 * sign-in page offers. The request is the shape `GET https://id.digistamp.co/api/auth/digiid/challenge`
 * answered on 2026-11-21 (the nonce here is not a live one).
 */
class MarketSignInIdentityTest {

    private val offered = "digiid://id.digistamp.co/api/auth/digiid/callback?x=f327de4fb2dbf5c1a98d796f5535dd4a"

    /** The request the marketplace host issued until the move, for the comparison below. */
    private val beforeTheMove = "digiid://assets.digistamp.co/api/auth/digiid/callback?x=0b654336c3fcc835a5f325a8f3d4dc77"

    @Test fun `the approval names the sign-in host and the answer is posted to it`() {
        val request = DigiIdRequest.parse(offered)
        assertNotNull(request)
        assertEquals(DigistampUris.SIGN_IN_HOST, request!!.domain)
        assertEquals("id.digistamp.co", request.callbackHttpUrl.host)
        assertEquals("https://id.digistamp.co/api/auth/digiid/callback", request.callbackUrl)
        assertFalse("the callback is cleartext", request.isUnsecure)
        assertEquals("the URI signed is the URI offered", offered, request.rawUri)
    }

    /** Not a DigiScope host, so IdentityKeyPolicy gives it its own per-site identity. */
    @Test fun `the sign-in host signs with its per-site identity`() {
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(DigiIdRequest.parse(offered)!!.domain))
        assertEquals(IdentityKeyKind.PER_SITE, IdentityKeyPolicy.choose(DigiIdRequest.parse(beforeTheMove)!!.domain))
    }

    /**
     * The per-site key is derived from the callback URL (DigiIdManager: signIdentityMessage takes
     * `request.callbackUrl`), and the move changed that URL's host. So the address a wallet signs in
     * with at id.digistamp.co is NOT the one it signed in with at assets.digistamp.co: to DigiStamp
     * an existing user arrives as a new identity. Recorded here so the fact is not rediscovered.
     */
    @Test fun `the move changed the per-site key's derivation input`() {
        assertNotEquals(DigiIdRequest.parse(beforeTheMove)!!.callbackUrl, DigiIdRequest.parse(offered)!!.callbackUrl)
        assertEquals("https://assets.digistamp.co/api/auth/digiid/callback", DigiIdRequest.parse(beforeTheMove)!!.callbackUrl)
    }
}
