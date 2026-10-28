package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import app.aroundtheblock.wallet.core.hub.ChatMessage
import app.aroundtheblock.wallet.core.hub.DigiRunnerStats
import app.aroundtheblock.wallet.core.hub.HubJson
import app.aroundtheblock.wallet.core.hub.LeaderboardEntry
import app.aroundtheblock.wallet.core.hub.UserInfo
import app.aroundtheblock.wallet.core.network.BoundedBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DigiScopeProfile(
    val handle: String?,
    val address: String,
    val tipBalance: Long,
    /** The account's id on the server, the `userId` its chat messages carry; null when not sent. */
    val userId: Int? = null,
    /** The account's computed Trust Level (`trust_level`), 0 when not sent. */
    val trustLevel: Int = 0,
    /**
     * Whether the server lets this account post in chat (`can_post`: Trust Level 2). Only cosmetic
     * on the phone: the server refuses a post and an upload below L2 whatever this says.
     */
    val canPost: Boolean = false,
)

/** What `POST /hub/images` answered. */
sealed class HubImageUpload {
    /** The image is stored, not yet attached: send it with a message as `attachmentId` [id]. */
    data class Uploaded(val id: Int, val url: String, val width: Int, val height: Int) : HubImageUpload()

    /**
     * It was refused or not delivered. [message] is the server's own text when it sent one (shown
     * as is), [code] its machine code (`TRUST_L2_REQUIRED`, `TOO_LARGE`, `BAD_IMAGE`), [status] the
     * HTTP status, or 0 when no answer was read.
     */
    data class Failed(val message: String? = null, val code: String? = null, val status: Int = 0) : HubImageUpload()
}

/** What `POST /hub/report` answered. */
enum class HubReportResult {
    /** `{ reported: true }`. */
    REPORTED,
    /** 400: the message no longer exists (deleted since it was shown). */
    GONE,
    /** 429: the report limit (10 an hour) is reached. */
    LIMITED,
    /** Anything else, or no answer. */
    FAILED,
}

