package io.digibyte.core.digiscope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class DigiScopeProfile(
    val handle: String?,
    val address: String,
    val tipBalance: Long
)

class DigiScopeClient(
    baseClient: OkHttpClient
) {
    companion object {
        const val BASE_URL = "https://api.digiscope.me/api"
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

    private var jwtToken: String? = null

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
                .url("$BASE_URL/auth/digiid")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseJson = JSONObject(response.body?.string() ?: return@withContext null)
            val token = responseJson.optString("token", null)
            if (token != null) {
                jwtToken = token
            }
            token
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Register a pseudonymous handle.
     */
    suspend fun registerHandle(handle: String): Boolean = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext false
        try {
            val json = JSONObject().apply { put("handle", handle) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/user/handle")
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
                .url("$BASE_URL/user/profile")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            DigiScopeProfile(
                handle = json.optString("handle", null),
                address = json.optString("address", ""),
                tipBalance = json.optLong("tipBalance", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Set JWT token (e.g., loaded from EncryptedSharedPreferences).
     */
    fun setToken(token: String) { jwtToken = token }
    fun getToken(): String? = jwtToken
    fun isLoggedIn(): Boolean = jwtToken != null
}
