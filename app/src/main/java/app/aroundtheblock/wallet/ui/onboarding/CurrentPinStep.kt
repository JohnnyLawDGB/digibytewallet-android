package app.aroundtheblock.wallet.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.ui.components.rememberSpendAuth
import app.aroundtheblock.wallet.ui.navigation.PIN_STORE_UNAVAILABLE_ROUTE
import kotlinx.coroutines.launch

/**
 * The current-PIN step of a first run (INT-2026-11-19-B: no onboarding path sets or replaces the
 * app PIN while any wallet record exists). A first run that starts while a wallet and its PIN are
 * stored keeps that PIN and asks for it: on its first screen, before a phrase is shown or typed,
 * and again at the write if the app has left the foreground in between.
 *
 * Returns the step: called with what to do next, it runs that at once when no PIN is owed
 * ([OnboardingViewModel.currentPinOwed]), and otherwise only after the app's one PIN dialog has
 * accepted the current PIN (a wrong PIN there counts, cools down and wipes as anywhere else).
 * A PIN that is owed but cannot be asked for (the PIN store cannot be read) opens the screen that
 * says so; [onDeclined] is called whenever the step did not go on.
 */
@Composable
internal fun rememberCurrentPinStep(
    viewModel: OnboardingViewModel,
    navController: NavController,
    onDeclined: () -> Unit = {},
): (() -> Unit) -> Unit {
    val spendAuth = rememberSpendAuth()
    spendAuth.Dialogs()
    val scope = rememberCoroutineScope()
    val subtitle = stringResource(R.string.sec_verify_pin_sub)
    return { go ->
        if (viewModel.currentPinOwed()) {
            scope.launch {
                if (spendAuth.authorizeWithPin(subtitle)) {
                    viewModel.onCurrentPinVerified()
                    go()
                } else {
                    if (!spendAuth.pinCanBeAsked()) {
                        navController.navigate(PIN_STORE_UNAVAILABLE_ROUTE) { launchSingleTop = true }
                    }
                    onDeclined()
                }
            }
        } else {
            go()
        }
    }
}
