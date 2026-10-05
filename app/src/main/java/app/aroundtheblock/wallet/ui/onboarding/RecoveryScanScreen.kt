package app.aroundtheblock.wallet.ui.onboarding

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.recovery.DerivationProfile
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteBlue
import java.text.NumberFormat
import java.util.Locale

/**
 * Restore privately, with an optional check of other wallet apps' formats. Sits between phrase
 * entry and the wallet-birth date.
 *
 * NOTHING IS SENT BY DEFAULT. The restored wallet finds this app's own address formats — native
 * (dgb1q…), legacy (D…), Taproot, DigiDollar — with compact-filter sync, on the phone. Funds that
 * another app put on a different derivation path (BIP44 Coinomi/Ledger/Trezor, BIP49, old
 * BreadWallet forks) are invisible to that sync, and finding them means asking a server: the scan
 * derives every profile in [app.aroundtheblock.wallet.core.recovery.DerivationProfile.BUILT_INS]
 * and sends those addresses to the reconcile backend. So it runs only when the user asks, after
 * being told exactly that (owner decision 2026-10-05). The same scan stays available later in
 * Settings → Recover funds.
 */
@Composable
fun RecoveryScanScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val scanState by viewModel.scanResults.collectAsStateWithLifecycle()
    val verdict by viewModel.passphraseVerdict.collectAsStateWithLifecycle()
    val onContinue = {
        navController.navigate("recovery_date") {
            popUpTo("recovery_scan") { inclusive = true }
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
                stringResource(R.string.restore_scan_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.restore_scan_body),
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            when (val s = scanState) {
                is RecoveryScanService.State.Idle ->
                    OtherFormatsOffer(onScan = { viewModel.runRecoveryScan() })

                is RecoveryScanService.State.Scanning ->
                    ScanningBody(stage = s.stage)

                is RecoveryScanService.State.Failed ->
                    FailedBody(s.reason) { viewModel.runRecoveryScan() }

                is RecoveryScanService.State.Done ->
                    DoneBody(state = s, verdict = verdict)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onContinue,
                enabled = scanState !is RecoveryScanService.State.Scanning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
            ) {
                Text(stringResource(R.string.recover_continue), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

/** The opt-in, and the honest description of what it sends. */
@Composable
private fun OtherFormatsOffer(onScan: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2742), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.restore_other_title),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.restore_other_body),
            color = Color(0xFFB0BEC5),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.restore_other_check), color = DigiByteAccent) }
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
                stage ?: stringResource(R.string.restore_scan_deriving),
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.restore_scan_duration),
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
                Text(stringResource(R.string.restore_scan_failed), color = Color(0xFFFF8A80), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(reason, color = Color(0xFFB0BEC5), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.restore_scan_retry), color = DigiByteAccent) }
    }
}

@Composable
private fun DoneBody(
    state: RecoveryScanService.State.Done,
    verdict: app.aroundtheblock.wallet.core.recovery.PassphraseScanVerdict.Outcome?,
) {
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
                        state.totalBalanceSat > 0 -> stringResource(R.string.restore_found_title)
                        state.allBackendUnreachable -> stringResource(R.string.restore_unreachable_title)
                        else -> stringResource(R.string.restore_none_title)
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
                        state.totalBalanceSat > 0 -> {
                            val pathsWithFunds = state.results.count { it.totalSat > 0 }
                            pluralStringResource(
                                R.plurals.restore_found_body,
                                pathsWithFunds,
                                fmt.format(totalDgb),
                                pathsWithFunds,
                            )
                        }
                        state.allBackendUnreachable ->
                            stringResource(R.string.restore_unreachable_body)
                        else ->
                            stringResource(R.string.restore_none_body)
                    },
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                )
                if (state.nonNativeWithFunds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.restore_nonnative_note, fmt.format(nonNativeDgb)),
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

        if (verdict == app.aroundtheblock.wallet.core.recovery.PassphraseScanVerdict.Outcome.LIKELY_TYPO) {
            Text(
                stringResource(R.string.restore_likely_typo),
                color = Color(0xFFFFCC66),
                fontSize = 13.sp,
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
                    pluralStringResource(
                        R.plurals.restore_addresses_scanned,
                        result.addresses.size,
                        result.profile.pathString(),
                        result.addresses.size,
                    ),
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
