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
import io.digibyte.core.security.PinVerifyResult
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteRed
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import io.digibyte.R

private const val SEC_PIN_LENGTH = 6

/** Format a re-auth lockout deadline as a localised "locked, try again in M:SS" at press time.
 *  (The dialog gates re-render per keystroke; a live tick isn't needed here — the
 *  enforcement lives in PinManager regardless of what this string says.)
 *  Takes Resources because it is not composable. */
private fun securityLockedMessage(res: android.content.res.Resources, until: Long): String {
    val remainingMs = (until - System.currentTimeMillis()).coerceAtLeast(0L)
    val totalSec = (remainingMs + 999L) / 1000L
    // The M:SS clock itself is a number format, not prose — only the sentence around it is
    // translated, so the digits read the same in every language.
    val clock = "%d:%02d".format(totalSec / 60, totalSec % 60)
    return res.getString(R.string.sec_locked_countdown, clock)
}

// ── Internal step state for security actions ──────────────────────────────────
private enum class SecurityDialog { None, ChangePinVerify, ChangePinNew, ChangePinConfirm, ViewSeedWarning, ViewSeedPinVerify, WipePinVerify, WipeConfirmDialog, WipeAfterNConfirm }

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
    pinManager: PinManager,
    biometricAuth: BiometricAuth,
    walletManager: WalletManager,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val appResources = androidx.compose.ui.platform.LocalContext.current.resources
    val pinChangedMsg = stringResource(R.string.sec_pin_changed)
    val pinsMismatchMsg = stringResource(R.string.sec_pins_mismatch)
    val bioRequiredMsg = stringResource(R.string.sec_biometric_required)
    // Hoisted: used inside click/callback lambdas, which are not composable.
    val wipeEnabledMsg = stringResource(R.string.sec_wipe_after_enabled, PinManager.WIPE_THRESHOLD)
    val authViewPhraseMsg = stringResource(R.string.sec_auth_view_phrase)
    val incorrectPinMsg = stringResource(R.string.sec_incorrect_pin)
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
    // NOT remember { }: stringResource is composable and cannot be called inside a remember
    // lambda. Building the list each recomposition is four string lookups, and it has to rebuild
    // anyway when the language changes.
    val timeoutOptions = listOf(
        60_000L to stringResource(R.string.sec_autolock_1m),
        5 * 60_000L to stringResource(R.string.sec_autolock_5m),
        15 * 60_000L to stringResource(R.string.sec_autolock_15m),
        30 * 60_000L to stringResource(R.string.sec_autolock_30m),
    )
    val currentTimeout by viewModel.autoLockTimeout.collectAsStateWithLifecycle()
    val wipeAfterNEnabled by viewModel.wipeAfterNEnabled.collectAsStateWithLifecycle()

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
                title = { Text(stringResource(R.string.set_security), color = Color.White) },
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
                SettingsCategory(title = stringResource(R.string.sec_pin_auth)) {
                    SettingsRow(
                        icon = Icons.Default.Pin,
                        iconTint = DigiByteAccent,
                        title = stringResource(R.string.sec_change_pin),
                        subtitle = stringResource(R.string.sec_change_pin_sub),
                        onClick = { activeDialog = SecurityDialog.ChangePinVerify }
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Default.Fingerprint,
                        iconTint = DigiByteBlue,
                        title = stringResource(R.string.sec_biometric),
                        subtitle = if (biometricAvailable) stringResource(R.string.sec_biometric_yes) else stringResource(R.string.sec_biometric_no),
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
                        ?: stringResource(R.string.sec_autolock_custom)
                    Box {
                        SettingsRow(
                            icon = Icons.Default.Timer,
                            iconTint = Color(0xFFFF9800),
                            title = stringResource(R.string.sec_autolock),
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

                    SettingsRowDivider()

                    // Wipe-after-N: destructive, opt-in, default OFF. Enabling requires
                    // an explicit "recovery phrase is backed up" acknowledgement — 10
                    // consecutive wrong PINs then permanently erase the wallet on-device.
                    SettingsRow(
                        icon = Icons.Default.DeleteSweep,
                        iconTint = DigiByteRed,
                        title = stringResource(R.string.sec_wipe_after, PinManager.WIPE_THRESHOLD),
                        subtitle = if (wipeAfterNEnabled)
                                       stringResource(R.string.sec_wipe_after_on, PinManager.WIPE_THRESHOLD)
                                   else stringResource(R.string.sec_wipe_after_off),
                        onClick = {
                            if (wipeAfterNEnabled) viewModel.setWipeAfterN(false)
                            else activeDialog = SecurityDialog.WipeAfterNConfirm
                        },
                        trailing = {
                            Switch(
                                checked = wipeAfterNEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) activeDialog = SecurityDialog.WipeAfterNConfirm
                                    else viewModel.setWipeAfterN(false)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DigiByteRed,
                                    checkedTrackColor = DigiByteRed.copy(alpha = 0.4f)
                                )
                            )
                        }
                    )
                }
            }

            item {
                SettingsCategory(title = stringResource(R.string.sec_recovery_phrase)) {
                    SettingsRow(
                        icon = Icons.Default.Key,
                        iconTint = Color(0xFFFFD700),
                        title = stringResource(R.string.sec_view_phrase),
                        subtitle = stringResource(R.string.sec_view_phrase_sub),
                        onClick = { activeDialog = SecurityDialog.ViewSeedWarning }
                    )
                }
            }

            item {
                SettingsCategory(title = stringResource(R.string.sec_danger_zone)) {
                    SettingsRow(
                        icon = Icons.Default.DeleteForever,
                        iconTint = DigiByteRed,
                        title = stringResource(R.string.sec_wipe_wallet),
                        subtitle = stringResource(R.string.sec_wipe_wallet_sub),
                        onClick = { activeDialog = SecurityDialog.WipePinVerify }
                    )
                }
            }
        }

        // ── Dialogs ─────────────────────────────────────────────────────────
        when (activeDialog) {

            SecurityDialog.ChangePinVerify -> {
                PinVerifyDialog(
                    title = stringResource(R.string.sec_verify_pin),
                    subtitle = stringResource(R.string.sec_verify_pin_sub),
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                when (val r = viewModel.verifyPin(pinInput)) {
                                    is PinVerifyResult.Success -> {
                                        pinInput = ""
                                        activeDialog = SecurityDialog.ChangePinNew
                                    }
                                    is PinVerifyResult.LockedOut -> {
                                        pinError = securityLockedMessage(appResources, r.until); pinInput = ""
                                    }
                                    is PinVerifyResult.ShouldWipe -> {
                                        pinInput = ""; resetDialogState(); viewModel.wipeWallet()
                                    }
                                    is PinVerifyResult.Wrong -> {
                                        pinError = incorrectPinMsg; pinInput = ""
                                    }
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
                    title = stringResource(R.string.sec_new_pin),
                    subtitle = stringResource(R.string.sec_new_pin_sub),
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
                    title = stringResource(R.string.sec_confirm_new_pin),
                    subtitle = stringResource(R.string.sec_confirm_new_pin_sub),
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
                                    snackMessage = pinChangedMsg
                                } else {
                                    pinError = pinsMismatchMsg
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
                            stringResource(R.string.sec_view_phrase),
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
                                stringResource(R.string.sec_view_phrase_body),
                                color = Color(0xFFB0BEC5),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { activeDialog = SecurityDialog.ViewSeedPinVerify }
                        ) {
                            Text(stringResource(R.string.sec_understand), color = Color(0xFFFFD700))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = ::resetDialogState) {
                            Text(stringResource(R.string.common_cancel), color = Color(0xFF8899AA))
                        }
                    }
                )
            }

            SecurityDialog.ViewSeedPinVerify -> {
                PinVerifyDialog(
                    title = stringResource(R.string.sec_verify_pin_title),
                    subtitle = stringResource(R.string.sec_verify_pin_view_sub),
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                when (val r = viewModel.verifyPin(pinInput)) {
                                    is PinVerifyResult.Success -> {
                                        pinInput = ""
                                        // Now require biometric
                                        scope.launch {
                                            activeDialog = SecurityDialog.None
                                            if (activity != null && biometricAvailable) {
                                                val result = biometricAuth.authenticate(
                                                    activity,
                                                    title = "DigiByte Wallet",
                                                    subtitle = authViewPhraseMsg
                                                )
                                                if (result is BiometricResult.Success) {
                                                    navController.navigate("settings_view_seed")
                                                } else {
                                                    snackMessage = bioRequiredMsg
                                                }
                                            } else if (!biometricAvailable) {
                                                // No biometric hardware — PIN alone suffices
                                                navController.navigate("settings_view_seed")
                                            }
                                        }
                                    }
                                    is PinVerifyResult.LockedOut -> {
                                        pinError = securityLockedMessage(appResources, r.until); pinInput = ""
                                    }
                                    is PinVerifyResult.ShouldWipe -> {
                                        pinInput = ""; resetDialogState(); viewModel.wipeWallet()
                                    }
                                    is PinVerifyResult.Wrong -> {
                                        pinError = incorrectPinMsg; pinInput = ""
                                    }
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
                    title = stringResource(R.string.sec_confirm_pin),
                    subtitle = stringResource(R.string.sec_confirm_pin_wipe_sub),
                    pinInput = pinInput,
                    pinError = pinError,
                    onDigit = { d ->
                        if (pinInput.length < SEC_PIN_LENGTH) {
                            pinInput += d
                            pinError = null
                            if (pinInput.length == SEC_PIN_LENGTH) {
                                when (val r = viewModel.verifyPin(pinInput)) {
                                    is PinVerifyResult.Success -> {
                                        pinInput = ""
                                        activeDialog = SecurityDialog.WipeConfirmDialog
                                    }
                                    is PinVerifyResult.LockedOut -> {
                                        pinError = securityLockedMessage(appResources, r.until); pinInput = ""
                                    }
                                    is PinVerifyResult.ShouldWipe -> {
                                        pinInput = ""; resetDialogState(); viewModel.wipeWallet()
                                    }
                                    is PinVerifyResult.Wrong -> {
                                        pinError = incorrectPinMsg; pinInput = ""
                                    }
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
                        Text(stringResource(R.string.sec_wipe_wallet), color = DigiByteRed, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            stringResource(R.string.sec_wipe_wallet_body),
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
                            Text(stringResource(R.string.sec_wipe_everything), color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = ::resetDialogState) {
                            Text(stringResource(R.string.common_cancel), color = Color(0xFF8899AA))
                        }
                    }
                )
            }

            SecurityDialog.WipeAfterNConfirm -> {
                var backupAcknowledged by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = ::resetDialogState,
                    containerColor = Color(0xFF1A2742),
                    title = {
                        Text(stringResource(R.string.sec_wipe_after, PinManager.WIPE_THRESHOLD), color = DigiByteRed, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.sec_wipe_after_body, PinManager.WIPE_THRESHOLD),
                                color = Color(0xFFB0BEC5),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { backupAcknowledged = !backupAcknowledged }
                            ) {
                                Checkbox(
                                    checked = backupAcknowledged,
                                    onCheckedChange = { backupAcknowledged = it },
                                    colors = CheckboxDefaults.colors(checkedColor = DigiByteRed)
                                )
                                Text(
                                    stringResource(R.string.sec_wipe_after_ack),
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = backupAcknowledged,
                            onClick = {
                                viewModel.setWipeAfterN(true)
                                resetDialogState()
                                snackMessage = wipeEnabledMsg
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DigiByteRed)
                        ) {
                            Text(stringResource(R.string.sec_enable), color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = ::resetDialogState) {
                            Text(stringResource(R.string.common_cancel), color = Color(0xFF8899AA))
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
                                                    contentDescription = stringResource(R.string.pin_backspace),
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
                Text(stringResource(R.string.common_cancel), color = Color(0xFF8899AA))
            }
        }
    )
}
