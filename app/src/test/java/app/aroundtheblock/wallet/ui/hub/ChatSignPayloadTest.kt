package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.core.model.DigiIdRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AND-009 / BB-2026-10-10-superisme #2: a Hub post is signed over exactly the text that is sent, and the text
 * signed names where it is sent — the kind of post and its channel — so a signature is
 * valid only for the destination it was made for. The signed text is fixed below, line for line:
 * anyone checking a post rebuilds it from the post and the place it appeared.
 */
class ChatSignPayloadTest {

    private val header = "DigiScope Hub signed post v1"

    /** ECMAScript's String.prototype.trim set (WhiteSpace + LineTerminator): what the Hub trims off a post. */
    private val hubTrims = listOf(
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
        '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
    )

    @Test fun `a chat message is signed as the text sent, bound to its channel`() {
        val post = ChatSignPayload.chatMessage(1, "  gm  ")!!
        assertEquals(1, post.destination)
        assertEquals("gm", post.content)
        assertEquals("$header\nkind: chat-message\nchannel: 1\n\ngm", post.signedText)
    }

    @Test fun `the text signed ends with exactly the text sent`() {
        val typed = listOf(
            "  leading spaces", "trailing spaces  ", "\ttabs\t", "\n\nnew lines\n", "\r\nwindows lines\r\n",
            "inner  spaces and\n\nlines stay", "emoji \uD83D\uDE00 inside", "\u00A0no-break\u00A0", "\u3000ideographic\u3000",
            "\uFEFFbyte order mark", "line separator\u2028",
        )
        for (text in typed) {
            for (post in listOf(ChatSignPayload.chatMessage(1, text)!!)) {
                assertTrue("'$text': the signed text does not end with what is sent", post.signedText.endsWith("\n\n" + post.content))
                assertEquals("'$text'", text.trim { it.isWhitespace() || it == '\uFEFF' }, post.content)
            }
        }
    }

    @Test fun `what is sent has no edge the Hub would trim off`() {
        for (c in hubTrims) {
            val name = "U+%04X".format(c.code)
            assertEquals(name, "gm", ChatSignPayload.chatMessage(1, "${c}gm$c")!!.content)
        }
    }

    @Test fun `the text inside a post is sent as typed`() {
        assertEquals("a  b\n\nc\td", ChatSignPayload.chatMessage(1, "a  b\n\nc\td")!!.content)
    }

    @Test fun `the same text sent to another destination is signed differently`() {
        assertNotEquals(ChatSignPayload.chatMessage(1, "gm")!!.signedText, ChatSignPayload.chatMessage(2, "gm")!!.signedText)
    }

    /** The forum is gone from the app, but its kinds still exist on the Hub: a chat signature must never read as one. */
    @Test fun `a chat message is never signed as a forum post`() {
        val signed = ChatSignPayload.chatMessage(6, "same")!!.signedText
        assertEquals("$header\nkind: chat-message\nchannel: 6\n\nsame", signed)
        assertFalse(signed.contains("kind: forum-"))
        assertFalse(signed.contains("\nthread: "))
        assertFalse(signed.contains("\ntitle: "))
    }

    @Test fun `nothing a Hub post signs is a Digi-ID login`() {
        val login = "digiid://site.example/login?x=00112233445566778899aabbccddeeff"
        for (post in listOf(ChatSignPayload.chatMessage(1, login)!!)) {
            assertFalse(post.signedText.startsWith("digiid:"))
            assertNull(DigiIdRequest.parse(post.signedText))
            assertTrue(post.signedText.startsWith("$header\n"))
        }
    }

    @Test fun `a post with nothing to send is not signed`() {
        assertNull(ChatSignPayload.chatMessage(1, ""))
        assertNull(ChatSignPayload.chatMessage(1, " \n\t "))
        assertNull(ChatSignPayload.chatMessage(1, "\uFEFF\u3000"))
    }

    // ── v2: a post with an image (website spec 2026-10-18 §6.3) ─────────────────

    private val headerV2 = "DigiScope Hub signed post v2"

    @Test fun `a post with an image is signed in the v2 form, naming its attachment`() {
        val post = ChatSignPayload.chatMessage(1, "  look  ", attachmentId = 42)!!
        assertEquals("look", post.content)
        assertEquals(42, post.attachmentId)
        assertEquals("$headerV2\nkind: chat-message\nchannel: 1\nattachment: 42\n\nlook", post.signedText)
    }

    @Test fun `an image alone is a post, signed with empty text`() {
        val post = ChatSignPayload.chatMessage(1, " \n ", attachmentId = 7)!!
        assertEquals("", post.content)
        assertEquals("$headerV2\nkind: chat-message\nchannel: 1\nattachment: 7\n\n", post.signedText)
    }

    /**
     * A post without an attachment is the v1 form, byte for byte: no `attachment:` line and no v2
     * header. The bytes are the v1 vector above, spelled out as UTF-8.
     */
    @Test fun `a post without an image keeps the v1 bytes exactly`() {
        val vector = "DigiScope Hub signed post v1\nkind: chat-message\nchannel: 1\n\ngm".encodeToByteArray()
        for (post in listOf(ChatSignPayload.chatMessage(1, "gm")!!, ChatSignPayload.chatMessage(1, "gm", attachmentId = null)!!)) {
            assertTrue(vector.contentEquals(post.signedText.encodeToByteArray()))
            assertNull(post.attachmentId)
            assertFalse(post.signedText.contains("attachment"))
        }
    }

    @Test fun `the same text with another image, or with none, is signed differently`() {
        val a = ChatSignPayload.chatMessage(1, "gm", attachmentId = 1)!!.signedText
        val b = ChatSignPayload.chatMessage(1, "gm", attachmentId = 2)!!.signedText
        val none = ChatSignPayload.chatMessage(1, "gm")!!.signedText
        assertEquals(3, setOf(a, b, none).size)
    }

    @Test fun `an attachment id the server could not have issued is not signed`() {
        assertNull(ChatSignPayload.chatMessage(1, "gm", attachmentId = 0))
        assertNull(ChatSignPayload.chatMessage(1, "gm", attachmentId = -5))
    }

    @Test fun `an image post is never a Digi-ID login`() {
        val post = ChatSignPayload.chatMessage(1, "digiid://site.example/login?x=00", attachmentId = 3)!!
        assertTrue(post.signedText.startsWith("$headerV2\n"))
        assertNull(DigiIdRequest.parse(post.signedText))
    }
}
