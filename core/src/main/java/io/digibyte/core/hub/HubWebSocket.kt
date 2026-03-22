package io.digibyte.core.hub

import io.digibyte.core.digiscope.DigiScopeClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class HubWebSocket(
    private val httpClient: OkHttpClient,
    private val digiScopeClient: DigiScopeClient
) {
    companion object {
        const val WS_URL = "wss://api.digiscope.me/api/hub/ws"
    }

    private val _messages = MutableSharedFlow<WebSocketEvent>(replay = 0, extraBufferCapacity = 100)
    val messages: SharedFlow<WebSocketEvent> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0

    fun connect() {
        val token = digiScopeClient.getToken() ?: return
        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $token")
            .build()

        _connectionState.value = ConnectionState.RECONNECTING

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = parseEvent(json)
                    if (event != null) _messages.tryEmit(event)
                } catch (e: Exception) { /* ignore malformed */ }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // Auto-reconnect with exponential backoff would go here
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun joinChannel(channelId: Int) {
        webSocket?.send(JSONObject().put("type", "join").put("channelId", channelId).toString())
    }

    fun leaveChannel(channelId: Int) {
        webSocket?.send(JSONObject().put("type", "leave").put("channelId", channelId).toString())
    }

    fun sendMessage(channelId: Int, content: String, signature: String) {
        webSocket?.send(
            JSONObject()
                .put("type", "message")
                .put("channelId", channelId)
                .put("content", content)
                .put("signature", signature)
                .toString()
        )
    }

    fun sendTyping(channelId: Int) {
        webSocket?.send(JSONObject().put("type", "typing").put("channelId", channelId).toString())
    }

    private fun parseEvent(json: JSONObject): WebSocketEvent? {
        return when (json.optString("type")) {
            "message" -> {
                val from = json.optJSONObject("from")
                WebSocketEvent.Message(
                    channelId = json.getInt("channelId"),
                    message = ChatMessage(
                        id = json.optInt("id"),
                        content = json.getString("content"),
                        from = UserInfo(
                            handle = from?.optString("handle"),
                            address = from?.optString("address") ?: ""
                        ),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        signature = json.optString("signature", "")
                    )
                )
            }
            "typing" -> {
                val from = json.optJSONObject("from")
                WebSocketEvent.Typing(
                    channelId = json.getInt("channelId"),
                    from = UserInfo(
                        handle = from?.optString("handle"),
                        address = from?.optString("address") ?: ""
                    )
                )
            }
            "online_count" -> WebSocketEvent.OnlineCount(
                channelId = json.getInt("channelId"),
                count = json.getInt("count")
            )
            "error" -> WebSocketEvent.Error(json.optString("message", "Unknown error"))
            else -> null
        }
    }
}
