package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.core.hub.ChatMessage
import app.aroundtheblock.wallet.core.hub.UserInfo
import app.aroundtheblock.wallet.ui.hub.ChatText.Piece.Link
import app.aroundtheblock.wallet.ui.hub.ChatText.Piece.Plain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat text is shown as plain text; only `http(s)://` links in it are made tappable, and nothing in
 * it is read as markup. A preview names the host from its URL.
 */
class ChatTextTest {

    @Test fun `a link in a sentence is a link, without the sentence's punctuation`() {
        assertEquals(
            listOf(Plain("see "), Link("https://digiscope.me/hub"), Plain(". ok")),
            ChatText.pieces("see https://digiscope.me/hub. ok"),
        )
        assertEquals(listOf(Plain("("), Link("http://a.example/x"), Plain(")!")), ChatText.pieces("(http://a.example/x)!"))
    }

    @Test fun `text with no link is one plain piece, markup and all`() {
        val text = "<b>bold</b> <a href=\"javascript:alert(1)\">x</a> &amp;"
        assertEquals(listOf(Plain(text)), ChatText.pieces(text))
    }

    @Test fun `only http and https are links`() {
        for (text in listOf("javascript:alert(1)", "file:///etc/hosts", "intent://x#Intent;end", "ftp://a.example", "digiid://a.example/x")) {
            assertTrue(text, ChatText.pieces(text).none { it is Link })
        }
        assertFalse(ChatText.isWebLink("https://"))
    }

    @Test fun `the pieces put back together are the text exactly`() {
        val text = "a https://x.example/p?q=1, b http://y.example. https://z.example!!"
        assertEquals(text, ChatText.pieces(text).joinToString("") { it.text })
    }

    @Test fun `a preview names the host from its URL`() {
        assertEquals("example.org", ChatText.previewHost("https://www.example.org/a"))
        assertNull(ChatText.previewHost("javascript:alert(1)"))
    }

    // ── whose message is it ────────────────────────────────────────────────────

    private fun from(userId: Int?, address: String) = ChatMessage(1, "gm", UserInfo("h", address, userId), 0L, "")

    @Test fun `a message is mine by account id when both sides have one`() {
        assertTrue(isOwnMessage(from(5, "Dother"), myUserId = 5, myAddress = "Dme"))
        assertFalse(isOwnMessage(from(6, "Dme"), myUserId = 5, myAddress = "Dme"))
    }

    @Test fun `without account ids a message is mine by the identity address`() {
        assertTrue(isOwnMessage(from(null, "Dme"), myUserId = 5, myAddress = "Dme"))
        assertFalse(isOwnMessage(from(null, ""), myUserId = null, myAddress = ""))
    }
}
