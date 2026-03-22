package io.digibyte

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
import io.digibyte.service.SyncService
import io.digibyte.ui.navigation.AppNavigation
import io.digibyte.ui.theme.DigiByteTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var biometricAuth: BiometricAuth
    @Inject lateinit var keyStoreManager: KeyStoreManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If the wallet is already unlocked at launch (e.g. biometric kept the
        // session alive), start the foreground sync service immediately so the
        // user sees live sync progress as soon as the wallet screen renders.
        if (walletManager.walletState.value is WalletState.Unlocked) {
            startSyncService()
        }

        setContent {
            DigiByteTheme {
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
