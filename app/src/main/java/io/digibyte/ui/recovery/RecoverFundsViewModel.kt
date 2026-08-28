package io.digibyte.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.recovery.DestResolution
import io.digibyte.core.recovery.LegacySweepService
import io.digibyte.core.recovery.RecoveryScanService
import io.digibyte.core.recovery.SeedProvider
import io.digibyte.core.recovery.SweepDestination
import io.digibyte.core.recovery.resolve
import io.digibyte.core.recovery.sweepSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the shared Recover-Funds UI (the Compose screen is Task 6). Owns the
 * seed lifecycle and orchestrates classify -> sweep.
 *
 * CRITICAL-3 seed handling:
 *  - The seed is loaded fresh for each operation (classify and sweep) and
 *    `fill(0)`-zeroed in a `finally` on BOTH paths.
 *  - [SeedProvider.loadSeed] returns the 64-byte BIP39 seed (the mnemonic ->
 *    seed conversion happens once inside the provider; see
 *    `WalletManager.loadBip39Seed`).
 *  - [RecoveryScanService.scanFromSeed] / [LegacySweepService.sweepFromSeed] do
 *    NOT zero the seed and are NOT internally guaranteed to be on an IO
 *    dispatcher by contract, so each invocation is wrapped in
 *    `withContext(Dispatchers.IO)` (they do blocking JNI + network work).
 *
 * Depends on the concrete [RecoveryScanService] (Hilt-injected) and constructs
 * a stateless [LegacySweepService] per sweep.
 */
