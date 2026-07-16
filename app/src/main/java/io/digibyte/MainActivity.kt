package io.digibyte

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.core.AppUpdate
import io.digibyte.core.UpdateChecker
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.reconcile.PostUpgradeReconciler
import io.digibyte.ui.components.UpdateDialog
import okhttp3.OkHttpClient
import io.digibyte.core.db.dao.WalletConfigDao
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
import io.digibyte.service.SyncService
import io.digibyte.ui.navigation.AppNavigation
import io.digibyte.ui.theme.DigiByteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var biometricAuth: BiometricAuth
    @Inject lateinit var keyStoreManager: KeyStoreManager
    @Inject lateinit var torManager: TorManager
    @Inject lateinit var walletConfigDao: WalletConfigDao
    @Inject lateinit var digiScopeClient: DigiScopeClient
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var assetManager: io.digibyte.core.asset.AssetManager

    /** Pending Digi-ID URI from a deep link — processed after PIN unlock. */
    var pendingDigiIdUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Wipe-after-N backstop: if a prior wipe-after-N was interrupted (process
        // killed between PinManager returning ShouldWipe and the wallet wipe
        // completing), finish it now — BEFORE any unlock UI is shown — so we never
        // present an unlock screen over a half-wiped wallet. Rare recovery path;
        // at cold start the native peer manager isn't running yet so this is quick.
        if (pinManager.isWipePending()) {
            android.util.Log.w("MainActivity", "pin_wipe_pending set — completing interrupted wallet wipe")
            kotlinx.coroutines.runBlocking {
                runCatching { walletManager.wipeWallet() }
                    .onFailure { android.util.Log.e("MainActivity", "backstop wipe failed", it) }
                pinManager.clearPin() // clears counters + pin_wipe_pending so we don't loop
            }
        }

        // Capture digiid:// deep link from launching intent
        handleDigiIdIntent(intent)

        // Handle new install vs Phase 1 upgrade Tor defaults.
        // Runs on IO thread; UI state is surfaced via showTorUpgradeDialog flag.
        lifecycleScope.launch {
            applyTorDefaults()
        }

        // If the wallet is already unlocked at launch (e.g. biometric kept the
        // session alive), start the foreground sync service immediately so the
        // user sees live sync progress as soon as the wallet screen renders.
        if (walletManager.walletState.value is WalletState.Unlocked) {
            startSyncService()
        }

        // Silently run post-upgrade reconcile once per version bump.
        // Waits for the first Unlocked state (biometric auto-unlock or PIN),
        // then hands off to PostUpgradeReconciler which internally debounces
        // for peer connectivity before hitting the node.
        //
        // hydrateFailedFlag here so the UI banner reflects a prior-process
        // failure even before this new attempt has had time to resolve.
        PostUpgradeReconciler.hydrateFailedFlag(applicationContext)
        lifecycleScope.launch {
            walletManager.walletState
                .filterIsInstance<WalletState.Unlocked>()
                .first()
            PostUpgradeReconciler.runIfNeeded(applicationContext, assetManager)
        }

        setContent {
            DigiByteTheme {
                // One-time upgrade dialog: shown only when a Phase 1 wallet is
                // first opened after the Phase 2 update.
                var showTorPrompt by remember { mutableStateOf(false) }
                val promptCallback: (Boolean) -> Unit = { enabled ->
                    torManager.isEnabled = enabled
                    torManager.upgradePromptShown = true
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cfg = walletConfigDao.get()
                        if (cfg != null) {
                            walletConfigDao.upsert(
                                cfg.copy(torEnabled = enabled, torPromptShown = true)
                            )
                        }
                    }
                    showTorPrompt = false
                }

                // Tor is disabled by default and no longer promoted — its no-exec
                // SOCKS peer-routing is broken (roadmapped). Suppress the legacy
                // "enable Tor?" first-launch prompt for upgrading Phase-1 wallets:
                // mark it shown WITHOUT offering it, so nobody is nudged into a
                // non-functional feature. Tor remains an opt-in in Settings.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val cfg = withContext(Dispatchers.IO) { walletConfigDao.get() }
                    if (cfg != null && !cfg.torPromptShown) {
                        torManager.upgradePromptShown = true
                        withContext(Dispatchers.IO) {
                            walletConfigDao.upsert(cfg.copy(torPromptShown = true))
                        }
                    }
                }

                if (showTorPrompt) {
                    AlertDialog(
                        onDismissRequest = { promptCallback(false) },
                        title = { Text("New Privacy Feature") },
                        text = {
                            Text(
                                "Tor routing is now available. When enabled, your wallet " +
                                "connects to the DigiByte network through Tor, hiding your IP " +
                                "address from peers.\n\nEnable Tor routing?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { promptCallback(true) }) {
                                Text("Enable")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { promptCallback(false) }) {
                                Text("Not now")
                            }
                        }
                    )
                }

                // Update check
                var pendingUpdate by remember { mutableStateOf<AppUpdate?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                    } catch (e: Exception) { "0" }
                    val update = UpdateChecker(okHttpClient).checkForUpdate(currentVersion)
                    if (update != null) pendingUpdate = update
                }
                if (pendingUpdate != null) {
                    UpdateDialog(
                        update = pendingUpdate!!,
                        onDismiss = { pendingUpdate = null }
                    )
                }

                AppNavigation(
                    walletManager = walletManager,
                    pinManager = pinManager,
                    biometricAuth = biometricAuth,
                    keyStoreManager = keyStoreManager,
                    digiScopeClient = digiScopeClient
                )
            }
        }
    }

    /**
     * Lock the wallet UI when the activity goes to background.
     * SyncService continues running but the user must re-enter PIN/biometric
     * when they return to the app. This prevents unauthorized access if
     * someone picks up an unlocked phone.
     */
    override fun onStop() {
        super.onStop()
        // UI-only lock — requires PIN/biometric to re-enter the app.
        // Does NOT call NativeBridge.lockSession() — the native seed stays
        // in memory so SyncService can continue syncing in the background.
        if (walletManager.walletState.value is WalletState.Unlocked) {
            walletManager.lockUi()
        }
    }

    /**
     * Defensive reconnect on foreground return.
     *
     * The SyncService runs a 10s peer-keepalive loop that should already
     * re-inject bloom peers and call startSync when peerCount drops to 0
     * (commit b1d26f2f). In practice users have hit a state where the app
     * has been backgrounded for hours, returns with 0 peers, and doesn't
     * recover on its own — likely the keepalive coroutine stalling through
     * Doze or dying silently on an uncaught throwable.
     *
     * As a user-visible recovery path: any time the activity comes to
     * foreground with the wallet unlocked and peerCount == 0, call
     * startSync() directly. The native bridge's startSync re-injects the
     * priority peer list and invokes BRPeerManagerConnect, which is
     * idempotent — safe to call when already connected. This gives the
     * user "reopen the app to reconnect" as a reliable escape hatch
     * alongside the keepalive's try/catch hardening in SyncService.
     */
    override fun onResume() {
        super.onResume()
        if (walletManager.walletState.value !is WalletState.Unlocked) return
        // getPeerCount() takes the native peer-manager lock (PEER_GUARD), which the
        // keepalive sweep can hold for up to ~K×10s while pinging half-dead peer
        // sockets. Reading it — and the follow-up reconnect decision — must happen
        // off the main thread so onResume always returns immediately; it must NEVER
        // call getPeerCount() directly here (that's the ANR).
        lifecycleScope.launch(Dispatchers.IO) {
            val peers = try { NativeBridge.getPeerCount() } catch (_: Throwable) { -1 }
            // Don't kick a direct startSync while Tor is enabled and still
            // bootstrapping — peers would dial direct before the SOCKS proxy is wired,
            // leaking the IP. Once Tor reaches Connected (bootstrap 100%) or fails, the
            // proxy is set / we've degraded, and this kick connects through Tor / direct.
            val torState = torManager.state.value
            val torComingUp = torManager.isEnabled &&
                (torState is TorState.Connecting || torState is TorState.Starting)
            if (peers == 0 && !torComingUp) {
                android.util.Log.i("MainActivity", "onResume with peerCount=0 — forcing a clean reconnect")
                try {
                    // forceReconnect first: after a long idle the existing manager is
                    // often stuck (dead peers holding the slots) and startSync alone
                    // (→ BRPeerManagerConnect) can't dig it out. A clean recreate does.
                    NativeBridge.forceReconnect()
                    NativeBridge.startSync()
                } catch (t: Throwable) {
                    android.util.Log.w("MainActivity", "reconnect kick failed", t)
                }
            }
        }
    }

    /**
     * Determine and apply Tor defaults based on install type:
     * - New install (no wallet config row): enable Tor by default.
     * - Phase 1 upgrade (config exists, torPromptShown = false): leave isEnabled alone;
     *   the dialog in setContent() will handle it.
     * - Normal run (torPromptShown = true): nothing to do.
     */
    private suspend fun applyTorDefaults() {
        val cfg = withContext(Dispatchers.IO) { walletConfigDao.get() }
        if (cfg == null) {
            // New install — Tor OFF by default. In-app Tor (kmp-tor no-exec) has a
            // broken SOCKS peer-routing path (the C core gets "connection refused"
            // on the in-process listener and degrades to direct every session), so
            // we neither enable nor promote it. Privacy by default is BIP158
            // compact filters (address privacy); IP anonymity, for users who need
            // it, is better served by Orbot or a system VPN. Tor stays available as
            // an opt-in in Settings → Network Info. Re-evaluate when the no-exec
            // SOCKS routing is fixed or Tor is removed (see ROADMAP).
            torManager.isEnabled = false
            torManager.upgradePromptShown = true
        }
        // Upgrade case: cfg != null && !cfg.torPromptShown — the LaunchedEffect in
        // setContent() now suppresses the legacy prompt (we don't promote Tor).
        // Normal run: cfg != null && cfg.torPromptShown — nothing to do.
    }

    /**
     * Start the SPV sync foreground service.
     * Safe to call multiple times — the service is START_STICKY and Android
     * delivers subsequent starts as additional onStartCommand calls which the
     * service ignores once already running.
     */
    internal fun startSyncService() {
        val intent = Intent(this, SyncService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDigiIdIntent(intent)
    }

    private fun handleDigiIdIntent(intent: Intent?) {
        val uri = intent?.data?.toString()
        if (uri != null && uri.startsWith("digiid://")) {
            pendingDigiIdUri = uri
            android.util.Log.i("MainActivity", "Captured Digi-ID deep link: $uri")
        }
    }
}
