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
    private val digiScopeClient: DigiScopeClient,
    /** Milliseconds on a clock that only moves forward; a parameter so tests can move it. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
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

        /** The wait before the next try when nothing has been refused: soon after a login. */
        const val RECONNECT_FIRST_MS = 5_000L

        /** The longest wait between tries. */
        const val RECONNECT_MAX_MS = 5 * 60_000L

        /** A connection that stayed up this long was accepted, and its end starts the wait over. */
        const val ACCEPTED_AFTER_MS = 60_000L

        /** The wait after [refusals] refusals in a row: doubled for each, from [RECONNECT_FIRST_MS] up to [RECONNECT_MAX_MS]. */
        fun reconnectDelayAfter(refusals: Int): Long =
            (RECONNECT_FIRST_MS shl refusals.coerceIn(0, 16)).coerceAtMost(RECONNECT_MAX_MS)
    }

    private val _messages = MutableSharedFlow<WebSocketEvent>(replay = 0, extraBufferCapacity = 100)
    val messages: SharedFlow<WebSocketEvent> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null

    /**
     * Connections refused in a row: one that failed, one the server ended within
     * [ACCEPTED_AFTER_MS] of opening it (it opens the socket and then closes it when it does not
     * accept the session), or one cut for a frame over the cap. A connection that held resets it.
     */
    @Volatile private var refusals = 0

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

        val listener = Listener().also { this.listener = it }
        webSocket = httpClient.newWebSocket(request, listener)
    }

    /**
     * How long to wait before trying again: [reconnectDelayAfter] the refusals in a row so far. A
     * server that keeps refusing is asked less and less often, up to once every 5 minutes.
     */
    fun reconnectDelayMs(): Long = reconnectDelayAfter(refusals)

    /**
     * One connection's events. A frame over [MAX_FRAME_BYTES] is not parsed or passed on: the
     * connection is closed as message-too-big and torn down at once, and nothing more from it is
     * passed on. OkHttp hands a frame over only once it is wholly in memory, so what this protects
     * is that an oversized frame is never trusted and the connection that sent it ends.
     */
    private inner class Listener : WebSocketListener() {
        @Volatile private var refused = false
        @Volatile private var openedAtMs = -1L

        /** This connection's end has been counted (or it was ended by the user, which is not counted). */
        @Volatile var ended = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.i("HubWS", "connected!")
            openedAtMs = nowMs()
            _connectionState.value = ConnectionState.CONNECTED
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

        /** The server is closing: answer at once, so the connection is down now, not when the server gives up waiting. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.w("HubWS", "closing: $code $reason")
            webSocket.close(1000, null)
            end(refusal = !held())
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.w("HubWS", "closed: $code $reason")
            end(refusal = !held())
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.e("HubWS", "failure: ${t.message}, response=${response?.code}")
            end(refusal = !held())
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        /** True once this connection has stayed up [ACCEPTED_AFTER_MS]. */
        private fun held(): Boolean = openedAtMs >= 0 && nowMs() - openedAtMs >= ACCEPTED_AFTER_MS

        /** Counts this connection's end, once: a refusal lengthens the wait, a connection that held starts it over. */
        private fun end(refusal: Boolean) {
            if (ended) return
            ended = true
            refusals = if (refusal) refusals + 1 else 0
        }

        private fun refuse(webSocket: WebSocket, size: Long) {
            refused = true
            end(refusal = true)
            android.util.Log.w("HubWS", "a frame of $size bytes is over the $MAX_FRAME_BYTES-byte limit; closing")
            webSocket.close(CLOSE_MESSAGE_TOO_BIG, "frame over the size limit")
            webSocket.cancel()
            if (this@HubWebSocket.webSocket === webSocket) this@HubWebSocket.webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun disconnect() {
        // The user's own close is not a refusal: it is never counted.
        listener?.ended = true
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Every frame goes out only while the Hub session it was opened for is still held (4.1.2 row 8, N4).
     * A socket outlives a logout unless something closes it, and the server knows it by the token it
     * was opened with: with no token now, nothing is sent and the socket is closed instead.
     */
    private fun sendAuthorized(frame: String) {
        if (digiScopeClient.getToken() == null) {
            if (webSocket != null) {
                android.util.Log.i("HubWS", "no Hub session: closing the socket instead of sending")
                disconnect()
            }
            return
        }
        webSocket?.send(frame)
    }

    fun joinChannel(channelId: Int) {
        sendAuthorized(JSONObject().put("type", "join").put("channelId", channelId).toString())
    }

    fun leaveChannel(channelId: Int) {
        sendAuthorized(JSONObject().put("type", "leave").put("channelId", channelId).toString())
    }

    fun sendMessage(channelId: Int, content: String, signature: String) {
        sendAuthorized(
            JSONObject()
                .put("type", "message")
                .put("channelId", channelId)
                .put("content", content)
                .put("signature", signature)
                .toString()
        )
    }

    fun sendTyping(channelId: Int) {
        sendAuthorized(JSONObject().put("type", "typing").put("channelId", channelId).toString())
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