@HiltViewModel
class RecoverFundsViewModel @Inject constructor(
    private val scanService: RecoveryScanService,
    private val seedProvider: SeedProvider,
    private val outgoingTxStore: io.digibyte.core.OutgoingTxStore,
    private val walletTxPersister: io.digibyte.core.WalletTxPersister,
    private val assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient,
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data object Classifying : UiState()
        data class Findings(
            val findings: List<RecoveryScanService.ProfileResult>,
            val totalSat: Long,
            val backendUnreachable: Boolean,
            /** Some derivation paths could not be checked. The findings below are therefore
             *  incomplete, and "no funds" must not be presented as a settled answer. */
            val partialFailurePaths: List<String> = emptyList(),
            val isForeign: Boolean = false,
        ) : UiState()
        data object Sweeping : UiState()
        data class Done(val outcomes: List<LegacySweepService.SweepOutcome>) : UiState()
        data class Error(val reason: String) : UiState()
    }

    /**
     * Moving the DigiAssets the sweep deliberately left behind.
     *
     * A separate flow from [UiState] on purpose. The sweep has already broadcast by the time
     * this runs, so its result must stay on screen — an asset move that failed cannot be allowed
     * to overwrite the record of coins that DID move.
     */
    sealed class AssetMoveState {
        data object Idle : AssetMoveState()
        data object Moving : AssetMoveState()
        data class Done(val moves: List<io.digibyte.core.recovery.ForeignAssetTransferService.Move>) : AssetMoveState()
        data class Error(val reason: String) : AssetMoveState()
    }


    /**
     * Decides which of the foreign seed's UTXOs may be spent as plain DGB.
     *
     * A DigiAsset lives on a specific UTXO; sweeping it as ordinary DGB destroys it. The raw
     * transaction comes from the asset provider and the marker check from the native parser —
     * the same one the wallet's own asset detection uses.
     */
    private fun assetClassifier() = io.digibyte.core.recovery.ForeignUtxoAssetClassifier(
        fetchRawTx = { txid -> assetNetworkClient.getRawTransaction(txid) },
        isAssetTx = { raw -> io.digibyte.core.bridge.NativeBridge.isAssetTransaction(raw) },
    )

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Classification result from the most recent [classify], used by [sweep].
     * This is the source of truth for "funds found" — never infer that from a
     * sweep outcome's `inputCount`.
     */
    private var lastFindings: List<RecoveryScanService.ProfileResult> = emptyList()

    private val _assetMove = MutableStateFlow<AssetMoveState>(AssetMoveState.Idle)
    val assetMove: StateFlow<AssetMoveState> = _assetMove.asStateFlow()

    /** The findings the last sweep ran against, kept so the asset move can reuse them without
     *  re-scanning. Cleared by [reset] with everything else. */
    private var sweptFindings: List<RecoveryScanService.ProfileResult> = emptyList()

    /** True when the last sweep used an entered phrase rather than this wallet's own seed —
     *  which decides where [moveHeldAssets] gets its signing seed from. */
    private var sweptForeign: Boolean = false

    // Transient: the entered foreign phrase, held between classifyForeign and
    // sweepForeign so the sweep re-derives the same seed. Cleared after sweep /
    // on reset. (A JVM String can't be zeroed — same accepted limit as restore.)
    private var pendingForeignMnemonic: String? = null
    /**
     * NFKD UTF-8 bytes of the passphrase for the phrase being restored, or null. Never persisted,
     * and zeroed on every reset path — this used to be a String that lived for the whole screen
     * and could not be wiped.
     */
    private var pendingForeignPassphrase: ByteArray? = null

    /**
     * Why a passphrase restore came back empty. Null when no passphrase was supplied.
     *
     * Wired here rather than only in OnboardingViewModel because THIS is the flow a user
     * actually reaches — the onboarding copy was unreachable in production.
     */
    private val _passphraseVerdict =
        MutableStateFlow<io.digibyte.core.recovery.PassphraseScanVerdict.Outcome?>(null)
    val passphraseVerdict: StateFlow<io.digibyte.core.recovery.PassphraseScanVerdict.Outcome?> =
        _passphraseVerdict

    // The coroutine launched by whichever of classify/sweep/classifyForeign/
    // sweepForeign is currently in flight. reset() cancels it so a stale scan
    // or sweep can't complete afterwards and overwrite the Idle state it just
    // set. Cancellation still runs each method's `finally` block, so the seed
    // is zeroed either way.
    private var activeJob: kotlinx.coroutines.Job? = null

    /** Return to Idle, cancel any in-flight scan/sweep, and drop any held foreign phrase (mode switch / leaving). */
    fun reset() {
        activeJob?.cancel()
        pendingForeignMnemonic = null
        pendingForeignPassphrase?.fill(0)
        pendingForeignPassphrase = null
        _passphraseVerdict.value = null
        sweptFindings = emptyList()
        sweptForeign = false
        _assetMove.value = AssetMoveState.Idle
        _state.value = UiState.Idle
    }

    fun classify() {
        _state.value = UiState.Classifying
        activeJob = viewModelScope.launch {
            val seed = seedProvider.loadSeed() ?: run {
                _state.value = UiState.Error("Wallet seed unavailable")
                return@launch
            }
            try {
                // seed = 64-byte BIP39 seed (scanFromSeed takes ONLY the seed —
                // no passphrase argument).
                when (val s = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }) {
                    is RecoveryScanService.State.Done -> {
                        lastFindings = s.nonNativeWithFunds
                        _state.value = if (s.allBackendUnreachable) {
                            UiState.Error("Couldn't reach the lookup service — try again")
                        } else {
                            UiState.Findings(
                                findings = s.nonNativeWithFunds,
                                totalSat = s.totalBalanceSat,
                                backendUnreachable = false,
                                partialFailurePaths = s.unreachableProfileLabels,
                            )
                        }
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Scan failed")
            } finally {
                seed.fill(0)
            }
        }
    }

    fun sweep(destination: SweepDestination) {
        val findings = lastFindings
        if (findings.isEmpty()) {
            _state.value = UiState.Error("Nothing to recover")
            return
        }
        when (val res = destination.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )) {
            is DestResolution.Invalid -> _state.value = UiState.Error(res.reason)
            is DestResolution.Ok -> {
                _state.value = UiState.Sweeping
                // A Native destination is this wallet's own fresh receive address,
                // so the sweep is a self-transfer that increases the balance — tag
                // it so the activity list keeps the C core's receive categorization
                // instead of rendering a negative "Sent" (see OutgoingTxStore
                // .shouldApplyOutgoingOverride). External destinations are real sends.
                val destIsSelf = destination is SweepDestination.Native
                activeJob = viewModelScope.launch {
                    val seed = seedProvider.loadSeed() ?: run {
                        _state.value = UiState.Error("Wallet seed unavailable")
                        return@launch
                    }
                    try {
                        val result = withContext(Dispatchers.IO) {
                            LegacySweepService(outgoingTxStore, walletTxPersister, assetClassifier()).sweepFromSeed(
                                seedBytes = seed,
                                nonNativeResults = findings,
                                destAddress = res.address,
                                destIsSelf = destIsSelf,
                            )
                        }
                        sweptFindings = findings
                        sweptForeign = false
                        _state.value = UiState.Done(result.outcomes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        _state.value = UiState.Error(t.message ?: "Sweep failed")
                    } finally {
                        seed.fill(0)
                    }
                }
            }
        }
    }

    /** Scan a DIFFERENT wallet's phrase (not this wallet's stored seed). */
    fun classifyForeign(mnemonic: String, passphrase: String? = null) {
        val phrase = mnemonic.trim().split(Regex("\\s+")).joinToString(" ") { it.lowercase() }
        if (!NativeBridge.isValidMnemonic(phrase)) {
            _state.value = UiState.Error("That doesn't look like a valid recovery phrase.")
            return
        }
        pendingForeignMnemonic = phrase
        pendingForeignPassphrase?.fill(0)
        pendingForeignPassphrase = io.digibyte.core.Bip39Passphrase.prepare(passphrase)
        _state.value = UiState.Classifying
        activeJob = viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), pendingForeignPassphrase) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase."); return@launch
            }
            try {
                when (val s = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }) {
                    is RecoveryScanService.State.Done -> {
                        val set = sweepSet(s, isForeign = true)      // includes native
                        val total = set.sumOf { it.totalSat }

                        // A passphrase was supplied and found nothing. Ask the OTHER question:
                        // does this phrase have funds without it? A BIP39 passphrase has no
                        // checksum, so a typo derives a valid EMPTY wallet and the honest answer
                        // — "no funds" — reads to the user as stolen coins. One more scan turns
                        // that into "check the passphrase".
                        val bareTotal: Long? =
                            if (pendingForeignPassphrase != null && total == 0L) {
                                val bare = NativeBridge.mnemonicToSeed(phrase.toByteArray(), null)
                                bare?.let {
                                    try {
                                        (withContext(Dispatchers.IO) { scanService.scanFromSeed(it) }
                                            as? RecoveryScanService.State.Done)
                                            ?.let { d -> sweepSet(d, isForeign = true).sumOf { f -> f.totalSat } }
                                    } finally {
                                        it.fill(0)
                                    }
                                }
                            } else null

                        _passphraseVerdict.value =
                            if (pendingForeignPassphrase == null) null
                            else io.digibyte.core.recovery.PassphraseScanVerdict.of(
                                withPassphraseSat = total,
                                withoutPassphraseSat = bareTotal,
                                incomplete = s.unreachableProfileLabels.isNotEmpty(),
                            )

                        _state.value = if (s.allBackendUnreachable) {
                            UiState.Error("Couldn't reach the lookup service — try again")
                        } else UiState.Findings(
                            findings = set, totalSat = total,
                            backendUnreachable = false, isForeign = true,
                            partialFailurePaths = s.unreachableProfileLabels,
                        )
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Scan failed")
            } finally {
                seed.fill(0)
            }
        }
    }

    /** Sweep the previously-scanned foreign phrase into THIS wallet (native). */
    fun sweepForeign() {
        val phrase = pendingForeignMnemonic ?: run {
            _state.value = UiState.Error("Enter a recovery phrase and scan first."); return
        }
        val findings = (_state.value as? UiState.Findings)?.findings ?: emptyList()
        if (findings.isEmpty()) { _state.value = UiState.Error("Nothing to recover"); return }
        val dest = SweepDestination.Native.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )
        if (dest !is DestResolution.Ok) {
            _state.value = UiState.Error("Could not get a destination address"); return
        }
        _state.value = UiState.Sweeping
        activeJob = viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), pendingForeignPassphrase) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase.")
                pendingForeignMnemonic = null
        pendingForeignPassphrase?.fill(0)
        pendingForeignPassphrase = null
        _passphraseVerdict.value = null
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    LegacySweepService(outgoingTxStore, walletTxPersister, assetClassifier()).sweepFromSeed(
                        seedBytes = seed,
                        nonNativeResults = findings,   // foreign: all-funded incl. native
                        destAddress = dest.address,
                        destIsSelf = true,             // lands in THIS wallet -> receive
                    )
                }
                sweptFindings = findings
                sweptForeign = true
                _state.value = UiState.Done(result.outcomes)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Sweep failed")
            } finally {
                seed.fill(0)
                // The phrase is deliberately NOT cleared here any more. Moving the DigiAssets the
                // sweep held back needs the same seed, and it is a separate user-confirmed step
                // that happens after this returns. It is cleared by reset() when the user leaves
                // the screen, and by moveHeldAssets() as soon as the move finishes — so the
                // window is "this screen, until the assets are dealt with" rather than the whole
                // session. A JVM String still cannot be zeroed; that limit is unchanged.
                _passphraseVerdict.value = null
            }
        }
    }

    /**
     * Move the DigiAssets the sweep left behind into this wallet, paying with the DGB it
     * reserved for exactly that.
     *
     * Separate from [sweep] because it is irreversible and deserves its own confirmation, and
     * because it has to remain runnable after a sweep that already broadcast. Safe to run more
     * than once: an asset that already moved is no longer in the old wallet's UTXO set, so the
     * second attempt finds nothing to move rather than double-spending.
     */
    fun moveHeldAssets() {
        val findings = sweptFindings.ifEmpty { lastFindings }
        if (findings.isEmpty()) {
            _assetMove.value = AssetMoveState.Error("Nothing to move — scan first.")
            return
        }
        val dest = SweepDestination.Native.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )
        if (dest !is DestResolution.Ok) {
            _assetMove.value = AssetMoveState.Error("Could not get a destination address")
            return
        }

        _assetMove.value = AssetMoveState.Moving
        activeJob = viewModelScope.launch {
            val foreignPhrase = pendingForeignMnemonic
            val seed: ByteArray? = if (sweptForeign) {
                if (foreignPhrase == null) null
                else NativeBridge.mnemonicToSeed(foreignPhrase.toByteArray(), pendingForeignPassphrase)
            } else {
                seedProvider.loadSeed()
            }
            if (seed == null) {
                _assetMove.value = AssetMoveState.Error(
                    "The recovery phrase is no longer available — scan again to move the assets."
                )
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    io.digibyte.core.recovery.ForeignAssetTransferService(
                        assetClassifier = assetClassifier(),
                        outgoingTxStore = outgoingTxStore,
                        walletTxPersister = walletTxPersister,
                    ).moveAssets(
                        seedBytes = seed,
                        results = findings,
                        destAddress = dest.address,
                    )
                }
                _assetMove.value = AssetMoveState.Done(result.moves)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _assetMove.value = AssetMoveState.Error(t.message ?: "Could not move the assets")
            } finally {
                seed.fill(0)
                // The assets have been dealt with one way or the other; the phrase has no further
                // use on this screen.
                pendingForeignMnemonic = null
                pendingForeignPassphrase?.fill(0)
                pendingForeignPassphrase = null
            }
        }
    }
}