class DigiScopeClient(
    baseClient: OkHttpClient,
    context: Context,
    /**
     * Signs a quick-login challenge with the shared identity key, m/0'/0/0, and answers
     * "address|signature", or null when the wallet cannot sign. A parameter only so the login can
     * be driven without the native library; the app always takes this default.
     */
    private val signWithSharedIdentity: (String) -> String? = {
        app.aroundtheblock.wallet.core.bridge.NativeBridge.signMessage(it, 0)
    },
) {
    companion object {
        const val BASE_URL = "https://api.digiscope.me/api"

        /**
         * The most a single Hub answer may be: 64 KiB — a login challenge or token, a profile, a
         * thread or reply as created, the handle check, game stats and the leaderboard.
         */
        internal const val SMALL_BODY_BYTES = 64L * 1024

        /**
         * The most a Hub list may be: 4 MiB — a page of messages or threads, a thread
         * with its replies. A message is at most 2,000 characters and a thread 5,000.
         */
        internal const val LIST_BODY_BYTES = 4L * 1024 * 1024

        /** The largest image the server takes (hub.js multer `fileSize`): 8 MB. */
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        /** The image types the server takes: PNG, JPEG, GIF and WebP. */
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        /**
         * [ms] as the server writes `created_at` ("2026-10-28 12:00:00", UTC): the form its
         * `before` compares against. A number there compares as smaller than every stored time, so
         * a page asked for with one came back empty.
         */
        internal fun serverTime(ms: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(ms))
    }

    // Certificate pinning for api.digiscope.me — prevents MITM via rogue CA.
    // Shared pin set — see app.aroundtheblock.wallet.core.network.DigiScopePins (one place to
    // update on rotation, so a stale pin can't linger in one client).
    private val client: OkHttpClient = baseClient.newBuilder()
        .certificatePinner(app.aroundtheblock.wallet.core.network.DigiScopePins.certificatePinner())
        // A pinned host's 3xx is never followed to a host the pins do not cover (4.1.2 row 30, C18).
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * The client for the Digi-ID exchange — quick login's challenge and every signed login: the
     * pinned client with redirects off. A signed login reaches the callback it was made for or
     * nowhere: a redirect answer is a failed login and is never followed (ID-002).
     */
    private val digiIdClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
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
     *
     * It signs with the shared identity key, m/0'/0/0, on purpose: the Hub account is bound to
     * that key's address, and a per-site key would lock the user out of it. What keeps the
     * signature DigiScope's is the check of the challenge before anything is signed (ID-001,
     * [QuickLoginChallengePolicy]); the text checked is the text signed and the text submitted.
     */
    suspend fun quickLogin(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Request challenge
            android.util.Log.i("DigiScope", "quickLogin: requesting challenge")
            val challengeReq = Request.Builder()
                .url("$BASE_URL/auth/digiid/challenge")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val offered = digiIdClient.newCall(challengeReq).execute().use { challengeResp ->
                if (!challengeResp.isSuccessful) {
                    android.util.Log.e("DigiScope", "quickLogin: challenge failed HTTP ${challengeResp.code}")
                    return@withContext false
                }
                val challengeJson = JSONObject(BoundedBody.text(challengeResp.body, SMALL_BODY_BYTES) ?: return@withContext false)
                challengeJson.optString("uri", null)
            }

            // 2. Only a DigiScope login challenge is signed (ID-001)
            val uri = QuickLoginChallengePolicy.accept(offered)
            if (uri == null) {
                android.util.Log.e("DigiScope", "quickLogin: the challenge is not a DigiScope login; nothing signed")
                return@withContext false
            }
            android.util.Log.i("DigiScope", "quickLogin: got challenge, signing")

            // 3. Sign with wallet key
            val signResult = signWithSharedIdentity(uri)
            if (signResult == null) {
                android.util.Log.e("DigiScope", "quickLogin: signMessage returned null — wallet locked?")
                return@withContext false
            }
            val parts = signResult.split("|", limit = 2)
            if (parts.size != 2) return@withContext false
            val address = parts[0]
            val signature = parts[1]
            android.util.Log.i("DigiScope", "quickLogin: signed, submitting callback")

            // 4. Submit to callback
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

            val token = digiIdClient.newCall(request).execute().use { response ->
                val responseBody = BoundedBody.text(response.body, SMALL_BODY_BYTES)
                // The callback body IS the session JWT — never log it, only the shape of the reply.
                android.util.Log.i("DigiScope", "login callback: HTTP ${response.code} bodyLength=${responseBody?.length ?: -1}")
                if (!response.isSuccessful || responseBody == null) return@withContext null

                val responseJson = JSONObject(responseBody)
                // Backend returns sessionToken (not token)
                responseJson.optString("sessionToken",
                    responseJson.optString("token", null))
            }
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
            client.newCall(request).execute().use { it.isSuccessful }
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
            client.newCall(request).execute().use { it.isSuccessful }
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
            client.newCall(request).execute().use { response ->
                if (!handleAuthResponse(response)) return@withContext null

                val json = JSONObject(BoundedBody.text(response.body, SMALL_BODY_BYTES) ?: return@withContext null)
                val profile = json.optJSONObject("profile") ?: json
                DigiScopeProfile(
                    handle = profile.optString("handle", null),
                    address = profile.optString("address", ""),
                    tipBalance = profile.optLong("tip_balance", profile.optLong("tipBalance", 0)),
                    userId = HubJson.intOrNull(profile, "id"),
                    trustLevel = HubJson.intOrNull(profile, "trust_level") ?: 0,
                    canPost = profile.optBoolean("can_post", false),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Hub REST: messages ────────────────────────────────────────────────────

    /**
     * Fetch recent messages for a channel: the newest page, or with [before] (Unix ms) the page
     * older than it. [before] is sent in the server's own time form ([serverTime]).
     */
    suspend fun getMessages(channelId: Int, before: Long? = null, limit: Int = 50): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val token = jwtToken ?: return@withContext emptyList()
            try {
                val url = "$BASE_URL/hub/channels/$channelId/messages".toHttpUrl().newBuilder()
                    .addQueryParameter("limit", limit.toString())
                    .apply { if (before != null) addQueryParameter("before", serverTime(before)) }
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()

                    val bodyStr = BoundedBody.text(response.body, LIST_BODY_BYTES) ?: return@withContext emptyList()
                    val json = JSONObject(bodyStr)
                    val array = json.optJSONArray("messages") ?: JSONArray(bodyStr)
                    parseChatMessages(array)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ── Hub REST: moderation ──────────────────────────────────────────────────

    /**
     * Report chat message [messageId] to the moderators: `POST /hub/report` with `{ messageId }`
     * and nothing else (the server takes an optional reason; the app sends none).
     */
    suspend fun reportMessage(messageId: Int): HubReportResult = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext HubReportResult.FAILED
        try {
            val body = JSONObject().put("messageId", messageId).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/hub/report")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> { handleAuthResponse(response); HubReportResult.FAILED }
                    response.code == 400 -> HubReportResult.GONE
                    response.code == 429 -> HubReportResult.LIMITED
                    !response.isSuccessful -> HubReportResult.FAILED
                    else -> {
                        val json = BoundedBody.text(response.body, SMALL_BODY_BYTES)
                            ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        if (json?.optBoolean("reported", false) == true) HubReportResult.REPORTED else HubReportResult.FAILED
                    }
                }
            }
        } catch (e: Exception) {
            HubReportResult.FAILED
        }
    }

    // ── Hub REST: images ──────────────────────────────────────────────────────

    /**
     * Uploads one image for a chat post: `POST /hub/images`, multipart with exactly one part, a
     * file named `image`, and no other field (the server takes no fields and two parts at most).
     * [bytes] must be one of [IMAGE_TYPES] as [mimeType] and at most [MAX_IMAGE_BYTES]; anything
     * else is refused here without a request. The server checks the image again by its content.
     */
    suspend fun uploadImage(bytes: ByteArray, mimeType: String): HubImageUpload = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext HubImageUpload.Failed()
        if (mimeType !in IMAGE_TYPES) return@withContext HubImageUpload.Failed(code = "BAD_IMAGE")
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@withContext HubImageUpload.Failed(code = "TOO_LARGE")
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/images")
                .header("Authorization", "Bearer $token")
                .post(imageUploadBody(bytes, mimeType))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    handleAuthResponse(response)
                    HubImageUpload.Failed(status = 401)
                } else {
                    val json = BoundedBody.text(response.body, SMALL_BODY_BYTES)
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    imageUploadAnswer(response.code, json)
                }
            }
        } catch (e: Exception) {
            HubImageUpload.Failed()
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val json = JSONObject(BoundedBody.text(response.body, SMALL_BODY_BYTES) ?: return@withContext false)
                json.optBoolean("available", false)
            }
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
                client.newCall(request).execute().use { response ->
                    val respBody = BoundedBody.text(response.body, SMALL_BODY_BYTES)
                    android.util.Log.d("DigiScope", "submitScore: ${response.code} — $respBody")
                    response.isSuccessful
                }
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
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val json = JSONObject(BoundedBody.text(response.body, SMALL_BODY_BYTES) ?: return@withContext emptyList())
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(BoundedBody.text(response.body, SMALL_BODY_BYTES) ?: return@withContext null)
                DigiRunnerStats(
                    bestScore = json.optInt("bestScore", 0),
                    bestRank = json.optInt("bestRank", 0),
                    totalGames = json.optInt("totalGames", 0),
                    totalCoins = json.optInt("totalCoins", 0)
                )
            }
        } catch (e: Exception) { null }
    }

    /** What an upload's answer means: [status] and its body as JSON (null when it was not JSON or over the cap). */
    private fun imageUploadAnswer(status: Int, json: JSONObject?): HubImageUpload {
        val id = json?.let { HubJson.intOrNull(it, "id") }?.takeIf { it > 0 }
        val url = json?.let { HubJson.text(it, "url") }
        return when {
            json == null -> HubImageUpload.Failed(status = status)
            status !in 200..299 -> HubImageUpload.Failed(HubJson.text(json, "error"), HubJson.text(json, "code"), status)
            id == null || url == null -> HubImageUpload.Failed(status = status)
            else -> HubImageUpload.Uploaded(id, url, HubJson.intOrNull(json, "width") ?: 0, HubJson.intOrNull(json, "height") ?: 0)
        }
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

    /**
     * Ends the Hub session on the phone and on the server. The phone forgets the token first, so
     * it is logged out whatever the network does; then the token is sent once to
     * `POST /api/auth/logout`, which deletes the session, so a copy of the token made earlier
     * stops working too. Not followed through a redirect. The answer is only closed: a failed
     * revocation leaves the phone logged out all the same, and the session to expire on its own.
     */
    fun logout() {
        val token = jwtToken
        jwtToken = null
        tokenStore.clear()
        if (!token.isNullOrBlank()) revokeSession(token)
    }

    private fun revokeSession(token: String) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/auth/logout")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            digiIdClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    android.util.Log.w("DigiScope", "session revoke not delivered: ${e.javaClass.simpleName}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { if (!it.isSuccessful) android.util.Log.w("DigiScope", "session revoke answered ${it.code}") }
                }
            })
        } catch (e: Exception) {
            android.util.Log.w("DigiScope", "session revoke not sent: ${e.javaClass.simpleName}")
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun parseUserInfo(obj: JSONObject?, userId: Int?): UserInfo = UserInfo(
        handle = obj?.optString("handle", null),
        address = obj?.optString("address", "") ?: "",
        userId = userId,
    )

    private fun parseChatMessages(array: JSONArray): List<ChatMessage> =
        (0 until array.length()).map { i -> parseChatMessage(array.getJSONObject(i)) }

    private fun parseChatMessage(obj: JSONObject): ChatMessage {
        // The REST row is flat (handle, address, user_id); a nested `from` is taken when present.
        val from = obj.optJSONObject("from")
        return ChatMessage(
            id = obj.optInt("id"),
            // An image-only post carries empty text.
            content = HubJson.text(obj, "content") ?: "",
            from = if (from != null) {
                parseUserInfo(from, HubJson.intOrNull(from, "userId"))
            } else {
                UserInfo(
                    handle = HubJson.text(obj, "handle"),
                    address = HubJson.text(obj, "address") ?: "",
                    userId = HubJson.intOrNull(obj, "user_id"),
                )
            },
            timestamp = parseDatetimeToMs(obj.optString("created_at", obj.optString("timestamp", ""))),
            signature = obj.optString("signature", ""),
            attachment = HubJson.attachment(obj),
            preview = HubJson.preview(obj),
        )
    }

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

/**
 * The body of `POST /hub/images`: multipart form data with one part, a file named `image` with
 * [mimeType], and nothing else. The server (multer: `fields: 0`, `parts: 2`, `.single('image')`)
 * refuses any other field, and takes a part as a file only when it has a file name.
 */
internal fun imageUploadBody(bytes: ByteArray, mimeType: String): MultipartBody =
    MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("image", "image", bytes.toRequestBody(mimeType.toMediaType()))
        .build()
