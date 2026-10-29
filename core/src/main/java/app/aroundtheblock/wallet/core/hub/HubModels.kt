package app.aroundtheblock.wallet.core.hub

data class ChatMessage(
    val id: Int,
    val content: String,
    val from: UserInfo,
    val timestamp: Long,
    val signature: String,
    /** The image posted with the message, or null. A message may be an image alone (empty [content]). */
    val attachment: ChatAttachment? = null,
    /** The preview of the first link in [content], or null; it may arrive later as a [WebSocketEvent.Preview]. */
    val preview: LinkPreview? = null,
)

/** [userId] is the server's account id when it sent one (REST `user_id`, WS `from.userId`). */
data class UserInfo(val handle: String?, val address: String, val userId: Int? = null)

/**
 * An image attached to a chat message, as the server sends it. [url] is a path on the API host
 * (`/api/hub/media/<file>`), not a full URL: [HubMedia.imageUrl] makes the one the app loads.
 */
data class ChatAttachment(val url: String, val width: Int, val height: Int)

/**
 * The server's preview of a link in a message. Every field is what the linked page said about
 * itself, so all of it is untrusted text: shown as plain text, never as HTML.
 */
data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val siteName: String?,
    val imageUrl: String?,
)

data class HubProfile(
    val handle: String?,
    val address: String,
    val tipBalance: Long
)

sealed class WebSocketEvent {
    data class Message(val channelId: Int, val message: ChatMessage) : WebSocketEvent()
    data class Typing(val channelId: Int, val from: UserInfo) : WebSocketEvent()
    data class OnlineCount(val channelId: Int, val count: Int) : WebSocketEvent()
    /** The server's preview for message [messageId], sent a moment after the message; null clears it. */
    data class Preview(val channelId: Int, val messageId: Int, val preview: LinkPreview?) : WebSocketEvent()
    /** Message [id] was deleted (by its author or by staff). */
    data class Deleted(val channelId: Int, val id: Int) : WebSocketEvent()
    /** [code] is the server's machine code when it sent one, e.g. [HubJson.TRUST_L2_REQUIRED]. */
    data class Error(val message: String, val code: String? = null) : WebSocketEvent()
}

enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }

data class LeaderboardEntry(
    val rank: Int,
    val handle: String,
    val score: Int,
    val distance: Int,
    val coins: Int,
    val createdAt: Long
)

data class DigiRunnerStats(
    val bestScore: Int,
    val bestRank: Int,
    val totalGames: Int,
    val totalCoins: Int
)
