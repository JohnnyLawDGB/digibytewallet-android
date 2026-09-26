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
) : ViewModel() {

    // In-memory mnemonic — cleared after wallet creation
    private var _mnemonic: List<String> = emptyList()

    // Word count for create flow
    private var _wordCount: Int = 12

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

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
    private var _passphrase: ByteArray? = null

    /**
     * Converted to bytes on the way in, so the String the user typed is not held across the four
     * setup screens. It used to be kept as a String from the seed screen until wallet creation —
     * a secret that could not be wiped, alive for the whole flow.
     */
    fun setPassphrase(value: String?) {
        _passphrase?.fill(0)
        _passphrase = io.digibyte.core.Bip39Passphrase.prepare(value)
    }

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
            // ViewModel to keep holding it — and now it can actually be wiped.
            _passphrase?.fill(0)
            _passphrase = null
            _uiState.value = if (success) OnboardingUiState.WalletCreated else OnboardingUiState.Error("Wallet creation failed")
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
