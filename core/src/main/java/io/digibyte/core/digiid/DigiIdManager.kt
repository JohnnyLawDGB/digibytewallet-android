package io.digibyte.core.digiid

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.DigiIdHistoryDao
import io.digibyte.core.db.entity.DigiIdHistoryEntity
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.core.model.DigiIdResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DigiIdManager(
    private val httpClient: OkHttpClient,
    private val historyDao: DigiIdHistoryDao
) {
    suspend fun authenticate(request: DigiIdRequest): DigiIdResult = withContext(Dispatchers.IO) {
        try {
            // Sign the challenge via C core.
            // NativeBridge.signDigiIdChallenge() is a TODO — jni_digiid.c not yet implemented.
            // The flow works end-to-end but sends a placeholder signature until JNI signing lands.
            val address = NativeBridge.getReceiveAddress(0, 0)
                ?: return@withContext DigiIdResult.Error(1, "No wallet address available")

            // Build the digiid:// URI to echo back in the POST body (protocol requirement)
            val rawUri = "digiid://${request.callbackUrl
                .removePrefix("https://")
                .removePrefix("http://")}"

            // TODO: replace placeholder_signature with NativeBridge.signDigiIdChallenge(request.callbackUrl)
            //       once jni_digiid.c is implemented
            val json = JSONObject().apply {
                put("uri", rawUri)
                put("address", address)
                put("signature", "placeholder_signature")
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(request.callbackUrl)
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val success = response.isSuccessful

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
                DigiIdResult.Success(request.domain)
            } else {
                DigiIdResult.Error(response.code, "Authentication rejected by server (${response.code})")
            }
        } catch (e: Exception) {
            DigiIdResult.Error(-1, e.message ?: "Authentication failed")
        }
    }
}
