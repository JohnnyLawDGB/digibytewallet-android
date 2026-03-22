package io.digibyte

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.db.dao.WalletConfigDao
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
import io.digibyte.core.tor.TorManager
import io.digibyte.service.SyncService
import io.digibyte.ui.navigation.AppNavigation
import io.digibyte.ui.theme.DigiByteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var biometricAuth: BiometricAuth
    @Inject lateinit var keyStoreManager: KeyStoreManager
    @Inject lateinit var torManager: TorManager
    @Inject lateinit var walletConfigDao: WalletConfigDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

                // A simple mechanism to trigger the dialog: re-check the flag
                // once the composition is ready by reading it from TorManager prefs.
                // The flag is set in applyTorDefaults() before setContent() runs
                // for the first launch, but LaunchedEffect ensures it re-checks.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val cfg = withContext(Dispatchers.IO) { walletConfigDao.get() }
                    if (cfg != null && !cfg.torPromptShown) {
                        showTorPrompt = true
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

                AppNavigation(
                    walletManager = walletManager,
                    pinManager = pinManager,
                    biometricAuth = biometricAuth,
                    keyStoreManager = keyStoreManager
                )
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
            // New install — enable Tor by default, mark prompt as shown
            // (no need to ask — it's privacy-by-default).
            torManager.isEnabled = true
            torManager.upgradePromptShown = true
            // WalletConfigEntity will be created by onboarding flow; TorManager
            // prefs already have the right value. Nothing more needed here.
        }
        // Upgrade case: cfg != null && !cfg.torPromptShown — dialog handles it.
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
}
