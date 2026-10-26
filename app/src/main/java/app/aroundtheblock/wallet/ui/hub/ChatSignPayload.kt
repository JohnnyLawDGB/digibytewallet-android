package app.aroundtheblock.wallet.ui.hub

/**
 * A Hub post exactly as it is sent, and the text its signature is made over (AND-009,
 * BB-2026-10-10-superisme #2). Both are made from one value here, so what is signed cannot differ from what
 * is sent.
 *
 * The text signed names the kind of post and where it goes before the post itself, so a
 * signature is valid only for the destination it was made for: not in another channel or
 * thread, not as another kind of post, and never as a Digi-ID login (no text signed here starts
 * with `digiid:`). Line for line it is:
 *
 *     DigiScope Hub signed post v1
 *     kind: chat-message | forum-thread | forum-reply
 *     channel: <id>    (a chat message or a thread)   or   thread: <id>    (a reply)
 *     title: <title>   (a thread only)
 *     <an empty line>
 *     <the text, exactly as sent>
 *
 * Anyone checking a post rebuilds that text from the post and the place it appeared, and checks
 * the signature against the author's address.
 *
 * What is sent is what was typed less the blank characters at its two ends: every character the
 * Hub trims off a post (ECMAScript's trim) and every one Kotlin's trim takes, so the Hub stores
 * and shows exactly the text that was signed. A title is sent on one line — a line break in it is
 * sent as a space — so a title cannot take a line of the text, nor the text a line of the title.
 */
class ChatSignPayload private constructor(
    /** The channel a chat message or a new thread goes to, or the thread a reply goes to. */
    val destination: Int,
    /** A new thread's title, as sent; empty for a chat message or a reply. */
    val title: String,
    /** The text, as sent. */
    val content: String,
    /** The text the signature is made over. */
    val signedText: String,
) {
    companion object {
        private const val FORM = "DigiScope Hub signed post v1"

        /** A line break in any spelling, and runs of them: a title is sent on one line. */
        private val LINE_BREAKS = Regex("[\r\n\u2028\u2029\u0085]+")

        /** A chat message to [channelId], or null when [typed] holds nothing to send. */
        fun chatMessage(channelId: Int, typed: String): ChatSignPayload? {
            val content = sendable(typed)
            if (content.isEmpty()) return null
            return ChatSignPayload(channelId, "", content,
                signed(listOf("kind: chat-message", "channel: $channelId"), content))
        }

        /** A new thread in [channelId], or null when its title or its text holds nothing to send. */
        fun forumThread(channelId: Int, title: String, content: String): ChatSignPayload? {
            val oneLine = sendable(title.replace(LINE_BREAKS, " "))
            val text = sendable(content)
            if (oneLine.isEmpty() || text.isEmpty()) return null
            return ChatSignPayload(channelId, oneLine, text,
                signed(listOf("kind: forum-thread", "channel: $channelId", "title: $oneLine"), text))
        }

        /** A reply to [threadId], or null when [content] holds nothing to send. */
        fun forumReply(threadId: Int, content: String): ChatSignPayload? {
            val text = sendable(content)
            if (text.isEmpty()) return null
            return ChatSignPayload(threadId, "", text,
                signed(listOf("kind: forum-reply", "thread: $threadId"), text))
        }

        private fun signed(names: List<String>, content: String): String =
            (listOf(FORM) + names).joinToString("\n") + "\n\n" + content

        /** [typed] without the blank characters at its ends: Kotlin's whitespace, and the byte-order mark ECMAScript also trims. */
        private fun sendable(typed: String): String = typed.trim { it.isWhitespace() || it == '\uFEFF' }
    }
}
