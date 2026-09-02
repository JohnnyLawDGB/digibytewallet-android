package io.digibyte.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
import io.digibyte.core.model.SyncFrontier
import io.digibyte.core.model.SyncState
import io.digibyte.core.model.deriveSyncFrontier
import io.digibyte.core.security.PinManager
import io.digibyte.core.security.PinVerifyResult
import io.digibyte.core.settings.CustomNode
import io.digibyte.core.settings.CustomNodePrefs
import io.digibyte.core.settings.OwnNodeUri
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
import io.digibyte.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /** Compact-filter chain tip — the FUNCTIONAL sync frontier in CF-only mode.
     *  Polled alongside the other network stats in [refreshNetworkStats]. */
    private val _cfTip = MutableStateFlow(0L)

    /** CF scan-ledger counters (Phase-1 observe-only): scanned-through height,
     *  outstanding, gave-up, and pending. Polled in [refreshNetworkStats]. */
    private val _cfLedgerCounts = MutableStateFlow(CfLedgerCounts())
    val cfLedgerCounts: StateFlow<CfLedgerCounts> = _cfLedgerCounts.asStateFlow()

    /** CF scan-ledger hole ranges as "start–end" strings (Phase-1 observe-only). */
    private val _cfLedgerHoles = MutableStateFlow<List<String>>(emptyList())
    val cfLedgerHoles: StateFlow<List<String>> = _cfLedgerHoles.asStateFlow()

    /** Stable sync-target tip: a native monotonic high-water mark of the header
     *  height + peer estimate (see [deriveStableTipPeriodically]) — no HTTP tip
     *  call. 0 until first poll; matches the main wallet screen's denominator. */
    private val _externalTip = MutableStateFlow(0L)

    /** CF SCAN frontier (`getLowestNeededHeight()`) — the only honest progress
     *  signal under the paced convoy, which deliberately holds the header/cfheader
     *  frontiers a full CF_CONVOY_WINDOW ahead of it. 0 before the ledger exists. */
    private val _scanFrontier = MutableStateFlow(0L)

    /** True while an abandoned compact-filter band awaits recovery — withholds
     *  "Synced" here exactly as it does on the main screen. */
    private val _abandonedBandUnrecovered = MutableStateFlow(false)

    /** CF-gated sync frontier — the SAME derivation ([deriveSyncFrontier]) the
     *  main wallet screen uses, so the Network Info screen shares its CF-gated
     *  STAGE and can't categorically diverge (e.g. show "Synced" while cfheaders
     *  lags). Inputs are polled per-VM, so the exact block number can differ by
     *  a poll interval during active sync; it converges in steady state. */
    val syncFrontier: StateFlow<SyncFrontier> = combine(
        syncState, _peerCount, _lastBlockHeight, _estimatedHeight, _externalTip, _cfTip,
        _scanFrontier, _abandonedBandUnrecovered,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        deriveSyncFrontier(
            state = values[0] as SyncState,
            peerCount = values[1] as Int,
            currentHeight = values[2] as Long,
            targetHeight = values[3] as Long,
            externalTip = values[4] as Long,
            cfTip = values[5] as Long,
            // Same convoy re-key + band gate as the main wallet screen. If only one
            // of the two surfaces took these, Network Info would go back to claiming
            // "Synced" while the main card honestly showed a scan still descending —
            // the exact categorical divergence deriveSyncFrontier exists to prevent.
            scanFrontier = values[6] as Long,
            abandonedBandUnrecovered = values[7] as Boolean,
        )
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        deriveSyncFrontier(SyncState.Idle, 0, 0L, 0L, 0L, 0L)
    )

    // ── Wallet config ─────────────────────────────────────────────────────────
    private val _config = MutableStateFlow(WalletConfigEntity())
    val config: StateFlow<WalletConfigEntity> = _config.asStateFlow()

    /** Legacy, unread by any screen — see the note where setFiatCurrency used to be. */
    @Deprecated("The display currency lives in DisplayCurrencyStore; this is migration-only state.")
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

    // ── Beta update channel ──────────────────────────────────────────────────
    /** Whether the update check may offer PRERELEASES (default OFF / opt-in).
     *
     *  GitHub's /releases/latest excludes prereleases, so a `-beta` tag notifies nobody.
     *  That is deliberate — a build with unverified surface must not be pushed to every
     *  user — but it leaves testers with no way to be told a beta exists. This opt-in
     *  switches UpdateChecker to the full /releases list for people who asked for it. */
    private val _betaUpdatesEnabled = MutableStateFlow(
        context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
            .getBoolean("beta_updates", false)
    )
    val betaUpdatesEnabled: StateFlow<Boolean> = _betaUpdatesEnabled.asStateFlow()

    fun setBetaUpdatesEnabled(enabled: Boolean) {
        _betaUpdatesEnabled.value = enabled
        context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("beta_updates", enabled).apply()
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

    private val _customNodeLabel = MutableStateFlow(CustomNodePrefs.label(context))
    val customNodeLabel: StateFlow<String?> = _customNodeLabel.asStateFlow()

    private val _customNodeExclusive = MutableStateFlow(CustomNodePrefs.isExclusive(context))
    val customNodeExclusive: StateFlow<Boolean> = _customNodeExclusive.asStateFlow()

    /** Outcome of [pairFromUri]. NET_MISMATCH is a soft warning — the pairing is still
     *  persisted + enabled; the caller decides whether to surface a confirmation prompt. */
    enum class PairResult { OK, INVALID, NET_MISMATCH }

    fun setCustomNodeEnabled(enabled: Boolean) {
        CustomNodePrefs.setEnabled(context, enabled)
        _customNodeEnabled.value = enabled
        // Applies immediately: SyncService's ACTION_APPLY_OWN_NODE reruns the exact
        // keepalive reconnect triple (forceReconnect → re-inject → startSync), so
        // injectCustomNode() picks up the fresh isEnabled() and either pins the node
        // (true) or clears the pinned peer (false) without an app restart.
        applyOwnNodeNow()
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

    /** Parse a dgbnode:// URI (or raw host:port), persist + enable it as the pinned
     *  own node. NET_MISMATCH is a soft warning — the pairing is still persisted
     *  since [OwnNodeUri.net] is caller-declared metadata, not verified on-wire. */
    fun pairFromUri(raw: String): PairResult {
        val defaultPort = if (isTestnet(context)) CustomNode.TESTNET_DEFAULT_PORT
                          else CustomNode.MAINNET_DEFAULT_PORT
        val uri = OwnNodeUri.parse(raw, defaultPort) ?: return PairResult.INVALID
        CustomNodePrefs.setHostPort(context, uri.node.asHostPort())
        CustomNodePrefs.setLabel(context, uri.label)
        CustomNodePrefs.setEnabled(context, true)
        _customNodeHostPort.value = uri.node.asHostPort()
        _customNodeLabel.value = uri.label
        _customNodeEnabled.value = true
        val mismatch = uri.net != null && (uri.net == "testnet") != isTestnet(context)
        return if (mismatch) PairResult.NET_MISMATCH else PairResult.OK
    }

    /** Persist the exclusive-peer toggle (own node becomes the ONLY peer dialed). */
    fun setCustomNodeExclusive(exclusive: Boolean) {
        CustomNodePrefs.setExclusive(context, exclusive)
        _customNodeExclusive.value = exclusive
    }

    /** Apply the current own-node prefs immediately (no app restart) via SyncService's
     *  ACTION_APPLY_OWN_NODE — the same forceReconnect → re-inject → startSync triple
     *  the keepalive coroutine uses to recover a stalled peer pool. */
    fun applyOwnNodeNow() {
        // Caller-side FGS starts can throw ForegroundServiceStartNotAllowedException on Android
        // 12+ — e.g. the dataSync 6h/24h budget is spent on a long-syncing device — which crashed
        // the app when the user flicked "Use my own node" (DGB-1006, Pixel 10 / Android 17). The
        // service's own startForeground() try/catch never runs because the throw is caller-side,
        // before onStartCommand. Swallow it: the own-node pref is already persisted, so it applies
        // on the next service kick (onResume / the keepalive watchdog) — the same recovery
        // WalletViewModel's guarded starts rely on. Better a delayed apply than a crash.
        try {
            val intent = Intent(context, SyncService::class.java).setAction(SyncService.ACTION_APPLY_OWN_NODE)
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            android.util.Log.e("SettingsVM", "applyOwnNodeNow: startForegroundService threw", t)
        }
    }

    // ── Action results ────────────────────────────────────────────────────────
    private val _wipeResult = MutableStateFlow<WipeResult?>(null)
    val wipeResult: StateFlow<WipeResult?> = _wipeResult.asStateFlow()

    init {
        refreshNetworkStats()
        loadConfig()
        deriveStableTipPeriodically()
    }

    /** Maintain the stable sync-target tip natively (mirrors WalletViewModel) so
     *  the Network Info progress denominator matches the main screen — a
     *  MONOTONIC high-water mark of ONLY the PoW-validated header height, with no
     *  digiscope.me /api/chain/tip HTTP call. The peer estimate is NOT latched
     *  (it flows live as [_estimatedHeight]/targetHeight, which deriveSyncFrontier
     *  maxes against) so a transient inflated single-peer estimate can't pin the
     *  bar below 100% forever. Both screens latch the same header height, so they
     *  converge. */
    private fun deriveStableTipPeriodically() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val header = runCatching { NativeBridge.getLastBlockHeight() }.getOrDefault(0L)
                if (header > _externalTip.value) _externalTip.value = header
                delay(30_000L)
            }
        }
    }

    /**
     * READ-ONLY network stats for the Network Info screen. MUST NOT mutate sync
     * state.
     *
     * This previously called forceReconnect()+startSync() whenever it read 0
     * peers, so merely opening (or auto-refreshing) the Network Info screen could
     * tear down a working/recovering peer manager. Worse, that uncoordinated
     * startSync bypasses SyncService's saved-block-header load path, so the
     * recreated manager could come up with an empty chain — block height 0, and
     * an incoming tx unable to confirm (observed on-device, v3.10.1). A display
     * screen must never drive sync; reconnect is the wallet screen's
     * pull-to-refresh (the coordinated, on-device-proven path).
     */
    fun refreshNetworkStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _peerCount.value = runCatching { NativeBridge.getPeerCount() }.getOrDefault(0)
            _lastBlockHeight.value = runCatching { NativeBridge.getLastBlockHeight() }.getOrDefault(0L)
            _estimatedHeight.value = runCatching { NativeBridge.getEstimatedBlockHeight() }.getOrDefault(0L)
            _cfTip.value = runCatching { NativeBridge.getCFChainTipHeight().toLong() }.getOrDefault(0L)
            _scanFrontier.value =
                runCatching { NativeBridge.getLowestNeededHeight() }.getOrDefault(0L)
            _abandonedBandUnrecovered.value =
                io.digibyte.core.sync.CfAbandonmentStore.unrecoveredBand(context) != null
            // CF scan ledger (Phase-1 observe-only). Guarded like the other native
            // reads — native returns empty/short arrays before startSync.
            runCatching {
                val c = NativeBridge.getCfScanLedgerCounts()
                if (c.size >= 4) _cfLedgerCounts.value = CfLedgerCounts(c[0], c[1], c[2], c[3])
            }
            runCatching {
                val flat = NativeBridge.getCfScanLedgerHoleRanges()
                val holes = ArrayList<String>(flat.size / 2)
                var i = 0
                while (i + 1 < flat.size) {
                    holes.add("${flat[i]}–${flat[i + 1]}") // en-dash between start–end
                    i += 2
                }
                _cfLedgerHoles.value = holes
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
    /** Verify a PIN through the rate-limited [PinManager]. Returns the full
     *  [PinVerifyResult] so Settings re-auth gates enforce the SAME lockout the
     *  unlock screen does — a brute-forcer can't bypass the limit via Settings. */
    fun verifyPin(pin: String): PinVerifyResult = pinManager.verifyPin(pin)

    fun changePin(newPin: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pinManager.setPin(newPin)
        }
    }

    // ── Wipe-after-N (destructive, opt-in, default OFF) ───────────────────────
    private val _wipeAfterNEnabled = MutableStateFlow(pinManager.isWipeAfterNEnabled())
    val wipeAfterNEnabled: StateFlow<Boolean> = _wipeAfterNEnabled.asStateFlow()

    /** Enable/disable "wipe wallet after N failed PIN attempts". Enabling is gated
     *  by an explicit backup acknowledgement in the UI (irreversible on-device). */
    fun setWipeAfterN(enabled: Boolean) {
        pinManager.setWipeAfterN(enabled)
        _wipeAfterNEnabled.value = enabled
    }

    // ── Display preferences ───────────────────────────────────────────────────
    /**
     * REMOVED — the display currency now has one home, [io.digibyte.ui.wallet.DisplayCurrencyStore].
     *
     * This wrote WalletConfig.fiatCurrency, which nothing read except the screen that wrote it,
     * so Settings could show USD while the wallet showed BTC. The Room column stays for one
     * reason only: WalletViewModel reads it once, to adopt a currency someone chose here before
     * the control was wired to anything. Writing it again would resurrect the second source of
     * truth this deleted.
     */

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

/** CF scan-ledger counters surfaced to the Network Info screen (Phase-1 observe-only). */
data class CfLedgerCounts(
    val scannedThrough: Long = 0L,
    val outstanding: Long = 0L,
    val gaveUp: Long = 0L,
    val pending: Long = 0L,
)
