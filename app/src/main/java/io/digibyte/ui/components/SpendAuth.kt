package io.digibyte.ui.components

import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.R
import io.digibyte.core.WalletManager
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.BiometricResult
import io.digibyte.core.security.PinManager
import io.digibyte.core.security.PinVerifyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "SpendAuth"

/** Which credential a value-moving / identity action must present before it runs. */
enum class AuthMethod { BIOMETRIC, PIN, DENY }

/**
 * Pure policy behind [SpendAuth.authorize].
 *
 * DENY whenever there is no PIN: a biometric alone has nothing to fall through to when the
 * user taps "Use PIN", and a wallet screen without a PIN is not a reachable state after
 * onboarding — so it is treated as the anomaly it is rather than as "nothing to check".
 */
fun authMethodFor(biometricAvailable: Boolean, hasPin: Boolean): AuthMethod = when {
    !hasPin -> AuthMethod.DENY
    biometricAvailable -> AuthMethod.BIOMETRIC
    else -> AuthMethod.PIN
}

/**
 * The prompt's negative button reads "Use PIN" (BiometricAuth), and a swipe-away is the
 * same intent on devices that surface it as USER_CANCELED. A sensor that cannot serve —
 * locked out after failed attempts (canAuthenticate() still reports SUCCESS during a
 * lockout, so the prompt opens and fails at once), no enrolment, hardware missing or busy,
 * timed out — also falls through: the in-app PIN is a strictly in-app credential, so
 * reaching it costs nothing, and without this a locked-out user could not send at all.
 * ERROR_CANCELED is the OS abandoning the prompt (screen off, backgrounded): the action is
 * being abandoned, not re-credentialed, so it and every unclassified failure deny.
 */
fun biometricErrorFallsThroughToPin(errorCode: Int): Boolean = when (errorCode) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_TIMEOUT -> true
    else -> false
}

/**
 * Resolved with hiltViewModel() from inside [rememberSpendAuth] so screens get the gate
 * without PinManager being threaded through AppNavigation.
 */
@HiltViewModel
class AuthGateViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val walletManager: WalletManager,
    val biometricAuth: BiometricAuth,
) : ViewModel() {

    fun hasPin(): Boolean = try { pinManager.hasPin() } catch (e: Exception) {
        Log.e(TAG, "hasPin() failed: ${e.message}")
        false
    }

    fun verifyPin(pin: String): PinVerifyResult = pinManager.verifyPin(pin)

    /**
     * Wipe-after-N tripped inside a spend dialog. Same sequence as UnlockScreen: PinManager
     * has already persisted pin_wipe_pending, so a kill mid-way is completed by the
     * MainActivity backstop. NonCancellable because the wipe flips the wallet to NoWallet,
     * AppNavigation then pops this screen (and its ViewModel scope) — the clearPin() that
     * releases the pending flag must still run.
     */
    fun wipeWallet() {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { walletManager.wipeWallet() }
                    .onFailure { Log.e(TAG, "wipe after failed PINs failed", it) }
                pinManager.clearPin()
            }
        }
    }
}

/**
 * The second gate on every action that moves value or signs an identity: DGB / DigiDollar /
 * DigiAsset send, the funds sweep, Digi-ID approve, Hub quick-login, own-node pairing.
 *
 * Biometric first when the device has a STRONG credential; its "Use PIN" button (or a
 * cancel) falls through to the in-app [PinVerifyDialog]. Without biometrics the PIN dialog
 * is the gate. There is no third branch: before this existed, "no biometric" meant the
 * action simply ran.
 *
 * Usage: `val spendAuth = rememberSpendAuth()`, place `spendAuth.Dialogs()` in the screen's
 * tree, then `if (spendAuth.authorize(activity, title, subtitle)) doIt() else cancel()`.
 */
class SpendAuth internal constructor(private val vm: AuthGateViewModel) {

    private class PinRequest(val subtitle: String) {
        val outcome = CompletableDeferred<Boolean>()
    }

    private var pending by mutableStateOf<PinRequest?>(null)

    /** True only when the user presented a valid credential. Must be called on Main. */
    suspend fun authorize(activity: FragmentActivity?, title: String, subtitle: String): Boolean {
        val biometricAvailable = activity != null && vm.biometricAuth.canAuthenticate(activity)
        return when (authMethodFor(biometricAvailable, vm.hasPin())) {
            AuthMethod.DENY -> {
                Log.w(TAG, "spend action denied: no PIN set on a wallet screen")
                false
            }
            AuthMethod.BIOMETRIC -> when (val r = vm.biometricAuth.authenticate(activity!!, title, subtitle)) {
                is BiometricResult.Success -> true
                is BiometricResult.Error ->
                    if (biometricErrorFallsThroughToPin(r.errorCode)) awaitPin(subtitle) else false
            }
            AuthMethod.PIN -> awaitPin(subtitle)
        }
    }

    private suspend fun awaitPin(subtitle: String): Boolean {
        // A second request while one dialog is open (double-tap) resolves the first as denied
        // rather than leaving two callers waiting on one keypad.
        pending?.outcome?.complete(false)
        val req = PinRequest(subtitle)
        pending = req
        try {
            return req.outcome.await()
        } finally {
            if (pending === req) pending = null
        }
    }

    /** Hosts the PIN dialog; the screen places this once anywhere in its composition. */
    @Composable
    fun Dialogs() {
        val req = pending ?: return
        var input by remember(req) { mutableStateOf("") }
        var error by remember(req) { mutableStateOf<String?>(null) }
        val resources = LocalContext.current.resources
        val incorrectPinMsg = stringResource(R.string.sec_incorrect_pin)

        PinVerifyDialog(
            title = stringResource(R.string.unlock_title),
            subtitle = req.subtitle,
            pinInput = input,
            pinError = error,
            onDigit = { d ->
                if (input.length < PIN_VERIFY_LENGTH) {
                    input += d
                    error = null
                    if (input.length == PIN_VERIFY_LENGTH) {
                        val entered = input
                        input = ""
                        when (val r = vm.verifyPin(entered)) {
                            is PinVerifyResult.Success -> req.outcome.complete(true)
                            is PinVerifyResult.Wrong -> error = r.lockedUntil
                                ?.let { pinLockedCountdownMessage(resources, it) } ?: incorrectPinMsg
                            is PinVerifyResult.LockedOut -> error = pinLockedCountdownMessage(resources, r.until)
                            is PinVerifyResult.ShouldWipe -> {
                                req.outcome.complete(false)
                                vm.wipeWallet()
                            }
                        }
                    }
                }
            },
            onBackspace = { if (input.isNotEmpty()) input = input.dropLast(1) },
            onDismiss = { req.outcome.complete(false) }
        )
    }
}

@Composable
fun rememberSpendAuth(vm: AuthGateViewModel = hiltViewModel()): SpendAuth = remember(vm) { SpendAuth(vm) }
