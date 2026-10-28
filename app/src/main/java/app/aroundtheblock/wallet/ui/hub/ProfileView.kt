package app.aroundtheblock.wallet.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
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
import app.aroundtheblock.wallet.core.digiscope.DigiScopeProfile
import app.aroundtheblock.wallet.ui.components.rememberSpendAuth
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteBlue
import app.aroundtheblock.wallet.ui.theme.DigiByteGreen
import app.aroundtheblock.wallet.ui.util.openExternalUrl
import app.aroundtheblock.wallet.ui.components.keyboardPadding


// ── Hub account controls, shown inside the Chat tab ─────────────────────

/**
 * What the Chat tab shows about the Hub account: a Digi-ID sign-in when signed out, a prompt to
 * choose a handle once signed in without one, and nothing once a handle exists (the chat is then
 * the whole content). This replaced the separate Profile tab when the Hub was cut to Chat and
 * DigiRunner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubAccountStrip(viewModel: ProfileViewModel) {
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
    var showHandleSheet by remember { mutableStateOf(false) }

    when (val state = profileState) {
        is ProfileState.NotLoggedIn -> AccountPromptCard(
            text = stringResource(R.string.hub_signin_prompt),
            action = stringResource(R.string.hub_signin_button),
            onAction = {
                scope.launch {
                    // Denied: nothing happens — the sign-in card stays as it was.
                    if (spendAuth.authorize(context as? androidx.fragment.app.FragmentActivity, authTitle, authSubtitle)) {
                        viewModel.quickLogin()
                    }
                }
            }
        )

        is ProfileState.NoHandle -> {
            AccountPromptCard(
                text = stringResource(R.string.hub_handle_prompt),
                action = stringResource(R.string.hub_handle_button),
                onAction = { showHandleSheet = true }
            )
            if (showHandleSheet) {
                ModalBottomSheet(onDismissRequest = { showHandleSheet = false }) {
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
            }
        }

        // Signed in with a handle: the chat is the content. Loading or a failed profile load
        // shows nothing here; the tab reloads the profile each time it opens.
        else -> Unit
    }
}

@Composable
private fun AccountPromptCard(text: String, action: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(action, color = Color.White)
            }
        }
    }
}

/**
 * The chat header's account menu, shown only while a Hub session exists: log out, and the
 * in-app path to delete the DigiScope account that Google Play requires where an account can be
 * created. Logging out ends the session on the server and clears the cached chat
 * ([ProfileViewModel.logout]).
 */
@Composable
fun HubAccountMenu(onLogout: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.hub_menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.hub_menu_logout)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = { expanded = false; confirmLogout = true }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.hub_menu_delete_account)) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                onClick = { expanded = false; openExternalUrl(context, DELETE_ACCOUNT_URL) }
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.hub_logout_title)) },
            text = { Text(stringResource(R.string.hub_logout_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = { confirmLogout = false; onLogout() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.hub_menu_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.common_cancel)) }
            }
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
            .keyboardPadding()
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

/** The privacy policy's account-deletion section — also the link registered with Play. */
private const val DELETE_ACCOUNT_URL = "https://digiscope.me/wallet/privacy-policy#delete-account"
