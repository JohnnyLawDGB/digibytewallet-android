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
 * signed names where it is sent — the kind of post and its channel or thread — so a signature is
 * valid only for the destination it was made for. The signed text is fixed below, line for line:
 * anyone checking a post rebuilds it from the post and the place it appeared.
 */
class ChatSignPayloadTest {

    private val header = "DigiScope Hub signed post v1"

    /** ECMAScript's String.prototype.trim set (WhiteSpace + LineTerminator): what the Hub trims off a post. */
    private val hubTrims = listOf(
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', '　', '﻿',
    )

    @Test fun `a chat message is signed as the text sent, bound to its channel`() {
        val post = ChatSignPayload.chatMessage(1, "  gm  ")!!
        assertEquals(1, post.destination)
        assertEquals("gm", post.content)
        assertEquals("$header\nkind: chat-message\nchannel: 1\n\ngm", post.signedText)
    }

    @Test fun `a thread is signed as its title and text sent, bound to its channel`() {
        val post = ChatSignPayload.forumThread(6, " Proposal ", "\n body \n")!!
        assertEquals(6, post.destination)
        assertEquals("Proposal", post.title)
        assertEquals("body", post.content)
        assertEquals("$header\nkind: forum-thread\nchannel: 6\ntitle: Proposal\n\nbody", post.signedText)
    }

    @Test fun `a reply is signed as the text sent, bound to its thread`() {
        val post = ChatSignPayload.forumReply(42, "yes ")!!
        assertEquals(42, post.destination)
        assertEquals("yes", post.content)
        assertEquals("$header\nkind: forum-reply\nthread: 42\n\nyes", post.signedText)
    }

    @Test fun `the text signed ends with exactly the text sent`() {
        val typed = listOf(
            "  leading spaces", "trailing spaces  ", "\ttabs\t", "\n\nnew lines\n", "\r\nwindows lines\r\n",
            "inner  spaces and\n\nlines stay", "emoji 😀 inside", " no-break ", "　ideographic　",
            "﻿byte order mark", "line separator ",
        )
        for (text in typed) {
            for (post in listOf(ChatSignPayload.chatMessage(1, text)!!, ChatSignPayload.forumReply(9, text)!!,
                    ChatSignPayload.forumThread(6, "t", text)!!)) {
                assertTrue("'$text': the signed text does not end with what is sent", post.signedText.endsWith("\n\n" + post.content))
                assertEquals("'$text'", text.trim { it.isWhitespace() || it == '﻿' }, post.content)
            }
        }
    }

    @Test fun `what is sent has no edge the Hub would trim off`() {
        for (c in hubTrims) {
            val name = "U+%04X".format(c.code)
            assertEquals(name, "gm", ChatSignPayload.chatMessage(1, "${c}gm$c")!!.content)
            assertEquals(name, "gm", ChatSignPayload.forumReply(1, "${c}gm$c")!!.content)
            val thread = ChatSignPayload.forumThread(6, "${c}title$c", "${c}body$c")!!
            assertEquals(name, "title", thread.title)
            assertEquals(name, "body", thread.content)
        }
    }

    @Test fun `the text inside a post is sent as typed`() {
        assertEquals("a  b\n\nc\td", ChatSignPayload.chatMessage(1, "a  b\n\nc\td")!!.content)
        assertEquals("a  b\n\nc", ChatSignPayload.forumThread(6, "t", "a  b\n\nc")!!.content)
    }

    @Test fun `the same text sent to another destination is signed differently`() {
        assertNotEquals(ChatSignPayload.chatMessage(1, "gm")!!.signedText, ChatSignPayload.chatMessage(2, "gm")!!.signedText)
        assertNotEquals(ChatSignPayload.forumReply(1, "yes")!!.signedText, ChatSignPayload.forumReply(2, "yes")!!.signedText)
        assertNotEquals(ChatSignPayload.forumThread(6, "t", "x")!!.signedText, ChatSignPayload.forumThread(7, "t", "x")!!.signedText)
    }

    @Test fun `a chat message, a thread and a reply never sign the same text`() {
        val texts = listOf(
            ChatSignPayload.chatMessage(6, "same")!!.signedText,
            ChatSignPayload.forumThread(6, "same", "same")!!.signedText,
            ChatSignPayload.forumReply(6, "same")!!.signedText,
        )
        assertEquals(texts.toString(), 3, texts.toSet().size)
    }

    @Test fun `a title is sent on one line, so the title and the text cannot trade lines`() {
        val one = ChatSignPayload.forumThread(6, "a", "b\nc")!!
        val other = ChatSignPayload.forumThread(6, "a\nb", "c")!!
        assertEquals("a b", other.title)
        assertNotEquals(one.signedText, other.signedText)
        for (breaker in listOf("\n", "\r", "\r\n", " ", " ", "\u0085")) {
            val post = ChatSignPayload.forumThread(6, "first${breaker}second", "body")!!
            assertEquals("first second", post.title)
            assertEquals("$header\nkind: forum-thread\nchannel: 6\ntitle: first second\n\nbody", post.signedText)
        }
    }

    @Test fun `nothing a Hub post signs is a Digi-ID login`() {
        val login = "digiid://site.example/login?x=00112233445566778899aabbccddeeff"
        for (post in listOf(ChatSignPayload.chatMessage(1, login)!!, ChatSignPayload.forumReply(1, login)!!,
                ChatSignPayload.forumThread(6, login, login)!!)) {
            assertFalse(post.signedText.startsWith("digiid:"))
            assertNull(DigiIdRequest.parse(post.signedText))
            assertTrue(post.signedText.startsWith("$header\n"))
        }
    }

    @Test fun `a post with nothing to send is not signed`() {
        assertNull(ChatSignPayload.chatMessage(1, ""))
        assertNull(ChatSignPayload.chatMessage(1, " \n\t "))
        assertNull(ChatSignPayload.chatMessage(1, "﻿　"))
        assertNull(ChatSignPayload.forumReply(1, "  "))
        assertNull(ChatSignPayload.forumThread(6, " ", "body"))
        assertNull(ChatSignPayload.forumThread(6, "title", "\n"))
        assertNull(ChatSignPayload.forumThread(6, "\n", "body"))
    }
}
