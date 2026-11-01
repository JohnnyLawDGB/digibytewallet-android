package app.aroundtheblock.wallet.core.hub

data class ChatMessage(
    val id: Int,
    val content: String,
    val from: UserInfo,
    val timestamp: Long,
    val signature: String
)

/**
 * Who sent a Hub message. [isBot] is the server's word that this is the official bot's account
 * (`from.is_bot`, a JSON boolean); a display name never is (4.1.3 row 3): any account can register a
 * name, so a name that reads "Enigma" earns no badge.
 */
data class UserInfo(val handle: String?, val address: String, val isBot: Boolean = false) {
    companion object {
        /** The sender in a message's `from` object. Only a real JSON `true` marks the bot. */
        fun fromJson(from: org.json.JSONObject?): UserInfo = UserInfo(
            handle = from?.optString("handle", null),
            address = from?.optString("address", "") ?: "",
            isBot = from?.opt("is_bot") == true,
        )
    }
}

data class HubProfile(
    val handle: String?,
    val address: String,
    val tipBalance: Long
)

sealed class WebSocketEvent {
    data class Message(val channelId: Int, val message: ChatMessage) : WebSocketEvent()
    data class Typing(val channelId: Int, val from: UserInfo) : WebSocketEvent()
    data class OnlineCount(val channelId: Int, val count: Int) : WebSocketEvent()
    data class Error(val message: String) : WebSocketEvent()
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
