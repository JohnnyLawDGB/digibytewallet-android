package app.aroundtheblock.wallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteBlue
import app.aroundtheblock.wallet.ui.wallet.WalletViewModel

/**
 * The optional quick start of a wallet being added by restore, between the birth date and the
 * commit step. Skipping is the default and one tap, and sends nothing: the wallet's compact-filter
 * sync then finds its history privately on the phone.
 *
 * Opting in sends this wallet's addresses to the server the user picks: their own node when one is
 * set, or digiscope.me after a confirm step that says the server can link this wallet to their
 * other wallets. What comes back is shown as "not yet verified" and is never spendable or part of
 * the balance (see [app.aroundtheblock.wallet.core.wallets.QuickStart]).
 */
@Composable
fun QuickStartScreen(
    navController: NavController,
    viewModel: OnboardingViewModel,
) {
    val ui by viewModel.quickStart.collectAsStateWithLifecycle()
    var offer by remember { mutableStateOf<QuickStartOffer?>(null) }
    LaunchedEffect(Unit) { offer = viewModel.quickStartOffer() }
    // The DigiScope confirm step: only its own button sends anything.
    var confirmDigiScope by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, tint = DigiByteAccent, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.quickstart_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            when (val state = ui) {
                QuickStartUi.Idle -> if (!confirmDigiScope) {
                    Body(stringResource(R.string.quickstart_body))
                    Spacer(Modifier.height(24.dp))
                    // The default: first, primary, and nothing is sent.
                    Button(
                        onClick = { navController.navigate(ADD_COMMIT_ROUTE) { launchSingleTop = true } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
                    ) { Text(stringResource(R.string.quickstart_skip), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

                    val choices = offer?.choices.orEmpty()
                    val host = offer?.ownNodeHost
                    if (QuickStartSource.OWN_NODE in choices && host != null) {
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = { viewModel.runQuickStart(QuickStartSource.OWN_NODE) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(stringResource(R.string.quickstart_own_node, host), textAlign = TextAlign.Center) }
                        Spacer(Modifier.height(8.dp))
                        Note(stringResource(R.string.quickstart_own_node_note))
                    }
                    if (QuickStartSource.DIGISCOPE in choices) {
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = { confirmDigiScope = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(stringResource(R.string.quickstart_digiscope), textAlign = TextAlign.Center) }
                        Spacer(Modifier.height(8.dp))
                        Note(stringResource(R.string.quickstart_digiscope_disclosure))
                    }
                } else {
                    // The confirm step: the disclosure again, on its own, before anything is sent.
                    Body(stringResource(R.string.quickstart_digiscope_disclosure))
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { confirmDigiScope = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(stringResource(R.string.common_back)) }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.runQuickStart(QuickStartSource.DIGISCOPE) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
                    ) { Text(stringResource(R.string.quickstart_digiscope_confirm), textAlign = TextAlign.Center) }
                }

                QuickStartUi.Running -> {
                    CircularProgressIndicator(color = DigiByteAccent)
                    Spacer(Modifier.height(16.dp))
                    Body(stringResource(R.string.quickstart_running))
                    Spacer(Modifier.height(24.dp))
                    // Not a dead end: one tap stops the lookup and goes on to the commit step.
                    OutlinedButton(
                        onClick = {
                            viewModel.cancelQuickStart()
                            navController.navigate(ADD_COMMIT_ROUTE) { launchSingleTop = true }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(stringResource(R.string.quickstart_running_skip)) }
                }

                is QuickStartUi.Found -> {
                    val q = state.quickStart
                    Body(stringResource(R.string.quickstart_found, WalletViewModel.formatSatoshisBare(q.unverifiedSat), q.sourceLabel))
                    Spacer(Modifier.height(8.dp))
                    // Null is a DigiDollar lookup that was not made or not finished: said, never shown as zero.
                    Body(
                        q.unverifiedDdCents?.let { stringResource(R.string.quickstart_found_dd, WalletViewModel.formatDigiDollar(it)) }
                            ?: stringResource(R.string.quickstart_dd_unchecked),
                    )
                    Spacer(Modifier.height(8.dp))
                    Note(stringResource(R.string.quickstart_unverified_sub))
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { navController.navigate(ADD_COMMIT_ROUTE) { launchSingleTop = true } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
                    ) { Text(stringResource(R.string.quickstart_continue)) }
                }

                QuickStartUi.Failed, QuickStartUi.Incomplete -> {
                    Body(stringResource(if (state == QuickStartUi.Incomplete) R.string.quickstart_incomplete else R.string.quickstart_failed))
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { navController.navigate(ADD_COMMIT_ROUTE) { launchSingleTop = true } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue),
                    ) { Text(stringResource(R.string.quickstart_continue)) }
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0BEC5), textAlign = TextAlign.Center, lineHeight = 20.sp)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A4AE), textAlign = TextAlign.Center)
}
