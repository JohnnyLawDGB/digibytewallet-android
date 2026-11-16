package app.aroundtheblock.wallet.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.WalletSwitcher
import app.aroundtheblock.wallet.core.security.BiometricAuth
import app.aroundtheblock.wallet.core.wallets.AddResult
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent

/**
 * ADD mode's last step. Writes only the new wallet's record ([OnboardingViewModel.commitAdd]) and,
 * once it is added, restarts the app into it. No PIN is set or cleared here: the app PIN already
 * protects every wallet, and the running wallet's record is never written.
 *
 * The add starts once, on entry, and runs in the ViewModel's scope: leaving this screen while the
 * seed-key prompt is up does not orphan it, and a lock (which clears the onboarding flow) abandons
 * the reservation instead of leaving it behind.
 */
@Composable
fun AddWalletCommitScreen(
    navController: NavController,
    viewModel: OnboardingViewModel,
) {
    val context = LocalContext.current
    val biometricAuth = remember { BiometricAuth() }
    val promptTitle = stringResource(R.string.app_name_display)
    val promptSubtitle = stringResource(R.string.ksb_prompt_subtitle)
    val result by viewModel.addResult.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (viewModel.addResult.value == null) {
            viewModel.commitAdd(
                seedKeyPromptFor(
                    biometricAuth,
                    context as? androidx.fragment.app.FragmentActivity,
                    promptTitle, promptSubtitle,
                ),
            ) { }
        }
    }

    // Leaving the add: back to where it was started from. The phrase is already wiped by now, so
    // the onboarding screens behind this one have nothing left to continue with.
    val leave: () -> Unit = {
        if (!navController.popBackStack(ONBOARDING_ROUTE, inclusive = true)) navController.navigateUp()
    }

    LaunchedEffect(result) {
        val added = result as? AddResult.Added ?: return@LaunchedEffect
        // Restarts into the new wallet (WalletSwitcher compares with the wallet this process runs).
        // Anything else leaves the wallet added and active for the next launch.
        if (viewModel.switchTo(added.entry.id) != WalletSwitcher.Result.RESTARTING) leave()
    }

    // No way back while the add runs; afterwards back leaves the add flow.
    BackHandler(enabled = true) { if (result != null && result !is AddResult.Added) leave() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (val r = result) {
                null, is AddResult.Added -> {
                    CircularProgressIndicator(color = DigiByteAccent)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.wallet_add_saving), color = Color(0xFFB0BEC5), textAlign = TextAlign.Center)
                }
                is AddResult.Duplicate -> {
                    val shown = addFlowWalletName(r.existing)
                    Text(stringResource(R.string.wallet_add_duplicate, shown), color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { if (viewModel.switchTo(r.existing.id) != WalletSwitcher.Result.RESTARTING) leave() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(stringResource(R.string.wallet_add_duplicate_switch, shown)) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = leave, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.common_back))
                    }
                }
                AddResult.CapReached, AddResult.Failed -> {
                    Text(
                        text = if (r == AddResult.CapReached) {
                            stringResource(R.string.wallet_add_cap, WalletRegistry.MAX_PER_NETWORK)
                        } else {
                            stringResource(R.string.wallet_add_failed)
                        },
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = leave, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.common_back))
                    }
                }
            }
        }
    }
}

/** The name the add flow shows for [entry]: its own, or the default "Wallet <number>". */
@Composable
internal fun addFlowWalletName(entry: app.aroundtheblock.wallet.core.wallets.WalletEntry): String {
    val default = stringResource(R.string.wallet_default_name, entry.number)
    return app.aroundtheblock.wallet.ui.wallets.shownName(entry) { default }
}
