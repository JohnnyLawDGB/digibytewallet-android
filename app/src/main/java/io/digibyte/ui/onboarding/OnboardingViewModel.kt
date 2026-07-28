package io.digibyte.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.WalletManager
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.security.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Shared ViewModel for the onboarding flow.
 * Holds mnemonic state in memory only — never persisted or logged.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val pinManager: PinManager,
    private val recoveryScanService: io.digibyte.core.recovery.RecoveryScanService,
) : ViewModel() {

    // In-memory mnemonic — cleared after wallet creation
    private var _mnemonic: List<String> = emptyList()

    // Word count for create flow
    private var _wordCount: Int = 12

    // Recovery timestamp (Unix seconds) — 0 means full rescan
    private var _recoveryTimestamp: Long = 0L

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _pendingLegacyRecovery = MutableStateFlow(false)
    val pendingLegacyRecovery: StateFlow<Boolean> = _pendingLegacyRecovery.asStateFlow()

    fun getWordCount(): Int = _wordCount

    /** Set the word count (12 or 24) and generate a new mnemonic. */
    fun setWordCount(count: Int) {
        _wordCount = count
    }

    /** Returns the current in-memory mnemonic words. */
    fun getMnemonicWords(): List<String> = _mnemonic

    /** Generate a fresh mnemonic with the chosen word count. */
    fun generateMnemonic() {
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            val result = withContext(Dispatchers.Default) {
                NativeBridge.generateMnemonic(if (_wordCount == 24) 256 else 128)
            }
            if (result != null) {
                _mnemonic = result.trim().split(" ")
                _uiState.value = OnboardingUiState.MnemonicReady
            } else {
                _uiState.value = OnboardingUiState.Error("Failed to generate mnemonic")
            }
        }
    }

    /** Set mnemonic from recovery input (splits on whitespace). */
    fun setRecoveryMnemonic(phrase: String) {
        _mnemonic = phrase.trim().split("\\s+".toRegex())
    }

    /** Set the recovery timestamp mapped from the date picker. */
    fun setRecoveryTimestamp(timestamp: Long) {
        _recoveryTimestamp = timestamp
    }

    // ── Universal Restore scan state ─────────────────────────────────────────
    //
    // Holds the most recent multi-path scan result so RecoveryScanScreen can
    // show it without re-running the scan on config change, and so
    // RecoveryDateScreen / WalletScreen can consult it for "we found funds
    // on legacy paths" sweep context.

    private val _scanResults = kotlinx.coroutines.flow.MutableStateFlow<
            io.digibyte.core.recovery.RecoveryScanService.State
            >(io.digibyte.core.recovery.RecoveryScanService.State.Idle)
    val scanResults: kotlinx.coroutines.flow.StateFlow<
            io.digibyte.core.recovery.RecoveryScanService.State
            > = _scanResults.asStateFlow()

    /** Run the multi-path derivation scan against the currently-entered
     *  mnemonic. Results land in [scanResults]. Safe to call from the UI
     *  and observe reactively. */
    fun runRecoveryScan(passphrase: String? = null) {
        val phrase = _mnemonic.joinToString(" ")
        if (phrase.isBlank()) {
            _scanResults.value = io.digibyte.core.recovery.RecoveryScanService
                .State.Failed("No mnemonic entered")
            return
        }
        viewModelScope.launch {
            val result = recoveryScanService.scan(phrase, passphrase)
            _scanResults.value = result
        }
    }

    /** Create wallet from generated mnemonic. Clears mnemonic from memory when done. */
    fun createWallet(onResult: (Boolean) -> Unit) {
        val phrase = _mnemonic.joinToString(" ")
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            val success = withContext(Dispatchers.Default) {
                // Do NOT clearPin here — PinSetupScreen calls setPin() right
                // before this, and clearing afterward wipes the freshly-set
                // PIN. (recoverWallet keeps its clearPin because it runs
                // before pin_setup, replacing any stale-from-prior-install PIN.)
                walletManager.createWallet(phrase)
            }
            wipeMnemonicFromMemory()
            _uiState.value = if (success) OnboardingUiState.WalletCreated else OnboardingUiState.Error("Wallet creation failed")
            onResult(success)
        }
    }

    /** Recover wallet from entered mnemonic and chosen timestamp. */
    fun recoverWallet(onResult: (Boolean) -> Unit) {
        val phrase = _mnemonic.joinToString(" ")
        val ts = _recoveryTimestamp
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading

            // Deep-restore depth gate (spec Part 3c): if the CF scan this restore
            // needs is deeper than the native retention ceiling, REFUSE up front —
            // starting it would sync to a wrong balance or OOM. Computed OFFLINE
            // from hardcoded checkpoints (no peers, wallet not yet loaded), so we
            // decide BEFORE persisting the seed / rescanning. Belt-and-suspenders
            // for this lives in SyncService.startSyncWithTor.
            val tooDeep = withContext(Dispatchers.Default) {
                val depth = NativeBridge.restoreScanDepthBlocks(ts)
                val limit = NativeBridge.restoreScanDepthLimit()
                io.digibyte.core.sync.RestoreDepthGate.isRestoreTooDeep(depth, limit)
            }
            if (tooDeep) {
                // No seed persist, no rescan — leave the in-memory mnemonic intact
                // so the user can go back without re-entering their words.
                _uiState.value = OnboardingUiState.TooDeep
                onResult(false)
                return@launch
            }

            val success = withContext(Dispatchers.Default) {
                // Clear any stale PIN from a previous install so the user
                // is routed to PIN setup, not the unlock screen. Done inside
                // the worker dispatcher so the Keystore-backed prefs write
                // never lands on the main thread.
                pinManager.clearPin()
                walletManager.recoverWallet(phrase, ts)
            }

            // If the pre-recovery scan found funds on non-native paths, signal
            // the UI to navigate to RecoverFundsScreen after the wallet lands.
            // No silent sweep happens here anymore — the user is shown the screen
            // and initiates the sweep themselves.
            if (success) {
                val scan = _scanResults.value
                _pendingLegacyRecovery.value =
                    scan is io.digibyte.core.recovery.RecoveryScanService.State.Done &&
                    scan.nonNativeWithFunds.isNotEmpty()
            }

            wipeMnemonicFromMemory()
            _uiState.value = if (success) OnboardingUiState.WalletCreated else OnboardingUiState.Error("Recovery failed")
            onResult(success)
        }
    }

    /** Set PIN via PinManager. */
    fun setPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.Default) {
                runCatching { pinManager.setPin(pin); true }.getOrDefault(false)
            }
            onResult(success)
        }
    }

    fun resetError() {
        _uiState.value = OnboardingUiState.Idle
    }

    /** Overwrite mnemonic list with dummy data and clear — defence in depth. */
    private fun wipeMnemonicFromMemory() {
        _mnemonic = List(_mnemonic.size) { "wipe" }
        _mnemonic = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        wipeMnemonicFromMemory()
    }
}

sealed class OnboardingUiState {
    data object Idle : OnboardingUiState()
    data object Loading : OnboardingUiState()
    data object MnemonicReady : OnboardingUiState()
    data object WalletCreated : OnboardingUiState()
    data class Error(val message: String) : OnboardingUiState()

    /**
     * The chosen restore is deeper than this build can scan on-device (spec
     * Part 3c). A plain, honest refusal — NOT an error and NOT a failure the
     * user can fix by retrying; full historical restore ships with windowed-scan.
     */
    data object TooDeep : OnboardingUiState()

    companion object {
        /** User-facing copy for [TooDeep]. Kept here so UI and VM agree on one string. */
        const val RESTORE_TOO_DEEP_MESSAGE: String =
            "This wallet's history is deeper than this version can scan on your " +
            "phone yet. Full historical restore is coming in a future update."
    }
}
