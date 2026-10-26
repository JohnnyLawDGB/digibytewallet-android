package app.aroundtheblock.wallet

/**
 * Saved state from before a process death never reopens the app behind its lock (LOCK-PD).
 *
 * Android kills a backgrounded app to reclaim memory and, when the user returns, recreates the
 * Activity with the state it saved: the navigation back stack and every `rememberSaveable`.
 * The wallet itself comes back Locked, but the restored back stack put the user straight onto
 * the screen they had left — the wallet, Settings, even the recovery phrase — with no PIN, and
 * the reactive lock in AppNavigation never fired because Locked was not a change (reproduced on
 * 4.1.1, 2026-10-24).
 *
 * So every saved state carries the token of the process that wrote it, and an Activity restores
 * its state only when that token is this process's: a recreation within the same process
 * (rotation, a configuration or theme change) restores as before, while state written by a
 * process that has since died is dropped and the app starts from the start destination it
 * computes — the unlock screen for a stored wallet. The decision is per Activity instance, so a
 * task holding several MainActivity instances is covered too.
 */
object ProcessRestorePolicy {
    /** The key [MainActivity] stores [processToken] under in its saved state. */
    const val KEY = "app.aroundtheblock.wallet.process_token"

    /** Different in every process. */
    val processToken: String = java.util.UUID.randomUUID().toString()

    /** Restore [saved] only when it was written by this process ([savedToken] matches). */
    fun <T : Any> stateToRestore(saved: T?, savedToken: String?): T? =
        if (saved != null && savedToken == processToken) saved else null
}
