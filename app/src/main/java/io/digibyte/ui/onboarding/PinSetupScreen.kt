package io.digibyte.ui.onboarding

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.BiometricResult
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val PIN_LENGTH = 6

private enum class PinStep { ENTER, CONFIRM, BIOMETRIC }

@Composable
fun PinSetupScreen(
    navController: NavController,
    biometricAuth: BiometricAuth,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(PinStep.ENTER) }
    var firstPin by remember { mutableStateOf("") }
    var currentInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var biometricAvailable by remember { mutableStateOf(false) }
    var isPinSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        biometricAvailable = activity?.let { biometricAuth.canAuthenticate(it) } ?: false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        when (step) {
            PinStep.ENTER, PinStep.CONFIRM -> {
                PinEntryContent(
                    step = step,
                    currentInput = currentInput,
                    errorMessage = errorMessage,
                    onDigit = { digit ->
                        if (currentInput.length < PIN_LENGTH) {
                            currentInput += digit
                            errorMessage = null

                            if (currentInput.length == PIN_LENGTH) {
                                when (step) {
                                    PinStep.ENTER -> {
                                        firstPin = currentInput
                                        currentInput = ""
                                        step = PinStep.CONFIRM
                                    }
                                    PinStep.CONFIRM -> {
                                        if (currentInput == firstPin) {
                                            isPinSaving = true
                                            viewModel.setPin(currentInput) { pinSuccess ->
                                                if (pinSuccess) {
                                                    // Create the wallet if not already created.
                                                    // The mnemonic is still in the ViewModel
                                                    // from the seed display/verify flow.
                                                    val afterWalletReady: (Boolean) -> Unit = { success ->
                                                        if (success) {
                                                            // Kick off sync on the IO dispatcher — BRPeerManagerConnect
                                                            // opens sockets and can block for seconds on a cold start,
                                                            // which will ANR this callback chain if it runs on Main.
                                                            scope.launch {
                                                                withContext(Dispatchers.IO) {
                                                                    runCatching {
                                                                        io.digibyte.core.bridge.NativeBridge.startSync()
                                                                    }
                                                                }
                                                                isPinSaving = false
                                                                if (biometricAvailable && activity != null) {
                                                                    step = PinStep.BIOMETRIC
                                                                } else {
                                                                    navController.navigate("wallet") {
                                                                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            isPinSaving = false
                                                            errorMessage = "Wallet creation failed. Please try again."
                                                            currentInput = ""
                                                            step = PinStep.ENTER
                                                            firstPin = ""
                                                        }
                                                    }
                                                    // If wallet already exists (recovery flow or
                                                    // recomposition), skip creation and proceed.
                                                    if (io.digibyte.core.bridge.NativeBridge.isWalletLoaded()) {
                                                        afterWalletReady(true)
                                                    } else {
                                                        viewModel.createWallet(afterWalletReady)
                                                    }
                                                } else {
                                                    isPinSaving = false
                                                    errorMessage = "Failed to save PIN. Please try again."
                                                    currentInput = ""
                                                    step = PinStep.ENTER
                                                    firstPin = ""
                                                }
                                            }
                                        } else {
                                            errorMessage = "PINs do not match. Please try again."
                                            currentInput = ""
                                            firstPin = ""
                                            step = PinStep.ENTER
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    },
                    onBackspace = {
                        if (currentInput.isNotEmpty()) {
                            currentInput = currentInput.dropLast(1)
                            errorMessage = null
                        }
                    },
                    isPinSaving = isPinSaving
                )
            }

            PinStep.BIOMETRIC -> {
                BiometricOptInContent(
                    onEnable = {
                        scope.launch {
                            if (activity != null) {
                                biometricAuth.authenticate(
                                    activity,
                                    title = "Enable Biometric Unlock",
                                    subtitle = "Authenticate to enable fingerprint/face unlock"
                                )
                            }
                            // Wallet already created before reaching biometric step
                            navController.navigate("wallet") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    },
                    onSkip = {
                        // Wallet already created before reaching biometric step
                        navController.navigate("wallet") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PinEntryContent(
    step: PinStep,
    currentInput: String,
    errorMessage: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    isPinSaving: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "pin_step_label"
            ) { currentStep ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (currentStep == PinStep.ENTER) "Set Your PIN" else "Confirm Your PIN",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (currentStep == PinStep.ENTER)
                            "Choose a 6-digit PIN to protect your wallet"
                        else
                            "Enter your PIN again to confirm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0BEC5),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // PIN dot indicators
            PinDotRow(filledCount = currentInput.length, totalDots = PIN_LENGTH)

            // Error message
            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = DigiByteRed,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            if (isPinSaving) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = DigiByteAccent
                )
            }
        }

        // Numeric keypad
        NumericKeypad(
            onDigit = onDigit,
            onBackspace = onBackspace
        )
    }
}

@Composable
private fun PinDotRow(filledCount: Int, totalDots: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalDots) { idx ->
            val filled = idx < filledCount
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (filled) DigiByteAccent else Color.Transparent
                    )
                    .border(
                        2.dp,
                        if (filled) DigiByteAccent else Color(0xFF243352),
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit
) {
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
                                    .clickable { onBackspace() },
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
                                    .clickable { onDigit(key) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricOptInContent(
    onEnable: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            tint = DigiByteAccent,
            modifier = Modifier.size(72.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Enable Biometric Unlock?",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Use your fingerprint or face to unlock the wallet quickly. You can always use your PIN instead.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB0BEC5),
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onEnable,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
        ) {
            Text(
                text = "Enable Biometrics",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Skip for now",
                color = Color(0xFFB0BEC5),
                fontSize = 15.sp
            )
        }
    }
}
