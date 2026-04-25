package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.core.recovery.DerivationProfile
import io.digibyte.core.recovery.RecoveryScanService
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import java.text.NumberFormat
import java.util.Locale

/**
 * Universal Restore scan — runs between mnemonic entry and the date picker.
 *
 * Probes every [DerivationProfile] in [DerivationProfile.BUILT_INS] against
 * the user-entered mnemonic. If funds appear on any non-native profile, the
 * UI explicitly calls them out so the user knows there's a sweep to do.
 * Otherwise it's a fast pass-through to the normal date-picker flow.
 */
@Composable
fun RecoveryScanScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val scanState by viewModel.scanResults.collectAsStateWithLifecycle()

    // Kick the scan once on first composition. Rerunning is a no-op (service
    // state will re-emit) — the passphrase picker is a follow-up we can add
    // later if users ask for it.
    LaunchedEffect(Unit) {
        if (scanState is RecoveryScanService.State.Idle) {
            viewModel.runRecoveryScan(passphrase = null)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.TravelExplore,
                contentDescription = null,
                tint = DigiByteAccent,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Scanning for funds",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Checking every derivation path DigiByte wallets have used",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))

            when (val s = scanState) {
                is RecoveryScanService.State.Idle,
                is RecoveryScanService.State.Scanning ->
                    ScanningBody(stage = (s as? RecoveryScanService.State.Scanning)?.stage)

                is RecoveryScanService.State.Failed ->
                    FailedBody(s.reason) {
                        viewModel.runRecoveryScan(passphrase = null)
                    }

                is RecoveryScanService.State.Done -> {
                    val onContinue = {
                        navController.navigate("recovery_date") {
                            popUpTo("recovery_scan") { inclusive = true }
                        }
                    }
                    // Auto-advance when there's nothing to show. The Done body
                    // is informative when funds were found OR when the backend
                    // was unreachable (so the user knows SPV is the fallback);
                    // for a clean empty result it's pure friction.
                    val shouldAutoAdvance =
                        s.totalBalanceSat == 0L && !s.allBackendUnreachable
                    if (shouldAutoAdvance) {
                        LaunchedEffect(Unit) { onContinue() }
                    } else {
                        DoneBody(state = s, onContinue = onContinue)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningBody(stage: String?) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2742), RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CircularProgressIndicator(color = DigiByteAccent)
            Spacer(Modifier.height(12.dp))
            Text(
                stage ?: "Deriving addresses…",
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This can take 30-90 seconds depending on the node. It will " +
                "find funds on any derivation path DigiByte wallets use.",
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FailedBody(reason: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0x33FF5252), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Column {
                Text("Scan failed", color = Color(0xFFFF8A80), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(reason, color = Color(0xFFB0BEC5), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
        ) { Text("Retry scan") }
    }
}

@Composable
private fun DoneBody(state: RecoveryScanService.State.Done, onContinue: () -> Unit) {
    val fmt = remember {
        NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 8
        }
    }

    // Headline — total + non-native call-out
    val totalDgb = state.totalBalanceSat / 100_000_000.0
    val nonNativeTotalSat = state.nonNativeWithFunds.sumOf { it.totalSat }
    val nonNativeDgb = nonNativeTotalSat / 100_000_000.0

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    if (state.totalBalanceSat > 0) Color(0x3348B76D) else Color(0xFF1A2742),
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Column {
                Text(
                    when {
                        state.totalBalanceSat > 0 -> "Funds found"
                        state.allBackendUnreachable -> "Couldn't reach reconcile node"
                        else -> "No funds detected yet"
                    },
                    color = when {
                        state.totalBalanceSat > 0 -> Color(0xFF6BE8A3)
                        state.allBackendUnreachable -> Color(0xFFFFCC66)
                        else -> Color.White
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        state.totalBalanceSat > 0 ->
                            "${fmt.format(totalDgb)} DGB across ${state.results.count { it.totalSat > 0 }} path(s)"
                        state.allBackendUnreachable ->
                            "SPV will discover any funds on this seed during sync — the fast-path scan was unavailable. You can continue safely."
                        else ->
                            "SPV sync will keep looking after you continue — this scan only queries currently-unspent outputs."
                    },
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                )
                if (state.nonNativeWithFunds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${fmt.format(nonNativeDgb)} DGB is on a non-native path and " +
                        "will be swept to your new wallet automatically after recovery.",
                        color = Color(0xFFFFCC66),
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Per-profile breakdown
        state.results.forEach { r ->
            ProfileCard(r, fmt)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
        ) {
            Text(
                "Continue to recovery",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun ProfileCard(
    result: RecoveryScanService.ProfileResult,
    fmt: NumberFormat,
) {
    val hasFunds = result.totalSat > 0
    val dgb = result.totalSat / 100_000_000.0
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2742), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // CheckCircle when funds exist; FiberManualRecord (filled dot)
            // when empty so the row reads as informational rather than as
            // a radio button waiting for selection.
            Icon(
                if (hasFunds) Icons.Default.CheckCircle else Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = if (hasFunds) DigiByteAccent else Color(0xFF546E7A),
                modifier = Modifier.size(if (hasFunds) 20.dp else 10.dp)
                    .padding(top = if (hasFunds) 0.dp else 5.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    result.profile.label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    result.profile.description,
                    color = Color(0xFFB0BEC5),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${result.profile.pathString()} · ${result.addresses.size} addresses scanned",
                    color = Color(0xFF546E7A),
                    fontSize = 11.sp,
                )
            }
            if (hasFunds) {
                Text(
                    "${fmt.format(dgb)} DGB",
                    color = DigiByteAccent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
