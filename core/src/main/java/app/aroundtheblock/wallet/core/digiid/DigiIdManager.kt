package app.aroundtheblock.wallet.core.digiid

import android.util.Log
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.core.db.dao.DigiIdHistoryDao
import app.aroundtheblock.wallet.core.db.entity.DigiIdHistoryEntity
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import app.aroundtheblock.wallet.core.model.DigiIdResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "DigiIdManager"

class DigiIdManager(
    baseClient: OkHttpClient,
    private val historyDao: DigiIdHistoryDao,
    private val digiScopeClient: DigiScopeClient,
    /**
     * Signs the login URI with the identity [IdentityKeyKind] names and answers
     * "address|signature", or null when the wallet cannot sign. A parameter only so a login can be
     * driven without the native library; the app always takes this default.
     */
    private val sign: (DigiIdRequest, IdentityKeyKind) -> String? = ::signNatively,
    /** The address an identity would sign with, without signing; a parameter for the same reason. */
    private val address: (DigiIdRequest, IdentityKeyKind) -> String? = ::addressNatively,
    /** False for a wallet created on this device: it is never offered the shared identity (row 16). */
    private val walletMayHaveHistoryElsewhere: () -> Boolean = { true },
) {
    /** The two identities a restored wallet may pick between for [request]; see [IdentityChoice]. */
    data class IdentityChoice(val perSiteAddress: String, val sharedAddress: String)

    /**
     * The choice to offer for [request], or null when there is none: DigiScope, a domain with a
     * successful login on record (its history decides), or a wallet that cannot derive (locked).
     */
    suspend fun identityChoice(request: DigiIdRequest): IdentityChoice? = withContext(Dispatchers.IO) {
        if (!IdentityKeyPolicy.offersChoice(request.domain, historyDao.countSuccessful(request.domain) > 0, walletMayHaveHistoryElsewhere())) {
            return@withContext null
        }
        val perSite = address(request, IdentityKeyKind.PER_SITE) ?: return@withContext null
        val shared = address(request, IdentityKeyKind.LEGACY) ?: return@withContext null
        IdentityChoice(perSite, shared)
    }

    // Derived from the shared client, so its Tor proxy, pins and timeouts carry over. Redirects
    // are not followed: the signed login goes to the host the user approved, or nowhere.
    private val httpClient: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** [requested] is the user's pick from [identityChoice]; it counts only where a choice is offered. */
    suspend fun authenticate(request: DigiIdRequest, requested: IdentityKeyKind? = null): DigiIdResult = withContext(Dispatchers.IO) {
        try {
            // ── Security: validate callback domain matches URI ──
            // CRITICAL-4: The callback URL is derived from the scanned QR.
            // Verify it points to exactly the domain shown to the user — the same parsed URL
            // is what the POST below is built from, so the host checked is the host contacted.
            val callback = request.callbackHttpUrl
            if (callback.host != request.domain || callback.username.isNotEmpty() || callback.password.isNotEmpty()) {
                Log.e(TAG, "Callback host mismatch: ${callback.host} vs ${request.domain}")
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
            val hasAnyHistory = requested != null && historyDao.countSuccessful(request.domain) > 0
            val keyKind = IdentityKeyPolicy.choose(
                request.domain, hasLegacyHistory, hasAnyHistory, requested != null && walletMayHaveHistoryElsewhere(), requested,
            )

            // Sign the original digiid:// URI (includes nonce) with the chosen identity key.
            val signResult = sign(request, keyKind)
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
                .url(callback)
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            // Only the status decides the result: the answer's body is closed unread.
            response.close()
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
                // It comes from a fresh challenge (4.1.2 row 37): the server takes a challenge
                // once, so re-posting the approved one for the wallet's session was always refused
                // (401) and the Hub stayed disconnected. DigiScope always signs with the shared
                // identity (IdentityKeyPolicy), the one quickLogin signs with too.
                if (isDigiScopeDomain(request.domain)) {
                    Log.i(TAG, "DigiScope domain — authenticating wallet identity for Hub")
                    if (digiScopeClient.quickLogin()) {
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

/** The login URI signed with the chosen identity, through the native library. */
private fun signNatively(request: DigiIdRequest, keyKind: IdentityKeyKind): String? = when (keyKind) {
    IdentityKeyKind.LEGACY -> NativeBridge.signMessage(request.rawUri, 0)
    // Derivation input is the canonical callback URL (no query, so no nonce):
    // stable per site, unlinkable across sites.
    IdentityKeyKind.PER_SITE ->
        NativeBridge.signIdentityMessage(request.rawUri, request.callbackUrl, 0)
}

/** The address [keyKind] would sign [request] with, through the native library; no signature. */
private fun addressNatively(request: DigiIdRequest, keyKind: IdentityKeyKind): String? = when (keyKind) {
    IdentityKeyKind.LEGACY -> NativeBridge.identityAddress(null, 0)
    IdentityKeyKind.PER_SITE -> NativeBridge.identityAddress(request.callbackUrl, 0)
}
