package io.digibyte.core.digiid

import android.util.Log
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.DigiIdHistoryDao
import io.digibyte.core.db.entity.DigiIdHistoryEntity
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.core.model.DigiIdResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "DigiIdManager"

class DigiIdManager(
    private val httpClient: OkHttpClient,
    private val historyDao: DigiIdHistoryDao,
    private val digiScopeClient: DigiScopeClient
) {
    suspend fun authenticate(request: DigiIdRequest): DigiIdResult = withContext(Dispatchers.IO) {
        try {
            // Sign the original digiid:// URI (includes nonce) with the wallet's first BIP32 key.
            val signResult = NativeBridge.signMessage(request.rawUri, 0)
            if (signResult == null) {
                Log.e(TAG, "signMessage returned null — wallet locked or not initialized")
                return@withContext DigiIdResult.Error(1, "Wallet is locked — cannot sign")
            }

            val parts = signResult.split("|", limit = 2)
            if (parts.size != 2) {
                Log.e(TAG, "signMessage returned unexpected format: $signResult")
                return@withContext DigiIdResult.Error(1, "Signing failed — unexpected result")
            }
            val address = parts[0]
            val signature = parts[1]

            Log.i(TAG, "Digi-ID: signing with address $address for ${request.domain}")

            val json = JSONObject().apply {
                put("uri", request.rawUri)
                put("address", address)
                put("signature", signature)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(request.callbackUrl)
                .post(body)
                .build()

            Log.d(TAG, "POST to ${request.callbackUrl}")
            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            val success = response.isSuccessful

            Log.i(TAG, "Response: ${response.code} — $responseBody")

            // Persist to history regardless of outcome
            historyDao.insert(
                DigiIdHistoryEntity(
                    domain = request.domain,
                    callbackUrl = request.callbackUrl,
                    address = address,
                    timestamp = System.currentTimeMillis() / 1000,
                    success = success
                )
            )

            if (success) {
                // If this was a DigiScope login, capture the sessionToken from
                // the callback response and use it for Hub access.
                if (isDigiScopeDomain(request.domain) && responseBody != null) {
                    try {
                        val respJson = JSONObject(responseBody)
                        val sessionToken = respJson.optString("sessionToken", null)
                        if (sessionToken != null) {
                            digiScopeClient.persistToken(sessionToken)
                            Log.i(TAG, "Hub session token captured from callback")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract sessionToken: ${e.message}")
                    }
                }
                DigiIdResult.Success(request.domain)
            } else {
                DigiIdResult.Error(response.code, "Authentication rejected by server (${response.code})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "authenticate failed", e)
            DigiIdResult.Error(-1, e.message ?: "Authentication failed")
        }
    }

    private fun isDigiScopeDomain(domain: String): Boolean {
        return domain == "digiscope.me" ||
               domain == "api.digiscope.me" ||
               domain.endsWith(".digiscope.me")
    }
}
