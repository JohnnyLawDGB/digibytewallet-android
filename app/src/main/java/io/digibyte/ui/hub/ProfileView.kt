package io.digibyte.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.digiscope.DigiScopeProfile
import io.digibyte.ui.components.rememberSpendAuth
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.digibyte.R
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel()
) {
    // Refresh profile every time this tab becomes visible
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadProfile()
    }

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val handleInput by viewModel.handleInput.collectAsStateWithLifecycle()
    val handleAvailable by viewModel.handleAvailable.collectAsStateWithLifecycle()
    val isCheckingHandle by viewModel.isCheckingHandle.collectAsStateWithLifecycle()
    val isRegistering by viewModel.isRegistering.collectAsStateWithLifecycle()
    val registrationError by viewModel.registrationError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spendAuth = rememberSpendAuth()
    spendAuth.Dialogs()
    // Quick-login signs a Digi-ID challenge with the wallet key: the same identity
    // action the in-app Digi-ID approve gates, so it carries the same credential check.
    val authTitle = stringResource(R.string.did_auth_title)
    val authSubtitle = stringResource(R.string.did_auth_subtitle, "DigiScope")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = profileState) {
            is ProfileState.Loading -> {
                CircularProgressIndicator(
                    color = DigiByteBlue,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is ProfileState.NotLoggedIn -> {
                NotLoggedInContent(onQuickLogin = {
                    scope.launch {
                        // Denied: nothing happens — the login card stays as it was.
                        if (spendAuth.authorize(context as? androidx.fragment.app.FragmentActivity, authTitle, authSubtitle)) {
                            viewModel.quickLogin()
                        }
                    }
                })
            }

            is ProfileState.NoHandle -> {
                RegisterHandleContent(
                    address = state.address,
                    handleInput = handleInput,
                    handleAvailable = handleAvailable,
                    isCheckingHandle = isCheckingHandle,
                    isRegistering = isRegistering,
                    registrationError = registrationError,
                    onHandleChange = { viewModel.onHandleInputChange(it) },
                    onCheckAvailability = { viewModel.checkAvailability() },
                    onRegister = { viewModel.registerHandle() },
                    onClearError = { viewModel.clearRegistrationError() }
                )
            }

            is ProfileState.HasProfile -> {
                ProfileContent(
                    profile = state.profile,
                    onLogout = { viewModel.logout() }
                )
            }

            is ProfileState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.loadProfile() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

// ── Not logged in ──────────────────────────────────────────────────────────

@Composable
private fun NotLoggedInContent(onQuickLogin: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(DigiByteBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = DigiByteBlue,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Community Profile",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Log in with Digi-ID to create your community profile, register a handle, and participate in the forum.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeatureRow(Icons.Default.Tag, "Choose a pseudonymous handle")
                FeatureRow(Icons.Default.Forum, "Post and reply in the forum")
                FeatureRow(Icons.Default.Paid, "Send and receive DGB tips")
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onQuickLogin,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
        ) {
            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Connect to DigiScope", color = Color.White)
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = DigiByteAccent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Register handle ────────────────────────────────────────────────────────

@Composable
private fun RegisterHandleContent(
    address: String,
    handleInput: String,
    handleAvailable: Boolean?,
    isCheckingHandle: Boolean,
    isRegistering: Boolean,
    registrationError: String?,
    onHandleChange: (String) -> Unit,
    onCheckAvailability: () -> Unit,
    onRegister: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Choose Your Handle",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Your pseudonymous community identity. 3-20 characters, alphanumeric and underscore only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Connected address chip
        Surface(
            color = DigiByteBlue.copy(alpha = 0.12f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = DigiByteAccent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (address.length > 20) "${address.take(10)}…${address.takeLast(8)}"
                           else address,
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteAccent,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Handle input + availability indicator
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = handleInput,
                onValueChange = onHandleChange,
                label = { Text("Handle") },
                placeholder = { Text("e.g. DigiByte_Fan") },
                leadingIcon = {
                    Text(
                        "@",
                        color = DigiByteAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                },
                trailingIcon = {
                    when {
                        isCheckingHandle -> CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = DigiByteBlue
                        )
                        handleAvailable == true -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Available",
                            tint = DigiByteGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        handleAvailable == false -> Icon(
                            Icons.Default.Cancel,
                            contentDescription = "Taken",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        else -> null
                    }
                },
                singleLine = true,
                isError = registrationError != null && handleAvailable == false,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = when (handleAvailable) {
                        true -> DigiByteGreen
                        false -> MaterialTheme.colorScheme.error
                        null -> DigiByteBlue
                    },
                    unfocusedBorderColor = when (handleAvailable) {
                        true -> DigiByteGreen.copy(alpha = 0.6f)
                        false -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            )

            // Availability status text
            when (handleAvailable) {
                true -> Text(
                    "@$handleInput is available!",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteGreen
                )
                false -> {} // Error message shown below
                null -> if (handleInput.length in 3..20) {
                    Text(
                        "Tap \"Check\" to verify availability",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Error message
        registrationError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Check availability
            OutlinedButton(
                onClick = onCheckAvailability,
                enabled = handleInput.length >= 3 && !isCheckingHandle && !isRegistering,
                modifier = Modifier.weight(1f)
            ) {
                Text("Check")
            }

            // Register
            Button(
                onClick = onRegister,
                enabled = handleAvailable == true && !isRegistering,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Register", color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Profile display (has handle) ──────────────────────────────────────────

@Composable
private fun ProfileContent(
    profile: DigiScopeProfile,
    onLogout: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // Avatar
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(DigiByteBlue.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.handle?.take(1)?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineLarge,
                color = DigiByteAccent,
                fontWeight = FontWeight.Bold
            )
        }

        // Handle
        Text(
            text = "@${profile.handle}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // DGB address
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                val addr = profile.address
                Text(
                    text = if (addr.length > 24) "${addr.take(12)}…${addr.takeLast(10)}" else addr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tip balance card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DigiByteGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Paid,
                            contentDescription = null,
                            tint = DigiByteGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Tip Balance",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${String.format("%.8f", profile.tipBalance / 100_000_000.0)} DGB",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = DigiByteGreen
                        )
                    }
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Unlink / logout
        OutlinedButton(
            onClick = { showLogoutDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                width = 1.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Unlink DigiScope Account")
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = {
                Icon(
                    Icons.Default.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Unlink Account?") },
            text = {
                Text(
                    "This will remove your DigiScope session from this device. " +
                    "Your handle and profile remain on the server. You can log back in with Digi-ID.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Unlink")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
