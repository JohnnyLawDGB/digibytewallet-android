package app.aroundtheblock.wallet.ui.hub

/**
 * A Hub chat message exactly as it is sent, and the text its signature is made over (AND-009,
 * BB-2026-10-10-superisme #2). Both are made from one value here, so what is signed cannot differ from what
 * is sent.
 *
 * The text signed names the kind of post and where it goes before the post itself, so a
 * signature is valid only for the destination it was made for: not in another channel, not as
 * another kind of post, and never as a Digi-ID login (no text signed here starts with `digiid:`).
 * Line for line it is:
 *
 *     DigiScope Hub signed post v1
 *     kind: chat-message
 *     channel: <id>
 *     <an empty line>
 *     <the text, exactly as sent>
 *
 * The Hub also defines the kinds `forum-thread` and `forum-reply`. The app no longer posts to the
 * forum, but those kinds stay distinct from `chat-message`, so a chat signature still cannot be
 * taken for a forum post.
 *
 * Anyone checking a post rebuilds that text from the post and the place it appeared, and checks
 * the signature against the author's address.
 *
 * What is sent is what was typed less the blank characters at its two ends: every character the
 * Hub trims off a post (ECMAScript's trim) and every one Kotlin's trim takes, so the Hub stores
 * and shows exactly the text that was signed.
 */
class ChatSignPayload private constructor(
    /** The channel the message goes to. */
    val destination: Int,
    /** The text, as sent. */
    val content: String,
    /** The text the signature is made over. */
    val signedText: String,
) {
    companion object {
        private const val FORM = "DigiScope Hub signed post v1"

        /** A chat message to [channelId], or null when [typed] holds nothing to send. */
        fun chatMessage(channelId: Int, typed: String): ChatSignPayload? {
            val content = sendable(typed)
            if (content.isEmpty()) return null
            return ChatSignPayload(channelId, content,
                signed(listOf("kind: chat-message", "channel: $channelId"), content))
        }

        private fun signed(names: List<String>, content: String): String =
            (listOf(FORM) + names).joinToString("\n") + "\n\n" + content

        /** [typed] without the blank characters at its ends: Kotlin's whitespace, and the byte-order mark ECMAScript also trims. */
        private fun sendable(typed: String): String = typed.trim { it.isWhitespace() || it == '﻿' }
    }
}
