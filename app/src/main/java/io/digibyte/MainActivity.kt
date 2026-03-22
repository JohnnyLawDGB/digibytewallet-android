package io.digibyte

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.core.WalletManager
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.PinManager
import io.digibyte.ui.navigation.AppNavigation
import io.digibyte.ui.theme.DigiByteTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var biometricAuth: BiometricAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DigiByteTheme {
                AppNavigation(
                    walletManager = walletManager,
                    pinManager = pinManager,
                    biometricAuth = biometricAuth
                )
            }
        }
    }
}
