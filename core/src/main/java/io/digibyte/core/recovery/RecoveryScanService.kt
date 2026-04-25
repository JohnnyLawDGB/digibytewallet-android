package io.digibyte.core.recovery

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.reconcile.DgbNodeClient
import io.digibyte.core.reconcile.ReconcileResult
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Runs a multi-profile derivation scan during seed restore.
 *
 * Flow:
 *   1. Convert mnemonic + passphrase to 64-byte BIP39 seed (once, locally).
 *   2. For each [DerivationProfile] in [profiles], derive external + internal
 *      addresses under the profile's prefix + HMAC key.
 *   3. Query the reconcile backend for UTXOs on each profile's address set.
 *   4. Aggregate results per profile and surface totals + per-UTXO detail
 *      for the UI.
 *
 * The seed is zeroed in Kotlin heap memory when scanning finishes or fails.
 * (The native side already zeros the copy it used.)
 */
class RecoveryScanService(
    private val nodeClient: DgbNodeClient,
    private val profiles: List<DerivationProfile> = DerivationProfile.BUILT_INS,
) {
    sealed class State {
        object Idle : State()
        data class Scanning(val stage: String, val progress: Float = 0f) : State()
        data class Done(val results: List<ProfileResult>) : State() {
            val totalBalanceSat: Long = results.sumOf { it.totalSat }
            val nonNativeWithFunds: List<ProfileResult> =
                results.filter { !it.profile.isNative && it.totalSat > 0 }
            val nativeResult: ProfileResult? = results.firstOrNull { it.profile.isNative }

            /** True iff every profile that we tried to reach the backend for
             *  came back as `reachableBackend=false`. UI uses this to show
             *  "Couldn't reach reconcile endpoint" rather than misleading
             *  "No funds detected". */
            val allBackendUnreachable: Boolean =
                results.isNotEmpty() &&
                results.filter { it.addresses.isNotEmpty() }
                    .all { !it.reachableBackend }
        }
        data class Failed(val reason: String) : State()
    }

    data class ProfileResult(
        val profile: DerivationProfile,
        val addresses: List<String>,
        val utxos: List<UtxoEntry>,
        val rawTxs: Map<String, io.digibyte.core.reconcile.RawTxEntry>,
        /** False when the reconcile call returned null (network/timeout/etc.).
         *  Distinguishes "we asked and got nothing" from "we never got an
         *  answer" — critical for honest UX during backend outages. */
        val reachableBackend: Boolean = true,
    ) {
        val totalSat: Long = utxos.sumOf { it.amountSatoshi }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun scan(
        mnemonic: String,
        passphrase: String? = null,
    ): State = withContext(Dispatchers.IO) {
        val phraseBytes = mnemonic.trim().lowercase().toByteArray(Charsets.UTF_8)
        var seed: ByteArray? = null
        try {
            _state.value = State.Scanning("Deriving seed…", 0f)
            seed = NativeBridge.mnemonicToSeed(phraseBytes, passphrase)
            if (seed == null || seed.isEmpty()) {
                val failed = State.Failed("Could not derive seed from mnemonic")
                _state.value = failed
                return@withContext failed
            }
            val capturedSeed = seed

            _state.value = State.Scanning(
                "Deriving addresses across ${profiles.size} paths…", 0.1f
            )

            // Derive addresses for every profile up front so the subsequent
            // backend calls can run in parallel without interleaving JNI work.
            val profileAddrs: List<Pair<DerivationProfile, List<String>>> =
                profiles.map { profile ->
                    val arr = NativeBridge.deriveAddresses(
                        capturedSeed,
                        profile.hmacKey,
                        profile.prefixPath,
                        profile.gapExternal,
                        profile.gapInternal,
                        profile.addressFormat,
                    ) ?: emptyArray()
                    profile to arr.filter { it.isNotEmpty() }
                }

            // Seed has been consumed by all derivations — zero the Kotlin heap copy.
            capturedSeed.fill(0)
            seed = null

            _state.value = State.Scanning(
                "Querying node for UTXOs on ${profileAddrs.sumOf { it.second.size }} addresses…",
                0.4f
            )

            // Serialize per-profile reconciles. Backend dev's note 2026-04-25:
            // the digiscope reconcile endpoint serializes requests internally
            // and rejects concurrent ones with HTTP 429. Firing 6 profiles in
            // parallel from one wallet was both slower (queue + retries) and
            // poisoned the backend's circuit breaker. One at a time is faster
            // in practice and friendlier to the shared infra.
            val results = mutableListOf<ProfileResult>()
            for ((i, pair) in profileAddrs.withIndex()) {
                val (profile, addrs) = pair
                _state.value = State.Scanning(
                    "Reconciling profile ${i + 1}/${profileAddrs.size}: ${profile.label}",
                    0.4f + (0.5f * i / profileAddrs.size.coerceAtLeast(1)),
                )
                val result = if (addrs.isEmpty()) {
                    // No addresses derived for this profile (rare: BIP49 etc.
                    // when JNI returns empty). Treat as "checked, empty"
                    // rather than backend-failure.
                    ProfileResult(profile, addrs, emptyList(), emptyMap(), reachableBackend = true)
                } else {
                    val r: ReconcileResult? = nodeClient.reconcileAddresses(addrs)
                    if (r == null) {
                        ProfileResult(
                            profile, addrs, emptyList(), emptyMap(),
                            reachableBackend = false,
                        )
                    } else {
                        ProfileResult(profile, addrs, r.utxos, r.rawTxs, reachableBackend = true)
                    }
                }
                results.add(result)
            }

            val done = State.Done(results)
            _state.value = done
            done
        } catch (t: Throwable) {
            val failed = State.Failed(t.message ?: t.javaClass.simpleName)
            _state.value = failed
            failed
        } finally {
            seed?.fill(0)
            phraseBytes.fill(0)
        }
    }
}
