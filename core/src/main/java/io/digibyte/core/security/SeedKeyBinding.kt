package io.digibyte.core.security

/**
 * How the seed-wrapping Keystore key is (or isn't) bound to device authentication.
 * Pure policy — decided from the API level and whether the device has a secure lock
 * screen — so the crash matrix that killed the first attempt (256522c2: API 28
 * encrypt-before-auth, API 33 keygen with no lock screen, API 35 auth-state
 * inconsistency) is encoded in one testable place instead of scattered try/catches.
 *
 * Spec: docs/specs/keystore-auth-binding.md
 */
enum class SeedKeyBinding {
    /** No binding — device has no secure lock screen; keygen with auth required would
     *  throw (the API 33 crash). Behavior identical to the pre-binding wallet. */
    NONE,

    /** API 26–29: setUserAuthenticationRequired(true) + validity-duration window. */
    TIMEOUT_LEGACY,

    /** API 30+: setUserAuthenticationParameters(window, DEVICE_CREDENTIAL | BIOMETRIC_STRONG). */
    TIMEOUT_PARAMS,
}

/** Seconds after the last device unlock / device-credential auth during which the
 *  auth-bound key is usable. Every seed decrypt is a foreground flow, almost always
 *  within minutes of a device unlock; outside the window the typed-exception path
 *  prompts and retries instead of crashing. */
const val SEED_KEY_AUTH_WINDOW_SECS = 300

fun seedKeyBindingFor(apiLevel: Int, deviceSecure: Boolean): SeedKeyBinding = when {
    !deviceSecure -> SeedKeyBinding.NONE
    apiLevel >= 30 -> SeedKeyBinding.TIMEOUT_PARAMS
    else -> SeedKeyBinding.TIMEOUT_LEGACY
}

/** The auth-bound key refused to work because no device unlock happened within the
 *  window. Foreground callers refresh with a DEVICE_CREDENTIAL|BIOMETRIC_STRONG
 *  prompt and retry; background callers treat it as "not now". Never a crash. */
class KeystoreUserAuthRequiredException(cause: Throwable) :
    Exception("Keystore key requires a recent device unlock", cause)

/** The auth-bound key was permanently invalidated — the device lock screen was
 *  removed. The seed blob under it is gone for good; the wallet must be restored
 *  from the written recovery phrase. Surfaced as its own message, never as a
 *  generic unlock failure. */
class KeystoreKeyInvalidatedException(cause: Throwable) :
    Exception("Keystore key permanently invalidated (device lock removed)", cause)
