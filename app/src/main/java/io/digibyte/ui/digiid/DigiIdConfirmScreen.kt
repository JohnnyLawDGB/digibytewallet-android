@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.digiid

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.core.model.DigiIdResult
import io.digibyte.ui.components.rememberSpendAuth
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteNavy
import io.digibyte.ui.theme.DigiByteRed
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import io.digibyte.R

/**
 * Shown after scanning a digiid:// QR code.
 *
 * Layout:
 *  - Domain name prominently displayed
 *  - Warning banner if the callback is http (isUnsecure)
 *  - Callback URL for transparency
 *  - Approve / Deny buttons
 *  - Inline result screen (success checkmark or error message)
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DigiIdConfirmScreen(
    request: DigiIdRequest,
    onNavigateBack: () -> Unit,
    viewModel: DigiIdViewModel = hiltViewModel()
) {
    // Hoisted: used inside the auth coroutine lambda, which is not composable.
    val bioTitle = stringResource(R.string.did_auth_title)
    val bioSubtitleFmt = stringResource(R.string.did_auth_subtitle)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val spendAuth = rememberSpendAuth()
    spendAuth.Dialogs()

    val authResult by viewModel.authResult.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    // When we get a result, hand off to the result sub-screen
    val result = authResult

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.did_login)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearResult()
                        onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DigiByteNavy,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = result,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "DigiIdResult"
            ) { currentResult ->
                when (currentResult) {
                    null -> {
                        // Confirmation screen
                        ConfirmContent(
                            request = request,
                            isLoading = isLoading,
                            onApprove = {
                                coroutineScope.launch {
                                    val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
                                    // Denied: stay on the confirm screen — Deny remains the
                                    // explicit way out, and nothing has been signed.
                                    if (spendAuth.authorize(fragmentActivity, bioTitle, bioSubtitleFmt.format(request.domain))) {
                                        viewModel.authenticate(request)
                                    }
                                }
                            },
                            onDeny = {
                                viewModel.clearResult()
                                onNavigateBack()
                            }
                        )
                    }
                    is DigiIdResult.Success -> {
                        ResultContent(
                            success = true,
                            domain = currentResult.domain,
                            message = stringResource(R.string.did_logged_in, currentResult.domain),
                            onDone = {
                                viewModel.clearResult()
                                onNavigateBack()
                            }
                        )
                    }
                    is DigiIdResult.Error -> {
                        ResultContent(
                            success = false,
                            domain = request.domain,
                            message = currentResult.message,
                            onDone = {
                                viewModel.clearResult()
                                // Stay on screen so user can try again or deny
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    request: DigiIdRequest,
    isLoading: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Fingerprint icon
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            tint = DigiByteAccent,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Domain name — the most important info
        Text(
            text = request.domain,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.did_requesting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // HTTP warning banner
        if (request.isUnsecure) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DigiByteRed.copy(alpha = 0.15f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(R.string.did_warning),
                        tint = DigiByteRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.did_insecure),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = DigiByteRed
                        )
                        Text(
                            text = stringResource(R.string.did_insecure_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = DigiByteRed.copy(alpha = 0.85f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Callback URL — full transparency
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.did_callback_url),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = request.callbackUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Action buttons
        if (isLoading) {
            CircularProgressIndicator(color = DigiByteAccent)
        } else {
            Button(
                onClick = onApprove,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteAccent)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.did_approve),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.did_deny),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ResultContent(
    success: Boolean,
    domain: String,
    message: String,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = stringResource(if (success) R.string.send_success else R.string.rf_something_wrong),
            tint = if (success) DigiByteGreen else DigiByteRed,
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(if (success) R.string.did_success else R.string.did_failed),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (success) DigiByteGreen else DigiByteRed,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (success) DigiByteGreen else DigiByteAccent
            )
        ) {
            Text(
                text = stringResource(if (success) R.string.common_done else R.string.did_try_again),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}
