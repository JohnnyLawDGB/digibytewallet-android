package io.digibyte.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.digibyte.core.WalletManager
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import io.digibyte.core.db.dao.WalletConfigDao
import io.digibyte.core.db.entity.WalletConfigEntity
import io.digibyte.core.isTestnet
import io.digibyte.core.model.SyncState
import io.digibyte.core.security.PinManager
import io.digibyte.core.settings.CustomNode
import io.digibyte.core.settings.CustomNodePrefs
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
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
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val pinManager: PinManager,
    private val walletConfigDao: WalletConfigDao,
    private val torManager: TorManager
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

    // ── Tor state ─────────────────────────────────────────────────────────────
    /** Live Tor state from TorManager. */
    val torState: StateFlow<TorState> = torManager.state

    /** Whether Tor routing is currently enabled in user preferences. */
    private val _torEnabled = MutableStateFlow(torManager.isEnabled)
    val torEnabled: StateFlow<Boolean> = _torEnabled.asStateFlow()

    // ── Dandelion broadcast privacy ───────────────────────────────────────────
    /** Whether Dandelion stem submission is enabled (default OFF / opt-in). Persisted to
     *  the dgb_dandelion pref so SyncService.injectDandelionPeers reads it on sync start. */
    private val _dandelionEnabled = MutableStateFlow(
        context.getSharedPreferences("dgb_dandelion", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
    )
    val dandelionEnabled: StateFlow<Boolean> = _dandelionEnabled.asStateFlow()

    /** Toggle Dandelion stem submission: persist the pref, update the C-core gate,
     *  and mirror the Kotlin-side Broadcaster flag. */
    fun setDandelionEnabled(enabled: Boolean) {
        _dandelionEnabled.value = enabled
        Broadcaster.dandelionEnabled = enabled
        try { NativeBridge.setDandelionEnabled(enabled) } catch (_: Throwable) { /* applied on next sync */ }
        context.getSharedPreferences("dgb_dandelion", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).apply()
    }

    // ── Network selection (dev-gated Advanced section; Task 6) ───────────────
    /**
     * Whether the persisted network selection is testnet. Read from the SAME
     * network-independent `dgb_settings` store that DigiByteApp.onCreate()'s
     * applyNetworkSelection() consults at process start (see
     * io.digibyte.core.NetworkState) — this VM never touches the per-network
     * suffixed prefs/DB, only the selector pref itself.
     */
    private val _networkTestnetEnabled = MutableStateFlow(
        context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
            .getBoolean("dgb_network_testnet", false)
    )
    val networkTestnetEnabled: StateFlow<Boolean> = _networkTestnetEnabled.asStateFlow()

    /**
     * Persist the network selection and restart the app so DigiByteApp
     * .onCreate() re-reads the pref and calls NativeBridge.setNetwork()
     * before any wallet/peer-manager state is created. A live in-place
     * switch is out of scope (design doc §7.2 — restart-to-apply chosen).
     *
     * Uses commit() (not apply()) so the write is guaranteed durable on disk
     * before Runtime.exit(0) tears down the process — apply()'s async write
     * could otherwise race the exit and silently not persist.
     */
    fun setNetworkTestnet(enabled: Boolean) {
        context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("dgb_network_testnet", enabled).commit()
        restartApp()
    }

    /** Relaunch the app's own launcher activity in a fresh task, then kill this process. */
    private fun restartApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launchIntent)
        }
        Runtime.getRuntime().exit(0)
    }

    // ── Custom / own node (Advanced section) ──────────────────────────────────
    private val _customNodeEnabled = MutableStateFlow(CustomNodePrefs.isEnabled(context))
    val customNodeEnabled: StateFlow<Boolean> = _customNodeEnabled.asStateFlow()

    private val _customNodeHostPort = MutableStateFlow(CustomNodePrefs.hostPort(context) ?: "")
    val customNodeHostPort: StateFlow<String> = _customNodeHostPort.asStateFlow()

    fun setCustomNodeEnabled(enabled: Boolean) {
        CustomNodePrefs.setEnabled(context, enabled)
        _customNodeEnabled.value = enabled
        // Restart-to-apply (matches Sync Mode). The injection + CF-only mode + watchdog
        // suppression run inside SyncService's latched setup coroutine, which a live
        // reconnect does NOT re-run — so the UI must tell the user to restart the app.
    }

    /** Validate + persist the host:port. Returns false (persists nothing) if invalid. */
    fun saveCustomNodeHostPort(raw: String): Boolean {
        val defaultPort = if (isTestnet(context)) CustomNode.TESTNET_DEFAULT_PORT
                          else CustomNode.MAINNET_DEFAULT_PORT
        val parsed = CustomNode.parse(raw, defaultPort) ?: return false
        CustomNodePrefs.setHostPort(context, parsed.asHostPort())
        _customNodeHostPort.value = parsed.asHostPort()
        return true
    }

    // ── Action results ────────────────────────────────────────────────────────
    private val _wipeResult = MutableStateFlow<WipeResult?>(null)
    val wipeResult: StateFlow<WipeResult?> = _wipeResult.asStateFlow()

    init {
        refreshNetworkStats()
        loadConfig()
    }

    fun refreshNetworkStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val peers = runCatching { NativeBridge.getPeerCount() }.getOrDefault(0)
            _peerCount.value = peers
            _lastBlockHeight.value = runCatching { NativeBridge.getLastBlockHeight() }.getOrDefault(0L)
            _estimatedHeight.value = runCatching { NativeBridge.getEstimatedBlockHeight() }.getOrDefault(0L)
            // Wake-up: a manual refresh at 0 peers should actually reconnect, not just
            // repaint the count. Force a clean recreate so a manager stuck after long
            // idle re-dials (digiscope.me + cached peers are always re-injected).
            if (peers == 0) {
                runCatching {
                    NativeBridge.forceReconnect()
                    NativeBridge.startSync()
                }
            }
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

    // ── Tor management ────────────────────────────────────────────────────────
    /**
     * Toggle Tor on or off. Persists the choice to both TorManager prefs and
     * WalletConfigEntity so both surfaces agree on the enabled state.
     */
    fun setTorEnabled(enabled: Boolean) {
        torManager.isEnabled = enabled
        _torEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            // Persist to Room so SyncService and new install logic can read it.
            val updated = (_config.value).copy(torEnabled = enabled)
            walletConfigDao.upsert(updated)
            _config.value = updated
        }
        if (enabled) {
            // Use startAsync() — TorManager's own scope survives ViewModel
            // destruction. Bootstrap takes 10-30s; viewModelScope would cancel
            // it if the user navigates away from Settings.
            torManager.startAsync()
        } else {
            torManager.stop()
            // Clear SOCKS proxy immediately so the C core reconnects directly.
            // Without this, peer connections keep trying the dead proxy until restart.
            NativeBridge.clearSocksProxy()
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
