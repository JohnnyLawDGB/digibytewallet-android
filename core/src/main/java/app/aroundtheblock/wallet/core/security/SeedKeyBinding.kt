package app.aroundtheblock.wallet.core.security

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

// ── KS-BIND: a new record is sealed under the auth-bound key FROM CREATION ─────────
//
// Invariant: when the device has a secure lock screen at wallet creation or restore, the seed is
// wrapped under the auth-bound key when the record is first written — not only after a later
// unlock migrates it. The bound key is usable for [SEED_KEY_AUTH_WINDOW_SECS] after ANY device
// authentication (the keyguard unlock that opened the phone counts, so does a device-credential
// prompt), so a create or restore that reaches its record write inside that window needs no prompt
// at all. One that does not — the user spent longer than the window writing the phrase down — gets
// ONE device-credential prompt at the step that writes the record (PIN setup for a create, the
// restore's start for a restore), and the record write follows inside the fresh window. Nothing
// here may block onboarding: every other outcome keeps the legacy fallback, and the existing
// unlock-time migration still moves such a wallet later.

/** What a probe of the auth-bound key answered, just before a new record is sealed. */
enum class AuthBoundProbe {
    /** The bound key exists and encrypted: the auth window is open. */
    READY,

    /** The bound key exists but the auth window has lapsed ([KeystoreUserAuthRequiredException]). */
    AUTH_REQUIRED,

    /** The bound key exists but was permanently invalidated ([KeystoreKeyInvalidatedException]). */
    INVALIDATED,

    /** No bound key could be made or used (no lock screen, keygen refused, vendor quirk). */
    UNAVAILABLE,
}

/** What the step that writes a new record must do before the write. */
enum class SeedKeyReadiness {
    /** Seal now: the record will be written under the auth-bound key. */
    READY,

    /** Show ONE device-credential prompt, then seal (whatever the prompt's outcome). */
    NEEDS_DEVICE_CREDENTIAL,

    /** The device has no secure lock screen: seal under the legacy key and tell the user so. */
    NO_LOCK_SCREEN,

    /** Binding is possible in principle but this device refused it: legacy key, no prompt — a
     *  prompt would not help, and onboarding must not be blocked on it. */
    UNAVAILABLE,
}

/** Pure: the readiness of a new record's seal, from whether the device is secure and the probe. */
fun seedKeyReadinessFor(deviceSecure: Boolean, probe: AuthBoundProbe): SeedKeyReadiness = when {
    !deviceSecure -> SeedKeyReadiness.NO_LOCK_SCREEN
    probe == AuthBoundProbe.READY -> SeedKeyReadiness.READY
    probe == AuthBoundProbe.AUTH_REQUIRED -> SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL
    else -> SeedKeyReadiness.UNAVAILABLE
}

/**
 * What the auth-bound `_v2` key itself answers, as opposed to what the stored record says. The
 * record can say "bound" while the key is gone: removing the device lock invalidates the key for
 * good (API 31+ deletes it outright), and setting a new lock does not bring it back.
 */
enum class BoundKeyState {
    /** No key under the auth-bound alias. */
    ABSENT,

    /** The key encrypts, or refuses only because the auth window has lapsed: it still opens. */
    USABLE,

    /** The key is permanently invalidated ([KeystoreKeyInvalidatedException]): it never opens again. */
    INVALIDATED,

    /** The Keystore could not be asked just now; no evidence either way. */
    UNKNOWN,
}

/** Whether the stored seed key is bound to the device lock, for the Security-settings status line. */
enum class SeedKeyStatus {
    /** No wallet record is stored. */
    NO_WALLET,

    /** The seed is wrapped under the auth-bound key, and that key still opens. */
    BOUND,

    /** The seed is on the legacy key on a device that has a lock screen: the next time the app
     *  opens its key (for example after a restart) it is moved. */
    PENDING,

    /** The seed is on the legacy key because the device has no secure lock screen. */
    NO_LOCK_SCREEN,

    /** The seed is wrapped under the auth-bound key but that key no longer opens (the device lock
     *  was removed, even if a new one was set since): the wallet needs its recovery phrase at the
     *  next cold start. */
    LOCK_REMOVED,

