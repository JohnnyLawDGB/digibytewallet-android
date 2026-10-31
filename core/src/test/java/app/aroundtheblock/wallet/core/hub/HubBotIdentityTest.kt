package app.aroundtheblock.wallet.core.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Hub bot badge comes from the server's flag, never from a display name (4.1.3 row 3). */
class HubBotIdentityTest {
    private fun from(json: String) = UserInfo.fromJson(JSONObject(json))

    @Test fun aNameThatReadsEnigmaIsNotTheBot() {
        assertFalse(from("""{"handle":"Enigma","address":"D1"}""").isBot)
        assertFalse(from("""{"handle":"enigma","address":"D1"}""").isBot)
        assertFalse(from("""{"handle":"ENIGMA","address":"D1"}""").isBot)
    }

    @Test fun onlyAJsonTrueMarksTheBot() {
        assertTrue(from("""{"handle":"Enigma","address":"D1","is_bot":true}""").isBot)
        assertFalse(from("""{"handle":"Enigma","address":"D1","is_bot":"true"}""").isBot)
        assertFalse(from("""{"handle":"Enigma","address":"D1","is_bot":1}""").isBot)
        assertFalse(from("""{"handle":"Enigma","address":"D1","is_bot":false}""").isBot)
    }

    @Test fun theOtherFieldsStillParse() {
        val u = from("""{"handle":"alice","address":"D9","is_bot":false}""")
        assertEquals("alice", u.handle)
        assertEquals("D9", u.address)
        assertFalse(UserInfo.fromJson(null).isBot)
    }
}
