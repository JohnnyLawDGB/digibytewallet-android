package io.digibyte.core.digiscope

import android.content.Context
import io.digibyte.core.hub.Channel
import io.digibyte.core.hub.ChatMessage
import io.digibyte.core.hub.DigiRunnerStats
import io.digibyte.core.hub.ForumThread
import io.digibyte.core.hub.LeaderboardEntry
import io.digibyte.core.hub.Reply
import io.digibyte.core.hub.ThreadDetail
import io.digibyte.core.hub.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DigiScopeProfile(
    val handle: String?,
    val address: String,
    val tipBalance: Long
)

class DigiScopeClient(
    baseClient: OkHttpClient,
    context: Context
) {
    companion object {
        const val BASE_URL = "https://api.digiscope.me/api"
    }

    // Certificate pinning for api.digiscope.me — prevents MITM via rogue CA.
    // Shared pin set — see io.digibyte.core.network.DigiScopePins (one place to
    // update on rotation, so a stale pin can't linger in one client).
    private val client: OkHttpClient = baseClient.newBuilder()
        .certificatePinner(io.digibyte.core.network.DigiScopePins.certificatePinner())
        .build()

    private val tokenStore = HubTokenStore(context)

    private var jwtToken: String? = null

    init {
        loadToken()
    }

    // ── JWT persistence ───────────────────────────────────────────────────────

    fun persistToken(token: String) {
        tokenStore.save(token)
        jwtToken = token
    }

    fun loadToken() {
        jwtToken = tokenStore.load()
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /**
     * One-tap login — requests a Digi-ID challenge from the server, signs it
     * locally with the wallet's key, and authenticates. No QR scan needed.
     * Returns true on success.
     */
    suspend fun quickLogin(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Request challenge
            android.util.Log.i("DigiScope", "quickLogin: requesting challenge")
            val challengeReq = Request.Builder()
                .url("$BASE_URL/auth/digiid/challenge")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val challengeResp = client.newCall(challengeReq).execute()
            if (!challengeResp.isSuccessful) {
                android.util.Log.e("DigiScope", "quickLogin: challenge failed HTTP ${challengeResp.code}")
                return@withContext false
            }

            val challengeJson = JSONObject(challengeResp.body?.string() ?: return@withContext false)
            val uri = challengeJson.optString("uri", null) ?: return@withContext false
            android.util.Log.i("DigiScope", "quickLogin: got challenge, signing")

            // 2. Sign with wallet key
            val signResult = io.digibyte.core.bridge.NativeBridge.signMessage(uri, 0)
            if (signResult == null) {
                android.util.Log.e("DigiScope", "quickLogin: signMessage returned null — wallet locked?")
                return@withContext false
            }
            val parts = signResult.split("|", limit = 2)
            if (parts.size != 2) return@withContext false
            val address = parts[0]
            val signature = parts[1]
            android.util.Log.i("DigiScope", "quickLogin: signed, submitting callback")

            // 3. Submit to callback
            val token = login(address, signature, uri)
            android.util.Log.i("DigiScope", "quickLogin: token=${if (token != null) "received" else "null"}")
            token != null
        } catch (e: Exception) {
            android.util.Log.e("DigiScope", "quickLogin: exception: ${e.message}", e)
            false
        }
    }

    /**
     * Login to DigiScope using Digi-ID credentials.
     * Returns JWT token on success.
     */
    suspend fun login(address: String, signature: String, uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("address", address)
                put("signature", signature)
                put("uri", uri)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/auth/digiid/callback")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            // The callback body IS the session JWT — never log it, only the shape of the reply.
            android.util.Log.i("DigiScope", "login callback: HTTP ${response.code} bodyLength=${responseBody?.length ?: -1}")
            if (!response.isSuccessful || responseBody == null) return@withContext null

            val responseJson = JSONObject(responseBody)
            // Backend returns sessionToken (not token)
            val token = responseJson.optString("sessionToken",
                responseJson.optString("token", null))
            if (token != null) {
                persistToken(token)
            }
            token
        } catch (e: Exception) {
            null
        }
    }

    // ── User profile ──────────────────────────────────────────────────────────

    /**
     * Register a pseudonymous handle.
     */
    suspend fun registerHandle(handle: String): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val json = JSONObject().apply { put("handle", handle) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/hub/profile/handle")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Link a DGB address as the tip wallet.
     */
    suspend fun linkTipWallet(address: String): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val json = JSONObject().apply { put("address", address) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/user/tip/link")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the user's DigiScope profile.
     */
    suspend fun getProfile(): DigiScopeProfile? = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/profile")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!handleAuthResponse(response)) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val profile = json.optJSONObject("profile") ?: json
            DigiScopeProfile(
                handle = profile.optString("handle", null),
                address = profile.optString("address", ""),
                tipBalance = profile.optLong("tip_balance", profile.optLong("tipBalance", 0))
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── Hub REST: channels ────────────────────────────────────────────────────

    /**
     * Fetch all available hub channels.
     */
    suspend fun getChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/channels")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val array = JSONArray(response.body?.string() ?: return@withContext emptyList())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Channel(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    type = obj.optString("type", "text")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Hub REST: messages ────────────────────────────────────────────────────

    /**
     * Fetch recent messages for a channel. Optionally paginate backwards with [before] (Unix ms).
     */
    suspend fun getMessages(channelId: Int, before: Long? = null, limit: Int = 50): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val token = jwtToken ?: return@withContext emptyList()
            try {
                val urlBuilder = StringBuilder("$BASE_URL/hub/channels/$channelId/messages?limit=$limit")
                if (before != null) urlBuilder.append("&before=$before")

                val request = Request.Builder()
                    .url(urlBuilder.toString())
                    .header("Authorization", "Bearer $token")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val array = json.optJSONArray("messages") ?: JSONArray(bodyStr)
                parseChatMessages(array)
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ── Hub REST: threads ─────────────────────────────────────────────────────

    /**
     * Fetch paginated forum thread list for a channel.
     */
    suspend fun getThreads(channelId: Int, page: Int = 1): List<ForumThread> =
        withContext(Dispatchers.IO) {
            val token = jwtToken ?: return@withContext emptyList()
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/hub/threads?channelId=$channelId&page=$page")
                    .header("Authorization", "Bearer $token")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val array = json.optJSONArray("threads") ?: JSONArray(bodyStr)
                parseForumThreads(array)
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * Fetch a single thread with all its replies.
     */
    suspend fun getThread(threadId: Int): ThreadDetail? = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/threads/$threadId")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val thread = parseForumThread(json.getJSONObject("thread"))
            val repliesArray = json.optJSONArray("replies") ?: JSONArray()
            val replies = (0 until repliesArray.length()).map { i ->
                parseReply(repliesArray.getJSONObject(i))
            }
            ThreadDetail(thread = thread, replies = replies)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a new forum thread in a channel.
     */
    suspend fun createThread(
        channelId: Int,
        title: String,
        content: String,
        signature: String
    ): ForumThread? = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext null
        try {
            val json = JSONObject().apply {
                put("channelId", channelId)
                put("title", title)
                put("content", content)
                put("signature", signature)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/hub/threads")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val respJson = JSONObject(response.body?.string() ?: return@withContext null)
            parseForumThread(respJson.optJSONObject("thread") ?: respJson)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Post a reply to an existing thread.
     */
    suspend fun replyToThread(threadId: Int, content: String, signature: String): Reply? =
        withContext(Dispatchers.IO) {
            val token = jwtToken ?: return@withContext null
            try {
                val json = JSONObject().apply {
                    put("content", content)
                    put("signature", signature)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL/hub/threads/$threadId/reply")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val respJson = JSONObject(response.body?.string() ?: return@withContext null)
                parseReply(respJson.optJSONObject("reply") ?: respJson)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Upvote a thread. Returns true if the upvote was accepted.
     */
    suspend fun upvoteThread(threadId: Int): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/hub/threads/$threadId/upvote")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Hub REST: tipping ─────────────────────────────────────────────────────

    /**
     * Send a tip to another user by their handle. [amount] is in DGB satoshis.
     */
    suspend fun sendTip(toHandle: String, amount: Long): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val json = JSONObject().apply {
                put("toHandle", toHandle)
                put("amount", amount)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/hub/tip")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Hub REST: moderation ──────────────────────────────────────────────────

    /**
     * Report a message or thread. Exactly one of [messageId] / [threadId] should be non-null.
     */
    suspend fun reportContent(
        messageId: Int? = null,
        threadId: Int? = null,
        reason: String
    ): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val json = JSONObject().apply {
                if (messageId != null) put("messageId", messageId)
                if (threadId != null) put("threadId", threadId)
                put("reason", reason)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/hub/report")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Hub REST: handle availability ─────────────────────────────────────────

    /**
     * Check whether a hub handle is still available.
     */
    suspend fun checkHandleAvailable(handle: String): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/profile/handle/${handle.trim()}/available")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false

            val json = JSONObject(response.body?.string() ?: return@withContext false)
            json.optBoolean("available", false)
        } catch (e: Exception) {
            false
        }
    }

    // ── DigiRunner Leaderboard ────────────────────────────────────────────────

    suspend fun submitDigiRunnerScore(score: Int, distance: Int, coins: Int, livesRemaining: Int): Boolean =
        withContext(Dispatchers.IO) {
            val token = jwtToken
            if (token == null) {
                android.util.Log.e("DigiScope", "submitScore: no token")
                return@withContext false
            }
            android.util.Log.d("DigiScope", "submitScore: score=$score distance=$distance coins=$coins lives=$livesRemaining expected=${distance/100 + coins*5}")
            try {
                val json = JSONObject().apply {
                    put("score", score)
                    put("distance", distance)
                    put("coins", coins)
                    put("livesRemaining", livesRemaining)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL/hub/digirunner/score")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                val respBody = response.body?.string()
                android.util.Log.d("DigiScope", "submitScore: ${response.code} — $respBody")
                response.isSuccessful
            } catch (e: Exception) {
                android.util.Log.e("DigiScope", "submitScore exception: ${e.message}")
                false
            }
        }

    suspend fun getDigiRunnerLeaderboard(period: String = "all", limit: Int = 20): List<LeaderboardEntry> =
        withContext(Dispatchers.IO) {
            val token = jwtToken ?: return@withContext emptyList()
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/hub/digirunner/leaderboard?period=$period&limit=$limit")
                    .header("Authorization", "Bearer $token")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
                val arr = json.optJSONArray("leaderboard") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    LeaderboardEntry(
                        rank = e.optInt("rank", i + 1),
                        handle = e.optString("handle", "Anonymous"),
                        score = e.getInt("score"),
                        distance = e.optInt("distance", 0),
                        coins = e.optInt("coins", 0),
                        createdAt = e.optLong("createdAt", 0)
                    )
                }
            } catch (e: Exception) { emptyList() }
        }

    suspend fun getDigiRunnerStats(): DigiRunnerStats? = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/digirunner/me")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            DigiRunnerStats(
                bestScore = json.optInt("bestScore", 0),
                bestRank = json.optInt("bestRank", 0),
                totalGames = json.optInt("totalGames", 0),
                totalCoins = json.optInt("totalCoins", 0)
            )
        } catch (e: Exception) { null }
    }

    // ── Token helpers ─────────────────────────────────────────────────────────

    /** Check if a response indicates expired session, and clear token if so. */
    private fun handleAuthResponse(response: okhttp3.Response): Boolean {
        if (response.code == 401) {
            // Session expired — clear stale token so isLoggedIn() returns false
            jwtToken = null
            tokenStore.clear()
            return false
        }
        return response.isSuccessful
    }

    /** Override in-memory token (e.g. for testing). Use [persistToken] to also save to disk. */
    fun setToken(token: String) { jwtToken = token }
    fun getToken(): String? = jwtToken
    fun isLoggedIn(): Boolean = jwtToken != null

    fun logout() {
        jwtToken = null
        tokenStore.clear()
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun parseUserInfo(obj: JSONObject?): UserInfo = UserInfo(
        handle = obj?.optString("handle", null),
        address = obj?.optString("address", "") ?: ""
    )

    private fun parseChatMessages(array: JSONArray): List<ChatMessage> =
        (0 until array.length()).map { i -> parseChatMessage(array.getJSONObject(i)) }

    private fun parseChatMessage(obj: JSONObject): ChatMessage = ChatMessage(
        id = obj.optInt("id"),
        content = obj.getString("content"),
        from = parseUserInfo(obj.optJSONObject("from")),
        timestamp = parseDatetimeToMs(obj.optString("created_at", obj.optString("timestamp", ""))),
        signature = obj.optString("signature", "")
    )

    private fun parseForumThreads(array: JSONArray): List<ForumThread> =
        (0 until array.length()).map { i -> parseForumThread(array.getJSONObject(i)) }

    private fun parseForumThread(obj: JSONObject): ForumThread = ForumThread(
        id = obj.getInt("id"),
        title = obj.getString("title"),
        content = obj.optString("content", ""),
        author = parseUserInfo(obj.optJSONObject("author")),
        replyCount = obj.optInt("reply_count", obj.optInt("replyCount", 0)),
        upvotes = obj.optInt("upvotes", 0),
        createdAt = parseDatetimeToMs(obj.optString("created_at", obj.optString("createdAt", ""))),
        updatedAt = parseDatetimeToMs(obj.optString("updated_at", obj.optString("updatedAt", "")))
    )

    private fun parseReply(obj: JSONObject): Reply = Reply(
        id = obj.getInt("id"),
        content = obj.getString("content"),
        author = parseUserInfo(obj.optJSONObject("author")),
        upvotes = obj.optInt("upvotes", 0),
        createdAt = parseDatetimeToMs(obj.optString("created_at", obj.optString("createdAt", "")))
    )

    /** Parse SQLite DATETIME string ("2026-03-28 00:38:46") or Unix ms to epoch ms. */
    private fun parseDatetimeToMs(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        // Try as Unix ms (numeric)
        value.toLongOrNull()?.let { return it }
        // Parse as DATETIME string
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(value)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
