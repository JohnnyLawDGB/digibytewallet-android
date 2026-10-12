package app.aroundtheblock.wallet.core.digiscope

import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.DIGISCOPE_CHALLENGE
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.NONCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ID-001: quick login signs a challenge only when the strict Digi-ID parser accepts it as a login
 * for DigiScope itself — an HTTPS callback on the very host and port the signed login is submitted
 * to. Anything else is refused before it can be signed, and what is accepted is the exact text
 * that was checked.
 */
class QuickLoginChallengePolicyTest {

    private fun acceptedAmong(vararg challenges: String?): List<String?> =
        challenges.filter { QuickLoginChallengePolicy.accept(it) != null }

    @Test fun `a DigiScope login challenge is accepted, exactly as it was given`() {
        assertEquals(DIGISCOPE_CHALLENGE, QuickLoginChallengePolicy.accept(DIGISCOPE_CHALLENGE))
        // The same origin with its port written out, and the host in capitals, are the same login.
        val withPort = "digiid://api.digiscope.me:443/api/auth/digiid/callback?x=$NONCE"
        assertEquals(withPort, QuickLoginChallengePolicy.accept(withPort))
        val capitals = "digiid://API.DigiScope.me/api/auth/digiid/callback?x=$NONCE"
        assertEquals(capitals, QuickLoginChallengePolicy.accept(capitals))
    }

    @Test fun `a challenge for another host is refused`() {
        val accepted = acceptedAmong(
            "digiid://other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://digiscope.me/api/auth/digiid/callback?x=$NONCE",
            "digiid://hub.digiscope.me/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me:8443/api/auth/digiid/callback?x=$NONCE",
        )
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }

    @Test fun `a challenge for a look-alike host is refused`() {
        val accepted = acceptedAmong(
            "digiid://api.digiscope.me.other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://api-digiscope.me/api/auth/digiid/callback?x=$NONCE",
            "digiid://apidigiscope.me/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me./api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.mе/api/auth/digiid/callback?x=$NONCE", // a Cyrillic 'е'
            "digiid://api.digiscope.me%2eother.example/api/auth/digiid/callback?x=$NONCE",
        )
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }

    @Test fun `a challenge that puts user info before the host is refused`() {
        val accepted = acceptedAmong(
            "digiid://api.digiscope.me@other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me:443@other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://other.example@api.digiscope.me/api/auth/digiid/callback?x=$NONCE",
        )
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }

    @Test fun `a challenge with an insecure callback is refused`() {
        val accepted = acceptedAmong(
            "digiid://api.digiscope.me/api/auth/digiid/callback?x=$NONCE&u=1",
            "digiid://api.digiscope.me:80/api/auth/digiid/callback?x=$NONCE&u=1",
        )
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }

    @Test fun `empty or garbage input is refused`() {
        val accepted = acceptedAmong(
            null, "", " ", "null", "garbage", "digiid://", "digiid:///api/auth/digiid/callback?x=$NONCE",
            "https://api.digiscope.me/api/auth/digiid/callback?x=$NONCE",
            "DIGIID://api.digiscope.me/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me/api/auth/digiid/callback",            // no nonce
            "digiid://api.digiscope.me/api/auth/digiid/callback?x=",         // an empty nonce
            " $DIGISCOPE_CHALLENGE", "$DIGISCOPE_CHALLENGE ", "$DIGISCOPE_CHALLENGE\n", // not the text checked
        )
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }
}
