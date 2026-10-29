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
 * A post with an image is signed in the v2 form (website spec 2026-10-18 §6.3), which is v1 with
 * its first line changed and one line added after the channel, naming the attachment the post
 * carries, so a signature for an image post is valid only with that image, and a text-only
 * signature never covers an image:
 *
 *     DigiScope Hub signed post v2
 *     kind: chat-message
 *     channel: <id>
 *     attachment: <the id the upload answered with>
 *     <an empty line>
 *     <the text, exactly as sent; may be empty>
 *
 * A text-only post keeps the v1 form byte for byte; the `attachment:` line is never written
 * without an attachment.
 *
 * The Hub also defines the kinds `forum-thread` and `forum-reply`. The app no longer posts to the
 * forum, but those kinds stay distinct from `chat-message`, so a chat signature still cannot be
 * taken for a forum post.
 *
 * Anyone checking a v1 post rebuilds that text from the post and the place it appeared, and checks
 * the signature against the author's address. A v2 post can be checked the same way only once the
 * server exposes the attachment's id (`attachment.id`) with the message: today it returns the
 * attachment's path and size but not its id, so a third party cannot yet rebuild the
 * `attachment:` line (a server change is planned separately).
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
    /** The uploaded image the post carries, or null for a text-only post. */
    val attachmentId: Int? = null,
) {
    companion object {
        private const val FORM = "DigiScope Hub signed post v1"
        private const val FORM_V2 = "DigiScope Hub signed post v2"

        /**
         * A chat message to [channelId], or null when there is nothing to send: no text and no
         * attachment, or an [attachmentId] that is not an id the server could have issued. With
         * an attachment the text may be empty and the post is signed in the v2 form; without one
         * it is the v1 form, unchanged.
         */
        fun chatMessage(channelId: Int, typed: String, attachmentId: Int? = null): ChatSignPayload? {
            val content = sendable(typed)
            return when {
                attachmentId == null -> if (content.isEmpty()) null else ChatSignPayload(channelId, content,
                    signed(FORM, listOf("kind: chat-message", "channel: $channelId"), content))
                attachmentId <= 0 -> null
                else -> ChatSignPayload(channelId, content,
                    signed(FORM_V2, listOf("kind: chat-message", "channel: $channelId", "attachment: $attachmentId"), content),
                    attachmentId)
            }
        }

        private fun signed(form: String, names: List<String>, content: String): String =
            (listOf(form) + names).joinToString("\n") + "\n\n" + content

        /** [typed] without the blank characters at its ends: Kotlin's whitespace, and the byte-order mark ECMAScript also trims. */
        private fun sendable(typed: String): String = typed.trim { it.isWhitespace() || it == '﻿' }
    }
}
