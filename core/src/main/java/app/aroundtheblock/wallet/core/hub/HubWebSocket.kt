package app.aroundtheblock.wallet.core.hub

import app.aroundtheblock.wallet.core.LogSafe
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.tor.TorRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.utf8Size
import org.json.JSONObject

/**
 * The Hub chat socket. At most one runs at a time, and none outlives the route it was opened on
 * (HUB-WS-TOR): each connection records the [TorRoute] in force when it was started, and it is
 * closed as soon as the route is another one — when [closeOnRouteChange] hears of the change, and
 * again, should that have been missed, when the socket opens and before any frame is sent on it.
 * Nothing is opened while the route is [TorRoute.Blocked] (Tor on and not connected yet): the
 * caller's ordinary retry opens the next socket once there is a route, which is then Tor's.
 */
class HubWebSocket(
    private val httpClient: OkHttpClient,
    private val digiScopeClient: DigiScopeClient,
    /** Where a connection made now would go; the app passes the Tor state holder's answer. */
    private val route: () -> TorRoute,
    /** Milliseconds on a clock that only moves forward; a parameter so tests can move it. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        const val WS_URL = "wss://api.digiscope.me/api/hub/ws"

        /**
         * The one Hub chat room. The server closed every other channel (website migration 141),
         * and it puts a socket in this room only when the socket sends a `join` for it.
         */
        const val CHAT_CHANNEL_ID = 1

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

    /** Guards [webSocket] and [listener]: connect, disconnect and a route change arrive on different threads. */
    private val lock = Any()
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var listener: Listener? = null

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
        synchronized(lock) {
            // One socket at a time: whatever is open, or still opening, is ended before another is started.
            endCurrent("Replaced by a new connection")
            val openedOn = route()
            if (openedOn is TorRoute.Blocked) {
                // Tor is on and not connected yet. Nothing is started; the caller's retry comes back.
                android.util.Log.i("HubWS", "connect: Tor is on and not connected yet, not connecting")
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }
            android.util.Log.i("HubWS", "connect: attempting WebSocket to $WS_URL")
            val request = Request.Builder()
                .url(WS_URL)
                .header("Authorization", "Bearer $token")
                .build()

            _connectionState.value = ConnectionState.RECONNECTING

            val listener = Listener(openedOn).also { this.listener = it }
            webSocket = httpClient.newWebSocket(request, listener)
        }
    }

    /**
     * Ends the current connection, open or still opening, without counting it as a refusal: the
     * close is ours. Cancelled as well as closed, so the connection is down now and not when the
     * server answers the close. Call with [lock] held.
     */
    private fun endCurrent(reason: String) {
        val socket = webSocket ?: return
        listener?.ended = true
        webSocket = null
        socket.close(1000, reason)
        socket.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Closes the socket if it was opened on a route other than [now] or than the route in force.
     * Returns true if it closed one. The caller's ordinary retry opens the next socket, on the
     * new route.
     */
    fun closeIfRouteChanged(now: TorRoute = route()): Boolean = synchronized(lock) {
        val openedOn = listener?.openedOn
        if (webSocket == null || openedOn == null) return false
        if (openedOn == now && openedOn == route()) return false
        android.util.Log.i("HubWS", "the route changed: closing the socket opened on the old one")
        endCurrent("Route changed")
        true
    }

    /**
     * Follows [routes] (the Tor state holder's route, as it changes) for as long as [scope] lives,
     * closing the socket whenever the route is no longer the one it was opened on.
     */
    fun closeOnRouteChange(routes: Flow<TorRoute>, scope: CoroutineScope): Job =
        scope.launch { routes.collect { closeIfRouteChanged(it) } }

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
    private inner class Listener(
        /** The route in force when this connection was started. */
        val openedOn: TorRoute,
    ) : WebSocketListener() {
        @Volatile private var refused = false
        @Volatile private var openedAtMs = -1L

        /** This connection's end has been counted (or it was ended by the user, which is not counted). */
        @Volatile var ended = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openedAtMs = nowMs()
            // A socket that opens after the user ended it, or after the Hub session it was opened
            // for is gone (logout, or a REST 401 that dropped the token mid-handshake), is closed at
            // once and never joined: left open and unjoined it would read as CONNECTED, so it would
            // never be replaced, and would later carry frames for a newer session. Closed as
            // sendAuthorized closes a socket with no session: by us, so not counted as a refusal.
            // The same for a socket that opens after the route changed under its handshake
            // (HUB-WS-TOR): it is never joined, and is cancelled as well, so it is down at once.
            val noSession = ended || digiScopeClient.getToken() == null
            val routeChanged = !noSession && route() != openedOn
            if (noSession || routeChanged) {
                android.util.Log.i("HubWS", if (routeChanged) "opened on a route no longer in force: closing" else "opened with no Hub session: closing")
                ended = true
                webSocket.close(1000, if (routeChanged) "Route changed" else "No Hub session")
                if (routeChanged) webSocket.cancel()
                if (this@HubWebSocket.webSocket === webSocket) this@HubWebSocket.webSocket = null
                // Only the current connection's listener speaks for the state: a stale one must not
                // overwrite a newer connection's.
                if (this@HubWebSocket.listener === this) _connectionState.value = ConnectionState.DISCONNECTED
                return
            }
            android.util.Log.i("HubWS", "connected!")
            // The server delivers nothing to a socket until it joins the room: join on every open,
            // the first connect and each reconnect alike. Sent on the socket that opened, which
            // may not be stored in [webSocket] yet.
            webSocket.send(joinFrame(CHAT_CHANNEL_ID))
            if (isCurrent()) _connectionState.value = ConnectionState.CONNECTED
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
            android.util.Log.w("HubWS", "closing: $code ${LogSafe.text(reason)}")
            webSocket.close(1000, null)
            end(refusal = !held())
            if (isCurrent()) _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.w("HubWS", "closed: $code ${LogSafe.text(reason)}")
            end(refusal = !held())
            if (isCurrent()) _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.e("HubWS", "failure: ${LogSafe.text(t.message)}, response=${response?.code}")
            end(refusal = !held())
            if (isCurrent()) _connectionState.value = ConnectionState.DISCONNECTED
        }

        /**
         * Only the current connection's listener speaks for the state: one that [connect] or a
         * route change has replaced ends later, and must not mark its successor disconnected.
         */
        private fun isCurrent(): Boolean = this@HubWebSocket.listener === this

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
            if (isCurrent()) _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun disconnect() = synchronized(lock) {
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
     * Nor does a frame go out on a socket opened on a route no longer in force (HUB-WS-TOR): it is
     * closed instead, and the frame is dropped as it is on any closed socket.
     */
    private fun sendAuthorized(frame: String) {
        if (digiScopeClient.getToken() == null) {
            if (webSocket != null) {
                android.util.Log.i("HubWS", "no Hub session: closing the socket instead of sending")
                disconnect()
            }
            return
        }
        if (closeIfRouteChanged()) return
        webSocket?.send(frame)
    }

    /** The join is sent only by [Listener.onOpen], on the socket that opened. */
    private fun joinFrame(channelId: Int): String =
        JSONObject().put("type", "join").put("channelId", channelId).toString()

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
                        from = UserInfo.fromJson(from),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        signature = json.optString("signature", "")
                    )
                )
            }
            "typing" -> {
                val from = json.optJSONObject("from")
                WebSocketEvent.Typing(
                    channelId = json.getInt("channelId"),
                    from = UserInfo.fromJson(from)
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
