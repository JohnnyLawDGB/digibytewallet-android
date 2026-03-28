package io.digibyte.core.digiscope

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.hub.Channel
import io.digibyte.core.hub.ChatMessage
import io.digibyte.core.hub.ForumThread
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
        private const val PREFS_NAME = "dgb_digiscope"
        private const val KEY_JWT = "dgb_jwt"
    }

    // Client with certificate pinning for api.digiscope.me
    // Extract actual pins during deployment — use intermediate CA pins
    private val client: OkHttpClient = baseClient.newBuilder()
        // Certificate pinning — pins extracted from api.digiscope.me cert chain
        // Using intermediate CA for longer validity
        // TODO: extract real pins at deployment time
        // .certificatePinner(CertificatePinner.Builder()
        //     .add("api.digiscope.me", "sha256/ACTUAL_PIN_HERE")
        //     .add("api.digiscope.me", "sha256/BACKUP_PIN_HERE")
        //     .build())
        .build()

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var jwtToken: String? = null

    init {
        loadToken()
    }

    // ── JWT persistence ───────────────────────────────────────────────────────

    fun persistToken(token: String) {
        prefs.edit().putString(KEY_JWT, token).apply()
        jwtToken = token
    }

    fun loadToken() {
        jwtToken = prefs.getString(KEY_JWT, null)
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

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
            if (!response.isSuccessful) return@withContext null

            val responseJson = JSONObject(response.body?.string() ?: return@withContext null)
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
            if (!response.isSuccessful) return@withContext null

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

    // ── Token helpers ─────────────────────────────────────────────────────────

    /** Override in-memory token (e.g. for testing). Use [persistToken] to also save to disk. */
    fun setToken(token: String) { jwtToken = token }
    fun getToken(): String? = jwtToken
    fun isLoggedIn(): Boolean = jwtToken != null

    fun logout() {
        jwtToken = null
        prefs.edit().remove(KEY_JWT).apply()
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
        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
        signature = obj.optString("signature", "")
    )

    private fun parseForumThreads(array: JSONArray): List<ForumThread> =
        (0 until array.length()).map { i -> parseForumThread(array.getJSONObject(i)) }

    private fun parseForumThread(obj: JSONObject): ForumThread = ForumThread(
        id = obj.getInt("id"),
        title = obj.getString("title"),
        content = obj.optString("content", ""),
        author = parseUserInfo(obj.optJSONObject("author")),
        replyCount = obj.optInt("replyCount", 0),
        upvotes = obj.optInt("upvotes", 0),
        createdAt = obj.optLong("createdAt", 0),
        updatedAt = obj.optLong("updatedAt", 0)
    )

    private fun parseReply(obj: JSONObject): Reply = Reply(
        id = obj.getInt("id"),
        content = obj.getString("content"),
        author = parseUserInfo(obj.optJSONObject("author")),
        upvotes = obj.optInt("upvotes", 0),
        createdAt = obj.optLong("createdAt", 0)
    )
}
