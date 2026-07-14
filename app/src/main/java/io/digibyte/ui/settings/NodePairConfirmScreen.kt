@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Warning
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
import io.digibyte.core.isTestnet
import io.digibyte.core.settings.CustomNode
import io.digibyte.core.settings.OwnNodeUri
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteNavy
import io.digibyte.ui.theme.DigiByteRed

/**
 * Shown after scanning a `dgbnode://` QR code (or arriving via a manual pairing
 * link). Confirms the parsed host:port + label before pairing it as the pinned
 * own node — mirrors [io.digibyte.ui.digiid.DigiIdConfirmScreen]'s scaffold.
 *
 * [OwnNodeUri.net] is caller-declared metadata, not verified on-wire, so a
 * mismatch against the wallet's current network is only a soft caution —
 * pairing still proceeds (see [SettingsViewModel.pairFromUri]).
 */
@Composable
fun NodePairConfirmScreen(
    rawUri: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val testnet = remember { isTestnet(context) }
    val defaultPort = remember {
        if (testnet) CustomNode.TESTNET_DEFAULT_PORT else CustomNode.MAINNET_DEFAULT_PORT
    }
    val parsed = remember(rawUri) { OwnNodeUri.parse(rawUri, defaultPort) }
    val netMismatch = remember(parsed, testnet) {
        parsed != null && parsed.net != null && (parsed.net == "testnet") != testnet
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Own Node") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel"
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                tint = DigiByteAccent,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (parsed != null) {
                Text(
                    text = parsed.node.asHostPort(),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (parsed.label != null)
                        "\"${parsed.label}\" wants to be your own node"
                    else
                        "wants to be your own node",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "Unrecognized pairing code",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = DigiByteRed,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (netMismatch) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFAA00).copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFFFAA00),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Network mismatch",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFFFFCC66)
                            )
                            Text(
                                text = "This node advertises ${parsed?.net}, but the wallet is " +
                                    "currently on ${if (testnet) "testnet" else "mainnet"}. " +
                                    "Pairing will still proceed, but the node may not sync correctly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFCC66).copy(alpha = 0.9f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            errorMessage?.let { message ->
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
                            contentDescription = "Error",
                            tint = DigiByteRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = DigiByteRed.copy(alpha = 0.9f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (pairing) {
                CircularProgressIndicator(color = DigiByteAccent)
            } else {
                Button(
                    onClick = {
                        pairing = true
                        when (viewModel.pairFromUri(rawUri)) {
                            SettingsViewModel.PairResult.OK,
                            SettingsViewModel.PairResult.NET_MISMATCH -> {
                                viewModel.applyOwnNodeNow()
                                onDone()
                            }
                            SettingsViewModel.PairResult.INVALID -> {
                                errorMessage = "This QR code isn't a valid node pairing code."
                                pairing = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DigiByteAccent)
                ) {
                    Text(
                        text = "Pair",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onCancel,
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
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
