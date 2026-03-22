package io.digibyte.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.UserInfo
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteNavy
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet for tipping a hub user DGB.
 *
 * [target] is the user to tip (handle or address).
 * [onDismiss] is called after a successful tip or on explicit close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipBottomSheet(
    target: UserInfo,
    digiScopeClient: DigiScopeClient,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var amountText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    // Display name — prefer handle, otherwise truncate address
    val displayName = target.handle
        ?: target.address.let { addr ->
            if (addr.length > 16) "${addr.take(8)}…${addr.takeLast(6)}" else addr
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Send Tip",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "to $displayName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Amount input ──────────────────────────────────────────────
            OutlinedTextField(
                value = amountText,
                onValueChange = { value ->
                    // Allow only valid decimal input
                    if (value.matches(Regex("^\\d*\\.?\\d{0,8}$"))) {
                        amountText = value
                        resultMessage = null
                    }
                },
                label = { Text("Amount (DGB)") },
                placeholder = { Text("0.00") },
                suffix = { Text("DGB") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSending && !isSuccess
            )

            // ── Result message ────────────────────────────────────────────
            resultMessage?.let { msg ->
                Surface(
                    color = if (isSuccess)
                        DigiByteGreen.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) DigiByteGreen
                                else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // ── Send button ───────────────────────────────────────────────
            Button(
                onClick = {
                    val dgb = amountText.toDoubleOrNull()
                    if (dgb == null || dgb <= 0.0) {
                        resultMessage = "Enter a valid DGB amount"
                        isSuccess = false
                        return@Button
                    }
                    // Convert DGB to satoshis
                    val satoshis = (dgb * 100_000_000).toLong()
                    val toHandle = target.handle ?: target.address
                    isSending = true
                    resultMessage = null
                    scope.launch {
                        val success = digiScopeClient.sendTip(toHandle, satoshis)
                        isSending = false
                        if (success) {
                            isSuccess = true
                            resultMessage = "Tip sent successfully!"
                            // Auto-dismiss after a short delay
                            kotlinx.coroutines.delay(1_500)
                            onDismiss()
                        } else {
                            isSuccess = false
                            resultMessage = "Failed to send tip. You may need to log in or link a tip wallet."
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isSending && !isSuccess && amountText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Send Tip",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── Tip wallet notice ─────────────────────────────────────────
            if (!digiScopeClient.isLoggedIn()) {
                Text(
                    text = "Log in with Digi-ID to enable tipping",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
