package io.digibyte.core.hub

data class Channel(val id: Int, val name: String, val description: String, val type: String)

data class ChatMessage(
    val id: Int,
    val content: String,
    val from: UserInfo,
    val timestamp: Long,
    val signature: String
)

data class UserInfo(val handle: String?, val address: String)

data class ForumThread(
    val id: Int,
    val title: String,
    val content: String,
    val author: UserInfo,
    val replyCount: Int,
    val upvotes: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class ThreadDetail(val thread: ForumThread, val replies: List<Reply>)

data class Reply(
    val id: Int,
    val content: String,
    val author: UserInfo,
    val upvotes: Int,
    val createdAt: Long
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
    data class Error(val message: String) : WebSocketEvent()
}

enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }
