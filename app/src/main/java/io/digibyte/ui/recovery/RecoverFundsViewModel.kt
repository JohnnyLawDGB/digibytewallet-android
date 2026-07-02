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
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data object Classifying : UiState()
        data class Findings(
            val findings: List<RecoveryScanService.ProfileResult>,
            val totalSat: Long,
            val backendUnreachable: Boolean,
            val isForeign: Boolean = false,
        ) : UiState()
        data object Sweeping : UiState()
        data class Done(val outcomes: List<LegacySweepService.SweepOutcome>) : UiState()
        data class Error(val reason: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Classification result from the most recent [classify], used by [sweep].
     * This is the source of truth for "funds found" — never infer that from a
     * sweep outcome's `inputCount`.
     */
    private var lastFindings: List<RecoveryScanService.ProfileResult> = emptyList()

    // Transient: the entered foreign phrase, held between classifyForeign and
    // sweepForeign so the sweep re-derives the same seed. Cleared after sweep /
    // on reset. (A JVM String can't be zeroed — same accepted limit as restore.)
    private var pendingForeignMnemonic: String? = null

    /** Return to Idle and drop any held foreign phrase (mode switch / leaving). */
    fun reset() {
        pendingForeignMnemonic = null
        _state.value = UiState.Idle
    }

    fun classify() {
        _state.value = UiState.Classifying
        viewModelScope.launch {
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
                            )
                        }
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
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
                viewModelScope.launch {
                    val seed = seedProvider.loadSeed() ?: run {
                        _state.value = UiState.Error("Wallet seed unavailable")
                        return@launch
                    }
                    try {
                        val result = withContext(Dispatchers.IO) {
                            LegacySweepService(outgoingTxStore, walletTxPersister).sweepFromSeed(
                                seedBytes = seed,
                                nonNativeResults = findings,
                                destAddress = res.address,
                                destIsSelf = destIsSelf,
                            )
                        }
                        _state.value = UiState.Done(result.outcomes)
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
    fun classifyForeign(mnemonic: String) {
        val phrase = mnemonic.trim().split(Regex("\\s+")).joinToString(" ")
        if (!NativeBridge.isValidMnemonic(phrase)) {
            _state.value = UiState.Error("That doesn't look like a valid recovery phrase.")
            return
        }
        pendingForeignMnemonic = phrase
        _state.value = UiState.Classifying
        viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), null) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase."); return@launch
            }
            try {
                when (val s = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }) {
                    is RecoveryScanService.State.Done -> {
                        val set = sweepSet(s, isForeign = true)      // includes native
                        _state.value = if (s.allBackendUnreachable) {
                            UiState.Error("Couldn't reach the lookup service — try again")
                        } else UiState.Findings(
                            findings = set, totalSat = set.sumOf { it.totalSat },
                            backendUnreachable = false, isForeign = true,
                        )
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
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
        viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), null) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase."); return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    LegacySweepService(outgoingTxStore, walletTxPersister).sweepFromSeed(
                        seedBytes = seed,
                        nonNativeResults = findings,   // foreign: all-funded incl. native
                        destAddress = dest.address,
                        destIsSelf = true,             // lands in THIS wallet -> receive
                    )
                }
                _state.value = UiState.Done(result.outcomes)
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Sweep failed")
            } finally {
                seed.fill(0)
                pendingForeignMnemonic = null
            }
        }
    }
}
