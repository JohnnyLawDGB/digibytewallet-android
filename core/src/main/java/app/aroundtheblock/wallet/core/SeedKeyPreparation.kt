package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KS-BIND. The one copy of what the step that is about to write a NEW wallet record does first —
 * PIN setup for a create, the restore's start for a restore. The app's
 * `prepareSeedKeyBeforeRecordWrite` supplies the device-credential prompt; the core tests supply a
 * fake one. Both run THIS function, so a test of it is a test of the shipped decision.
 *
 * Invariant: on a device with a secure lock screen the record is sealed under the auth-bound key
 * from creation. [prepare] (normally [WalletManager.prepareSeedKeyForNewRecord]) probes the key off
 * the main thread; on [SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL] the [prompt] runs exactly once and
 * is awaited, so the write that follows lands inside the fresh window. Every other readiness, a
 * null [prompt] (no activity) or a throwing [prepare] shows nothing and lets the write go ahead,
 * which then falls back to the legacy key: onboarding is never blocked on binding.
 */
suspend fun prepareSeedKeyThenPrompt(
    prepare: () -> SeedKeyReadiness,
    prompt: (suspend () -> Unit)?,
): SeedKeyReadiness {
    val readiness = try {
        withContext(Dispatchers.IO) { prepare() }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        SeedKeyReadiness.UNAVAILABLE
    }
    if (shouldPromptForDeviceCredential(readiness) && prompt != null) {
        prompt()
    }
    return readiness
}

/** KS-BIND: the one readiness that shows a prompt. Every other readiness writes without one. */
fun shouldPromptForDeviceCredential(readiness: SeedKeyReadiness): Boolean =
    readiness == SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL
