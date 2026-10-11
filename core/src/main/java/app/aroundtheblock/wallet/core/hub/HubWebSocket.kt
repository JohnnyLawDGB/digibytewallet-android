package app.aroundtheblock.wallet.core.hub

import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
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
import okio.ByteString
import okio.utf8Size
import org.json.JSONObject

class HubWebSocket(
    private val httpClient: OkHttpClient,
    private val digiScopeClient: DigiScopeClient
) {
    companion object {
        const val WS_URL = "wss://api.digiscope.me/api/hub/ws"

        /**
         * The largest frame the Hub socket accepts, in bytes of payload: 64 KiB. A chat message is
         * at most 2,000 characters (ChatView), far below this as JSON.
         */
        const val MAX_FRAME_BYTES = 64L * 1024

        /** The close code sent for a frame over [MAX_FRAME_BYTES]: 1009, message too big (RFC 6455). */
        const val CLOSE_MESSAGE_TOO_BIG = 1009
    }

    private val _messages = MutableSharedFlow<WebSocketEvent>(replay = 0, extraBufferCapacity = 100)
    val messages: SharedFlow<WebSocketEvent> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0

    fun connect() {
        val token = digiScopeClient.getToken()
        if (token == null) {
            android.util.Log.w("HubWS", "connect: no token, skipping")
            return
        }
        android.util.Log.i("HubWS", "connect: attempting WebSocket to $WS_URL")
        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $token")
            .build()

        _connectionState.value = ConnectionState.RECONNECTING

        webSocket = httpClient.newWebSocket(request, Listener())
    }

    /**
     * One connection's events. A frame over [MAX_FRAME_BYTES] is not parsed or passed on: the
     * connection is closed as message-too-big and torn down at once, and nothing more from it is
     * passed on. OkHttp hands a frame over only once it is wholly in memory, so what this protects
     * is that an oversized frame is never trusted and the connection that sent it ends.
     */
    private inner class Listener : WebSocketListener() {
        @Volatile private var refused = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.i("HubWS", "connected!")
            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (refused) return
            val size = text.utf8Size()
            if (size > MAX_FRAME_BYTES) {
                refuse(webSocket, size)
                return
            }
            try {
                val json = JSONObject(text)
                val event = parseEvent(json)
                if (event != null) _messages.tryEmit(event)
            } catch (e: Exception) { /* ignore malformed */ }
        }

        /** The Hub speaks text: a binary frame is ignored, as it always was, unless it is over the cap. */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!refused && bytes.size > MAX_FRAME_BYTES) refuse(webSocket, bytes.size.toLong())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.w("HubWS", "closed: $code $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.e("HubWS", "failure: ${t.message}, response=${response?.code}")
            _connectionState.value = ConnectionState.DISCONNECTED
            // Auto-reconnect with exponential backoff would go here
        }

        private fun refuse(webSocket: WebSocket, size: Long) {
            refused = true
            android.util.Log.w("HubWS", "a frame of $size bytes is over the $MAX_FRAME_BYTES-byte limit; closing")
            webSocket.close(CLOSE_MESSAGE_TOO_BIG, "frame over the size limit")
            webSocket.cancel()
            if (this@HubWebSocket.webSocket === webSocket) this@HubWebSocket.webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
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
