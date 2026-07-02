@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
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
import kotlinx.coroutines.launch

private const val SEC_PIN_LENGTH = 6

// ── Internal step state for security actions ──────────────────────────────────
private enum class SecurityDialog { None, ChangePinVerify, ChangePinNew, ChangePinConfirm, ViewSeedWarning, ViewSeedPinVerify, WipePinVerify, WipeConfirmDialog }

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
    pinManager: PinManager,
    biometricAuth: BiometricAuth,
    walletManager: WalletManager,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    val biometricAvailable = remember {
        activity?.let { biometricAuth.canAuthenticate(it) } ?: false
    }

    var activeDialog by remember { mutableStateOf(SecurityDialog.None) }
    var pinInput by remember { mutableStateOf("") }
    var newPinFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var snackMessage by remember { mutableStateOf<String?>(null) }

    // Auto-lock timeout options (ms → label)
    val timeoutOptions = remember {
        listOf(
            60_000L to "1 minute",
            5 * 60_000L to "5 minutes",
            15 * 60_000L to "15 minutes",
            30 * 60_000L to "30 minutes"
        )
    }
    val currentTimeout by viewModel.autoLockTimeout.collectAsStateWithLifecycle()

    val wipeResult by viewModel.wipeResult.collectAsStateWithLifecycle()
    LaunchedEffect(wipeResult) {
        if (wipeResult is WipeResult.Success) {
            navController.navigate("onboarding") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    fun resetDialogState() {
        activeDialog = SecurityDialog.None
        pinInput = ""
        newPinFirst = ""
        pinError = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Security", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
            )
        },
        containerColor = Color(0xFF0A1628)
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                SettingsCategory(title = "PIN & Authentication") {
                    SettingsRow(
                        icon = Icons.Default.Pin,
                        iconTint = DigiByteAccent,
                        title = "Change PIN",
                        subtitle = "Update your 6-digit wallet PIN",
                        onClick = { activeDialog = SecurityDialog.ChangePinVerify }
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Default.Fingerprint,
                        iconTint = DigiByteBlue,
                        title = "Biometric Authentication",
                        subtitle = if (biometricAvailable) "Fingerprint / face unlock available" else "Not available on this device",
                        onClick = {},
                        trailing = {
                            Switch(
                                checked = biometricAvailable,
                                onCheckedChange = null, // read-only display — toggling requires deeper prefs
                                enabled = biometricAvailable,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DigiByteAccent,
                                    checkedTrackColor = DigiByteAccent.copy(alpha = 0.4f)
                                )
                            )
                        }
                    )
                    SettingsRowDivider()

                    // Auto-lock timeout dropdown
                    var showTimeoutMenu by remember { mutableStateOf(false) }
                    val currentLabel = timeoutOptions.firstOrNull { it.first == currentTimeout }?.second
                        ?: "Custom"
                    Box {
                        SettingsRow(
                            icon = Icons.Default.Timer,
                            iconTint = Color(0xFFFF9800),
                            title = "Auto-Lock Timeout",
                            subtitle = currentLabel,
                            onClick = { showTimeoutMenu = true }
                        )
                        DropdownMenu(
                            expanded = showTimeoutMenu,
                            onDismissRequest = { showTimeoutMenu = false },
                            modifier = Modifier.background(Color(0xFF1A2742))
                        ) {
                            timeoutOptions.forEach { (ms, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (ms == currentTimeout) DigiByteAccent else Color.White
                                        )
                                    },
                                    onClick = {
                                        viewModel.setAutoLockTimeout(ms)
                                        showTimeoutMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsCategory(title = "Recovery Phrase") {
                    SettingsRow(
                        icon = Icons.Default.Key,
                        iconTint = Color(0xFFFFD700),
                        title = "View Recovery Phrase",
                        subtitle = "Requires PIN + biometric verification",
                        onClick = { activeDialog = SecurityDialog.ViewSeedWarning }
                    )
                }
            }

            item {
                SettingsCategory(title = "Danger Zone") {
                    SettingsRow(
                        icon = Icons.Default.DeleteForever,
                        iconTint = DigiByteRed,
                        title = "Wipe Wallet",
                        subtitle = "Remove all wallet data from this device",
                        onClick = { activeDialog = SecurityDialog.WipePinVerify }
                    )
                }
            }
        }

        // ── Dialogs ─────────────────────────────────────────────────────────
        when (activeDialog) {

            SecurityDialog.ChangePinVerify -> {
                PinVerifyDialog(
                    title = "Verify Current PIN",
                    subtitle = "Enter your current PIN to continue",
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                if (viewModel.verifyPin(pinInput)) {
                                    pinInput = ""
                                    activeDialog = SecurityDialog.ChangePinNew
                                } else {
                                    pinError = "Incorrect PIN"
                                    pinInput = ""
                                }
                            }
                        }
                    },
                    onBackspace = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) },
                    onDismiss = ::resetDialogState
                )
            }

            SecurityDialog.ChangePinNew -> {
                PinVerifyDialog(
                    title = "New PIN",
                    subtitle = "Enter your new 6-digit PIN",
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                newPinFirst = pinInput
                                pinInput = ""
                                activeDialog = SecurityDialog.ChangePinConfirm
                            }
                        }
                    },
                    onBackspace = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) },
                    onDismiss = ::resetDialogState
                )
            }

            SecurityDialog.ChangePinConfirm -> {
                PinVerifyDialog(
                    title = "Confirm New PIN",
                    subtitle = "Re-enter your new PIN",
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                if (pinInput == newPinFirst) {
                                    viewModel.changePin(pinInput)
                                    resetDialogState()
                                    snackMessage = "PIN changed successfully"
                                } else {
                                    pinError = "PINs do not match"
                                    pinInput = ""
                                    activeDialog = SecurityDialog.ChangePinNew
                                }
                            }
                        }
                    },
                    onBackspace = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) },
                    onDismiss = ::resetDialogState
                )
            }

            SecurityDialog.ViewSeedWarning -> {
                AlertDialog(
                    onDismissRequest = ::resetDialogState,
                    containerColor = Color(0xFF1A2742),
                    title = {
                        Text(
                            "View Recovery Phrase",
                            color = Color(0xFFFFD700),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally)
                            )
                            Text(
                                "Your recovery phrase gives complete access to your wallet.\n\n" +
                                "• Never share it with anyone\n" +
                                "• Never enter it on any website\n" +
                                "• Store it offline in a safe place\n\n" +
                                "Continuing requires PIN + biometric authentication.",
                                color = Color(0xFFB0BEC5),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { activeDialog = SecurityDialog.ViewSeedPinVerify }
                        ) {
                            Text("I Understand — Continue", color = Color(0xFFFFD700))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = ::resetDialogState) {
                            Text("Cancel", color = Color(0xFF8899AA))
                        }
                    }
                )
            }

            SecurityDialog.ViewSeedPinVerify -> {
                PinVerifyDialog(
                    title = "Verify PIN",
                    subtitle = "Enter your PIN to view recovery phrase",
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                if (viewModel.verifyPin(pinInput)) {
                                    pinInput = ""
                                    // Now require biometric
                                    scope.launch {
                                        activeDialog = SecurityDialog.None
                                        if (activity != null && biometricAvailable) {
                                            val result = biometricAuth.authenticate(
                                                activity,
                                                title = "DigiByte Wallet",
                                                subtitle = "Authenticate to view recovery phrase"
                                            )
                                            if (result is BiometricResult.Success) {
                                                navController.navigate("settings_view_seed")
                                            } else {
                                                snackMessage = "Biometric authentication required"
                                            }
                                        } else if (!biometricAvailable) {
                                            // No biometric hardware — PIN alone suffices
                                            navController.navigate("settings_view_seed")
                                        }
                                    }
                                } else {
                                    pinError = "Incorrect PIN"
                                    pinInput = ""
                                }
                            }
                        }
                    },
                    onBackspace = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) },
                    onDismiss = ::resetDialogState
                )
            }

            SecurityDialog.WipePinVerify -> {
                PinVerifyDialog(
                    title = "Confirm PIN",
                    subtitle = "Enter your PIN to wipe the wallet",
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                if (viewModel.verifyPin(pinInput)) {
                                    pinInput = ""
                                    activeDialog = SecurityDialog.WipeConfirmDialog
                                } else {
                                    pinError = "Incorrect PIN"
                                    pinInput = ""
                                }
                            }
                        }
                    },
                    onBackspace = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) },
                    onDismiss = ::resetDialogState
                )
            }

            SecurityDialog.WipeConfirmDialog -> {
                AlertDialog(
                    onDismissRequest = ::resetDialogState,
                    containerColor = Color(0xFF1A2742),
                    title = {
                        Text("Wipe Wallet", color = DigiByteRed, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            "This cannot be undone.\n\n" +
                            "All wallet data, keys, and transaction history will be permanently deleted from this device.\n\n" +
                            "Make sure you have your recovery phrase backed up before continuing.",
                            color = Color(0xFFB0BEC5),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                activeDialog = SecurityDialog.None
                                viewModel.wipeWallet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DigiByteRed)
                        ) {
                            Text("Wipe Everything", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = ::resetDialogState) {
                            Text("Cancel", color = Color(0xFF8899AA))
                        }
                    }
                )
            }

            SecurityDialog.None -> { /* no dialog */ }
        }
    }
}

// ── Shared PIN entry dialog ───────────────────────────────────────────────────
@Composable
private fun PinVerifyDialog(
    title: String,
    subtitle: String,
    pinInput: String,
    pinError: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A2742),
        title = {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    subtitle,
                    color = Color(0xFF8899AA),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                // PIN dot indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(SEC_PIN_LENGTH) { idx ->
                        val filled = idx < pinInput.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (filled) DigiByteAccent else Color.Transparent)
                                .border(2.dp, if (filled) DigiByteAccent else Color(0xFF243352), CircleShape)
                        )
                    }
                }

                pinError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = DigiByteRed, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(20.dp))

                // Compact keypad
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("", "0", "⌫")
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEach { key ->
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    when (key) {
                                        "" -> Spacer(Modifier.size(52.dp))
                                        "⌫" -> {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(CircleShape)
                                                    .clickable { onBackspace() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Backspace,
                                                    contentDescription = "Backspace",
                                                    tint = Color(0xFF8899AA),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                        else -> {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF243352))
                                                    .border(1.dp, Color(0xFF3A4F6A), CircleShape)
                                                    .clickable { onDigit(key) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    key,
                                                    fontSize = 20.sp,
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
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8899AA))
            }
        }
    )
}
