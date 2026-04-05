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
    private val pinManager: PinManager
) : ViewModel() {

    // In-memory mnemonic — cleared after wallet creation
    private var _mnemonic: List<String> = emptyList()

    // Word count for create flow
    private var _wordCount: Int = 12

    // Recovery timestamp (Unix seconds) — 0 means full rescan
    private var _recoveryTimestamp: Long = 0L

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

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

    /** Create wallet from generated mnemonic. Clears mnemonic from memory when done. */
    fun createWallet(onResult: (Boolean) -> Unit) {
        val phrase = _mnemonic.joinToString(" ")
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            pinManager.clearPin()
            val success = withContext(Dispatchers.Default) {
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
            // Clear any stale PIN from a previous install so the user
            // is routed to PIN setup, not the unlock screen.
            pinManager.clearPin()
            val success = withContext(Dispatchers.Default) {
                walletManager.recoverWallet(phrase, ts)
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
