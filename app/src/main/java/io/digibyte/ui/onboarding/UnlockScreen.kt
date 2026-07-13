package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.core.WalletManager
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.BiometricResult
import io.digibyte.core.security.PinManager
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UNLOCK_PIN_LENGTH = 6
private const val MAX_ATTEMPTS = 5

@Composable
fun UnlockScreen(
    navController: NavController,
    pinManager: PinManager,
    biometricAuth: BiometricAuth,
    walletManager: WalletManager
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var currentInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var attemptCount by remember { mutableIntStateOf(0) }
    // True while unlockFromUi()/restoreFromDisk() are running in the background.
    // restoreFromDisk() calls NativeBridge.stopSync(), which takes the native
    // peer-manager lock (PEER_GUARD) — the keepalive sweep can hold that lock for
    // up to ~K×10s pinging half-dead sockets, so this work must run off the main
    // thread (this was the ANR trace's attemptUnlock → unlockFromUi/restoreFromDisk
    // → stopSync path). The keypad/biometric button are disabled and a brief
    // "Unlocking…" indicator is shown while this is in flight.
    var isUnlocking by remember { mutableStateOf(false) }
    val biometricAvailable = remember { activity?.let { biometricAuth.canAuthenticate(it) } ?: false }

    // Attempt biometric automatically on first composition if available
    LaunchedEffect(Unit) {
        if (biometricAvailable && activity != null) {
            val result = biometricAuth.authenticate(activity)
            if (result is BiometricResult.Success) {
                isUnlocking = true
                withContext(Dispatchers.IO) {
                    if (walletManager.isWalletReady()) {
                        walletManager.unlockFromUi()
                    } else {
                        walletManager.restoreFromDisk()
                    }
                }
                isUnlocking = false
                navController.navigate("wallet") {
                    popUpTo("unlock") { inclusive = true }
                }
            }
        }
    }

    fun attemptUnlock(pin: String) {
        if (pinManager.verifyPin(pin)) {
            scope.launch {
                // For UI-only relock (wallet already loaded in memory), just flip state.
                // For fresh process (wallet not loaded), restore from disk — both run
                // off the main thread since restoreFromDisk() can block on the native
                // peer-manager lock.
                isUnlocking = true
                withContext(Dispatchers.IO) {
                    if (walletManager.isWalletReady()) {
                        walletManager.unlockFromUi()
                    } else {
                        walletManager.restoreFromDisk()
                    }
                }
                isUnlocking = false
                navController.navigate("wallet") {
                    popUpTo("unlock") { inclusive = true }
                }
            }
        } else {
            attemptCount++
            currentInput = ""
            errorMessage = if (attemptCount >= MAX_ATTEMPTS) {
                "Too many attempts. Please wait before trying again."
            } else {
                "Incorrect PIN (${MAX_ATTEMPTS - attemptCount} attempts remaining)"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DigiByteLogoHeader()

                Spacer(Modifier.height(40.dp))

                Text(
                    text = "Enter PIN",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Enter your 6-digit PIN to unlock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0BEC5),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                // PIN dot row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(UNLOCK_PIN_LENGTH) { idx ->
                        val filled = idx < currentInput.length
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (filled) DigiByteAccent else Color.Transparent)
                                .border(2.dp, if (filled) DigiByteAccent else Color(0xFF243352), CircleShape)
                        )
                    }
                }

                errorMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        color = DigiByteRed,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                if (isUnlocking) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = DigiByteAccent
                        )
                        Text(
                            text = "Unlocking…",
                            color = Color(0xFFB0BEC5),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Biometric button
                if (biometricAvailable) {
                    Spacer(Modifier.height(24.dp))
                    IconButton(
                        enabled = !isUnlocking,
                        onClick = {
                            scope.launch {
                                if (activity != null) {
                                    val result = biometricAuth.authenticate(activity)
                                    if (result is BiometricResult.Success) {
                                        isUnlocking = true
                                        withContext(Dispatchers.IO) {
                                            if (walletManager.isWalletReady()) {
                                                walletManager.unlockFromUi()
                                            } else {
                                                walletManager.restoreFromDisk()
                                            }
                                        }
                                        isUnlocking = false
                                        navController.navigate("wallet") {
                                            popUpTo("unlock") { inclusive = true }
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Unlock with biometrics",
                            tint = if (isUnlocking) Color(0xFF546E7A) else DigiByteAccent,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            // Numeric keypad
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫")
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEach { key ->
                            when (key) {
                                "" -> Spacer(Modifier.size(72.dp))
                                "⌫" -> {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = !isUnlocking) {
                                                if (currentInput.isNotEmpty()) {
                                                    currentInput = currentInput.dropLast(1)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Backspace,
                                            contentDescription = "Backspace",
                                            tint = Color(0xFFB0BEC5),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1A2742))
                                            .border(1.dp, Color(0xFF243352), CircleShape)
                                            .clickable(enabled = attemptCount < MAX_ATTEMPTS && !isUnlocking) {
                                                if (currentInput.length < UNLOCK_PIN_LENGTH) {
                                                    currentInput += key
                                                    errorMessage = null
                                                    if (currentInput.length == UNLOCK_PIN_LENGTH) {
                                                        attemptUnlock(currentInput)
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (attemptCount < MAX_ATTEMPTS && !isUnlocking) Color.White else Color(0xFF546E7A)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
