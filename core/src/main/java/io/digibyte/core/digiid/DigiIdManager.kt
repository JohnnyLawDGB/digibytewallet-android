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
            // ── Security: validate callback domain matches URI ──
            // CRITICAL-4: The callback URL is derived from the scanned QR.
            // Verify it actually points to the domain shown to the user.
            val callbackHost = try {
                java.net.URL(request.callbackUrl).host
            } catch (e: Exception) { "" }

            if (callbackHost.isEmpty()) {
                return@withContext DigiIdResult.Error(1, "Invalid callback URL")
            }
            if (callbackHost != request.domain && !callbackHost.endsWith(".${request.domain}")) {
                Log.e(TAG, "Callback host mismatch: $callbackHost vs ${request.domain}")
                return@withContext DigiIdResult.Error(1, "Callback domain doesn't match — possible phishing")
            }

            // HIGH-3: Block plaintext HTTP callbacks — signatures must never travel in cleartext
            if (request.isUnsecure) {
                Log.w(TAG, "Rejecting insecure (HTTP) Digi-ID callback to ${request.domain}")
                return@withContext DigiIdResult.Error(1, "Insecure (HTTP) authentication not allowed")
            }

            // Pick the identity: legacy m/0'/0/0 for DigiScope + grandfathered domains,
            // per-site SLIP-0013 (m/13'/...) for new ones — docs/specs/digiid-key-isolation.md.
            val hasLegacyHistory = historyDao.countSuccessfulLegacy(request.domain) > 0
            val keyKind = IdentityKeyPolicy.choose(request.domain, hasLegacyHistory)

            // Sign the original digiid:// URI (includes nonce) with the chosen identity key.
            val signResult = when (keyKind) {
                IdentityKeyKind.LEGACY -> NativeBridge.signMessage(request.rawUri, 0)
                // Derivation input is the canonical callback URL (no query, so no nonce):
                // stable per site, unlinkable across sites.
                IdentityKeyKind.PER_SITE ->
                    NativeBridge.signIdentityMessage(request.rawUri, request.callbackUrl, 0)
            }
            if (signResult == null) {
                Log.e(TAG, "signMessage returned null — wallet locked or not initialized")
                return@withContext DigiIdResult.Error(1, "Wallet is locked — cannot sign")
            }

            val parts = signResult.split("|", limit = 2)
            if (parts.size != 2) {
                // HIGH-1: Don't log the full signResult — contains address
                Log.e(TAG, "signMessage returned unexpected format (length=${signResult.length})")
                return@withContext DigiIdResult.Error(1, "Signing failed — unexpected result")
            }
            val address = parts[0]
            val signature = parts[1]

            Log.d(TAG, "Digi-ID: authenticating with ${request.domain}")

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

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            val success = response.isSuccessful

            // MEDIUM-2: Don't log response body — could contain tokens or attacker content
            Log.d(TAG, "Digi-ID response: ${response.code}")

            // Persist to history regardless of outcome
            historyDao.insert(
                DigiIdHistoryEntity(
                    domain = request.domain,
                    callbackUrl = request.callbackUrl,
                    address = address,
                    timestamp = System.currentTimeMillis() / 1000,
                    success = success,
                    derivation = if (keyKind == IdentityKeyKind.LEGACY) "legacy" else "site"
                )
            )

            if (success) {
                // If this was a DigiScope login, authenticate the WALLET's own
                // identity with the backend to get a Hub session token.
                // The callback token belongs to whoever's browser generated the QR —
                // the wallet needs its own session tied to its own address.
                if (isDigiScopeDomain(request.domain)) {
                    Log.i(TAG, "DigiScope domain — authenticating wallet identity for Hub")
                    val jwt = digiScopeClient.login(address, signature, request.rawUri)
                    if (jwt != null) {
                        Log.i(TAG, "Hub JWT obtained for wallet identity")
                    } else {
                        Log.w(TAG, "Hub JWT login failed — Hub will be disconnected")
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

    private fun isDigiScopeDomain(domain: String): Boolean =
        IdentityKeyPolicy.isDigiScopeDomain(domain)
}
