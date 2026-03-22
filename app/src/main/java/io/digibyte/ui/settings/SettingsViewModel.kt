package io.digibyte.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.WalletManager
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.WalletConfigDao
import io.digibyte.core.db.entity.WalletConfigEntity
import io.digibyte.core.model.SyncState
import io.digibyte.core.security.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val pinManager: PinManager,
    private val walletConfigDao: WalletConfigDao
) : ViewModel() {

    // ── Sync / network state ──────────────────────────────────────────────────
    val syncState: StateFlow<SyncState> = walletManager.syncState

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _lastBlockHeight = MutableStateFlow(0L)
    val lastBlockHeight: StateFlow<Long> = _lastBlockHeight.asStateFlow()

    private val _estimatedHeight = MutableStateFlow(0L)
    val estimatedHeight: StateFlow<Long> = _estimatedHeight.asStateFlow()

    // ── Wallet config ─────────────────────────────────────────────────────────
    private val _config = MutableStateFlow(WalletConfigEntity())
    val config: StateFlow<WalletConfigEntity> = _config.asStateFlow()

    val fiatCurrency: StateFlow<String> = _config.map { it.fiatCurrency }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "USD")

    val autoLockTimeout: StateFlow<Long> = _config.map { it.autoLockTimeoutMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60_000L)

    // ── Action results ────────────────────────────────────────────────────────
    private val _wipeResult = MutableStateFlow<WipeResult?>(null)
    val wipeResult: StateFlow<WipeResult?> = _wipeResult.asStateFlow()

    init {
        refreshNetworkStats()
        loadConfig()
    }

    fun refreshNetworkStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _peerCount.value = runCatching { NativeBridge.getPeerCount() }.getOrDefault(0)
            _lastBlockHeight.value = runCatching { NativeBridge.getLastBlockHeight() }.getOrDefault(0L)
            _estimatedHeight.value = runCatching { NativeBridge.getEstimatedBlockHeight() }.getOrDefault(0L)
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val cfg = withContext(Dispatchers.IO) {
                walletConfigDao.get() ?: WalletConfigEntity()
            }
            _config.value = cfg
        }
    }

    // ── PIN management ────────────────────────────────────────────────────────
    fun verifyPin(pin: String): Boolean = pinManager.verifyPin(pin)

    fun changePin(newPin: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pinManager.setPin(newPin)
        }
    }

    // ── Display preferences ───────────────────────────────────────────────────
    fun setFiatCurrency(currency: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = (_config.value).copy(fiatCurrency = currency)
            walletConfigDao.upsert(updated)
            _config.value = updated
        }
    }

    fun setAutoLockTimeout(ms: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = (_config.value).copy(autoLockTimeoutMs = ms)
            walletConfigDao.upsert(updated)
            _config.value = updated
        }
    }

    // ── Wallet wipe ───────────────────────────────────────────────────────────
    fun wipeWallet() {
        viewModelScope.launch {
            try {
                walletManager.wipeWallet()
                pinManager.clearPin()
                _wipeResult.value = WipeResult.Success
            } catch (e: Exception) {
                _wipeResult.value = WipeResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearWipeResult() { _wipeResult.value = null }
}

sealed class WipeResult {
    data object Success : WipeResult()
    data class Error(val message: String) : WipeResult()
}
