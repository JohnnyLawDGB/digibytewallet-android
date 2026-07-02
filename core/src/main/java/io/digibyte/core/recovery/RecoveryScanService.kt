package io.digibyte.core.recovery

import io.digibyte.core.bridge.NativeBridge
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
 *   3. Query the [UtxoSource] for UTXOs on each profile's address set.
 *   4. Aggregate results per profile and surface totals + per-UTXO detail
 *      for the UI.
 *
 * The seed is zeroed in Kotlin heap memory when scanning finishes or fails.
 * (The native side already zeros the copy it used.)
 */
class RecoveryScanService(
    private val utxoSource: UtxoSource,
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

            /** Every funded profile (native included) — the set to sweep when
             *  recovering a DIFFERENT wallet's phrase, where the native BIP84
             *  funds are foreign to this wallet and must be swept too. (The
             *  own-seed path uses [nonNativeWithFunds] instead, since native
             *  funds are already in this wallet.) BIP49 funded profiles are
             *  included here but are deferred inside the sweeper. */
            val allWithFunds: List<ProfileResult> =
                results.filter { it.totalSat > 0 }

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
        /** Same addresses as [addresses] but each tagged with its true
         *  (chain,index) from derivation. The sweeper reads THIS — never the
         *  positional index of [addresses] — so dropped empty slots can't
         *  mis-map an input to the wrong child key (bug #3). */
        val derivedAddresses: List<DerivedAddress>,
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

    /** #8: RecoveryScanService is a @Singleton, so the onboarding path's
     *  informational scan and RecoverFundsViewModel.classify share this
     *  instance. Cache the last usable Done keyed by the derived-address set so
     *  the second, structurally-identical classify is served without a second
     *  round of reconcile-backend calls (the endpoint is 429-prone). A
     *  different seed/profile produces a different key → cache miss → re-scan. */
    private data class ClassifyCache(
        val key: Map<DerivationProfile, List<DerivedAddress>>,
        val done: State.Done,
    )
    @Volatile private var lastClassify: ClassifyCache? = null

    /**
     * Post-derivation classification: for each profile's already-derived
     * address list, query [utxoSource] and assemble [ProfileResult]s.
     * Callable from a unit test with a [FakeUtxoSource] — no JNI needed.
     *
     * Emits per-profile [State.Scanning] progress and returns [State.Done].
     */
    suspend fun classifyDerived(
        derivedByProfile: Map<DerivationProfile, List<DerivedAddress>>,
    ): State.Done {
        lastClassify?.let { cached ->
            if (cached.key == derivedByProfile) {
                _state.value = cached.done
                return cached.done
            }
        }
        val profileAddrs = derivedByProfile.entries.toList()
        val results = mutableListOf<ProfileResult>()

        // Serialize per-profile reconciles. Backend dev's note 2026-04-25:
        // the digiscope reconcile endpoint serializes requests internally
        // and rejects concurrent ones with HTTP 429. Firing 6 profiles in
        // parallel from one wallet was both slower (queue + retries) and
        // poisoned the backend's circuit breaker. One at a time is faster
        // in practice and friendlier to the shared infra.
        for ((i, entry) in profileAddrs.withIndex()) {
            val (profile, derived) = entry
            val addrs = derived.map { it.address }
            _state.value = State.Scanning(
                "Reconciling profile ${i + 1}/${profileAddrs.size}: ${profile.label}",
                0.4f + (0.5f * i / profileAddrs.size.coerceAtLeast(1)),
            )
            val result = if (derived.isEmpty()) {
                // No addresses derived for this profile (rare: BIP49 etc.
                // when JNI returns empty). Treat as "checked, empty"
                // rather than backend-failure.
                ProfileResult(profile, addrs, derived, emptyList(), emptyMap(), reachableBackend = true)
            } else {
                val fetched = utxoSource.fetchUtxos(addrs)
                ProfileResult(
                    profile = profile,
                    addresses = addrs,
                    derivedAddresses = derived,
                    utxos = fetched?.utxos ?: emptyList(),
                    rawTxs = fetched?.rawTxs ?: emptyMap(),
                    reachableBackend = fetched != null,
                )
            }
            results.add(result)
        }

        val done = State.Done(results)
        // Only cache when EVERY non-empty profile was reachable, so a PARTIAL
        // outage (some profiles reconciled, some didn't) isn't memoized — the
        // Recover-Funds retry then re-queries and can pick up funds on the
        // profiles that failed the first time. allBackendUnreachable (the
        // total-outage case) is a subset of this, so it stays uncached too.
        val allReachable = done.results.all { it.addresses.isEmpty() || it.reachableBackend }
        if (allReachable) {
            lastClassify = ClassifyCache(derivedByProfile, done)
        }
        return done
    }

    /**
     * Derives each profile's addresses from [seedBytes] via JNI, then calls
     * [classifyDerived]. The seed bytes are owned by the caller — this
     * function does NOT zero [seedBytes].
     */
    suspend fun scanFromSeed(
        seedBytes: ByteArray,
    ): State = withContext(Dispatchers.IO) {
        try {
            _state.value = State.Scanning(
                "Deriving addresses across ${profiles.size} paths…", 0.1f
            )
            val derivedByProfile = deriveAllProfiles(seedBytes)
            val done = classifyDerived(derivedByProfile)
            _state.value = done
            done
        } catch (t: Throwable) {
            val failed = State.Failed(t.message ?: t.javaClass.simpleName)
            _state.value = failed
            failed
        }
    }

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

            val derivedByProfile = deriveAllProfiles(capturedSeed)

            // Seed has been consumed by all derivations — zero the Kotlin heap copy.
            capturedSeed.fill(0)
            seed = null

            _state.value = State.Scanning(
                "Querying node for UTXOs on ${derivedByProfile.values.sumOf { it.size }} addresses…",
                0.4f
            )

            val done = classifyDerived(derivedByProfile)
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

    /**
     * Derives addresses for every profile from [seedBytes] via JNI.
     * Returns a LinkedHashMap preserving profile ordering (matches [profiles]).
     * The seed is NOT zeroed here — callers control lifetime.
     */
    private fun deriveAllProfiles(
        seedBytes: ByteArray,
    ): Map<DerivationProfile, List<DerivedAddress>> {
        val result = LinkedHashMap<DerivationProfile, List<DerivedAddress>>(profiles.size)
        for (profile in profiles) {
            val arr = NativeBridge.deriveAddresses(
                seedBytes,
                profile.hmacKey,
                profile.prefixPath,
                profile.gapExternal,
                profile.gapInternal,
                profile.addressFormat,
            ) ?: emptyArray()
            result[profile] = mapDerived(arr, profile.gapExternal)
        }
        return result
    }
}

/**
 * Tag each raw derived address with its true (chain,index) from its RAW array
 * position — external[0..gapExternal-1] then internal[…] — and drop empty
 * slots. Computing (chain,index) BEFORE filtering is the #3 fix: a dropped
 * empty slot can no longer shift a surviving address onto the wrong child key.
 * Pure + JNI-free so it is unit-testable.
 */
internal fun mapDerived(raw: Array<String>, gapExternal: Int): List<DerivedAddress> =
    raw.mapIndexedNotNull { pos, addr ->
        when {
            addr.isEmpty() -> null
            pos < gapExternal -> DerivedAddress(addr, chain = 0, index = pos)
            else -> DerivedAddress(addr, chain = 1, index = pos - gapExternal)
        }
    }
