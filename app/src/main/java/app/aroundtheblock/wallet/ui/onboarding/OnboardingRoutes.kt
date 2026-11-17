package app.aroundtheblock.wallet.ui.onboarding

/**
 * Which flow the onboarding screens run. FIRST_RUN sets the app up (wallet, then the app PIN);
 * ADD adds a wallet beside the ones the app holds and never sets or clears the PIN.
 */
enum class OnboardingMode {
    FIRST_RUN, ADD;

    companion object {
        /** The route argument; only "add" opens the add flow, anything else is the first run. */
        fun of(arg: String?): OnboardingMode = if (arg == ADD_ARG) ADD else FIRST_RUN
    }
}

private const val FIRST_RUN_ARG = "first_run"
private const val ADD_ARG = "add"

/** The onboarding destination. Back-stack lookups and `popUpTo` use this pattern. */
const val ONBOARDING_ROUTE = "onboarding?mode={mode}"

/** The onboarding route to navigate to. */
fun onboardingRoute(mode: OnboardingMode): String =
    "onboarding?mode=" + if (mode == OnboardingMode.ADD) ADD_ARG else FIRST_RUN_ARG

/** The navigation argument [ONBOARDING_ROUTE] carries, and its default. */
const val ONBOARDING_MODE_ARG = "mode"
const val ONBOARDING_MODE_DEFAULT = FIRST_RUN_ARG

/** ADD mode's last step: the new wallet's record is written, then the app restarts into it. */
const val ADD_COMMIT_ROUTE = "add_wallet_commit"

/** The restore's optional quick start (Task 15). */
const val QUICK_START_ROUTE = "restore_quick_start"
