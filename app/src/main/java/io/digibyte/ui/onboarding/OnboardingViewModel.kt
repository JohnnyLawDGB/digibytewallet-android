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

    /**
     * OPTIONAL BIP39 passphrase, in memory only until the wallet is created.
     *
     * Held here rather than passed through navigation arguments so it never enters a back-stack
     * entry, a saved-state bundle, or a deep link.
     */
    private var _passphrase: String? = null

    fun setPassphrase(value: String?) { _passphrase = value?.takeIf { it.isNotEmpty() } }

    fun hasPassphrase(): Boolean = _passphrase != null

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

            // When a passphrase was supplied and found nothing, ask the other question too:
            // does this phrase have funds WITHOUT it? A BIP39 passphrase has no checksum, so a
            // typo derives a valid empty wallet and the scan honestly reports nothing — which
            // reads to the user as stolen coins. One extra pass turns that into "check the
            // passphrase", which is a five-second fix instead of a panic.
            val comparison: Long? =
                if (passphrase != null &&
                    result is io.digibyte.core.recovery.RecoveryScanService.State.Done &&
                    result.totalBalanceSat == 0L &&
                    !result.anyBackendUnreachable
                ) {
                    (recoveryScanService.scan(phrase, null)
                        as? io.digibyte.core.recovery.RecoveryScanService.State.Done)
                        ?.totalBalanceSat
                } else null

            _passphraseVerdict.value = if (result is io.digibyte.core.recovery.RecoveryScanService.State.Done) {
                io.digibyte.core.recovery.PassphraseScanVerdict.of(
                    withPassphraseSat = result.totalBalanceSat,
                    withoutPassphraseSat = comparison,
                    incomplete = result.anyBackendUnreachable,
                )
            } else null

            // The comparison scan overwrote the observable state; put the real answer back so the
            // UI never shows funds that belong to a wallet the user is not restoring.
            _scanResults.value = result
        }
    }

    /** Why a passphrase scan came back empty, when one was supplied. Null when not applicable. */
    private val _passphraseVerdict =
        MutableStateFlow<io.digibyte.core.recovery.PassphraseScanVerdict.Outcome?>(null)
    val passphraseVerdict: StateFlow<io.digibyte.core.recovery.PassphraseScanVerdict.Outcome?> =
        _passphraseVerdict

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
                walletManager.createWallet(phrase, _passphrase)
            }
            wipeMnemonicFromMemory()
            // The passphrase is now in the Keystore envelope; there is no reason for the
            // ViewModel to keep holding it.
            _passphrase = null
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

            // NOTE: no depth gate here. Restores are accepted at ANY depth — the
            // paced convoy bounds the memory of an arbitrarily deep CF scan, so
            // there is nothing to refuse. (A backup you can't restore from isn't a
            // backup.)
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

}