    /** The seed is on the legacy key on a secure device, but an auth-bound key left over from an
     *  earlier lock is invalidated, so the move can never happen on this install: PENDING would be
     *  a promise that cannot come true. */
    CANNOT_BIND,

    /** The record says bound, but the key could not be asked just now ([BoundKeyState.UNKNOWN]):
     *  no evidence that it still opens, so nothing is claimed. Security settings hides the line for
     *  that visit rather than say "tied to this phone's screen lock" without evidence. */
    UNCHECKED,
}

/**
 * Pure: the status line, from what is stored, what the auth-bound key answers, and whether the
 * device is secure now. BOUND is claimed only when the record is bound AND the key still opens AND
 * the device has a lock: the record alone is not evidence (KS-BIND), and neither is a key that could
 * not be asked (UNCHECKED).
 */
fun seedKeyStatusFor(
    hasWallet: Boolean,
    recordBound: Boolean,
    boundKey: BoundKeyState,
    deviceSecure: Boolean,
): SeedKeyStatus = when {
    !hasWallet -> SeedKeyStatus.NO_WALLET
    recordBound -> when {
        !deviceSecure -> SeedKeyStatus.LOCK_REMOVED
        boundKey == BoundKeyState.ABSENT || boundKey == BoundKeyState.INVALIDATED -> SeedKeyStatus.LOCK_REMOVED
        boundKey == BoundKeyState.USABLE -> SeedKeyStatus.BOUND
        else -> SeedKeyStatus.UNCHECKED
    }
    !deviceSecure -> SeedKeyStatus.NO_LOCK_SCREEN
    boundKey == BoundKeyState.INVALIDATED -> SeedKeyStatus.CANNOT_BIND
    else -> SeedKeyStatus.PENDING
}

/** How a Keystore failure bears on the auth-bound key, if it does at all. */
enum class KeystoreAuthFailure { AUTH_REQUIRED, INVALIDATED }

/**
 * Classify [t] by walking its cause chain (KS-BIND). Some vendor Keystores do not raise
 * `UserNotAuthenticatedException` at `cipher.init` but at `doFinal`, wrapped as an
 * `IllegalBlockSizeException` whose cause is a `KeyStoreException` ("Key user not authenticated").
 * Read only at the top level, that answered "unavailable", and a create whose auth window had
 * lapsed fell back to the legacy key without a prompt.
 */
fun keystoreAuthFailureOf(t: Throwable): KeystoreAuthFailure? {
    var cur: Throwable? = t
    val seen = HashSet<Throwable>()
    var depth = 0
    while (cur != null && depth < MAX_CAUSE_DEPTH && seen.add(cur)) {
        when {
            cur is KeystoreUserAuthRequiredException -> return KeystoreAuthFailure.AUTH_REQUIRED
            cur is KeystoreKeyInvalidatedException -> return KeystoreAuthFailure.INVALIDATED
            cur is android.security.keystore.UserNotAuthenticatedException -> return KeystoreAuthFailure.AUTH_REQUIRED
            cur is android.security.keystore.KeyPermanentlyInvalidatedException -> return KeystoreAuthFailure.INVALIDATED
            cur.javaClass.simpleName == "KeyStoreException" -> {
                val msg = cur.message?.lowercase().orEmpty()
                if ("user not authenticated" in msg) return KeystoreAuthFailure.AUTH_REQUIRED
                if ("permanently invalidated" in msg) return KeystoreAuthFailure.INVALIDATED
            }
        }
        cur = cur.cause
        depth++
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * Run [block] and map an auth failure anywhere in a thrown exception's cause chain to the typed
 * [KeystoreUserAuthRequiredException] / [KeystoreKeyInvalidatedException]; anything else is
 * rethrown unchanged.
 */
inline fun <T> mapKeystoreAuthErrors(block: () -> T): T = try {
    block()
} catch (e: KeystoreUserAuthRequiredException) {
    throw e
} catch (e: KeystoreKeyInvalidatedException) {
    throw e
} catch (e: Exception) {
    when (keystoreAuthFailureOf(e)) {
        KeystoreAuthFailure.AUTH_REQUIRED -> throw KeystoreUserAuthRequiredException(e)
        KeystoreAuthFailure.INVALIDATED -> throw KeystoreKeyInvalidatedException(e)
        null -> throw e
    }
}
