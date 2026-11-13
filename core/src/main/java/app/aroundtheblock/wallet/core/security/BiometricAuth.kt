package app.aroundtheblock.wallet.core.security

import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BiometricAuth {

    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "HoldFast Wallet",
        subtitle: String = "Authenticate to continue",
        negativeButtonText: String = "Use PIN"
    ): BiometricResult = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(BiometricResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(BiometricResult.Error(errorCode, errString.toString()))
            }

            override fun onAuthenticationFailed() {
                // Don't resume — biometric prompt handles retry internally
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(info)
    }

    /**
     * Device-credential prompt for refreshing the auth-bound Keystore key's window
     * (docs/specs/keystore-auth-binding.md): DEVICE_CREDENTIAL | BIOMETRIC_STRONG,
     * and NO negative button — the API forbids one when DEVICE_CREDENTIAL is allowed.
     * A success from EITHER method counts as a device auth event, which is exactly
     * what a timeout-bound key needs to become usable again.
     */
    suspend fun authenticateDeviceCredential(
        activity: FragmentActivity,
        title: String = "HoldFast Wallet",
        subtitle: String = "Confirm your device lock to unlock the wallet key",
    ): BiometricResult = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        // SEED-VIEW-AUTH: generation of this prompt in OwnCredentialPromptExemption; set before
        // prompt.authenticate, so every callback below closes this prompt and no other.
        var promptGen = 0
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                OwnCredentialPromptExemption.closePrompt(promptGen, success = true)
                if (cont.isActive) cont.resume(BiometricResult.Success)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                OwnCredentialPromptExemption.closePrompt(promptGen, success = false)
                if (cont.isActive) cont.resume(BiometricResult.Error(errorCode, errString.toString()))
            }
            override fun onAuthenticationFailed() { /* prompt retries internally; still up */ }
        }
        // androidx.biometric rejects BIOMETRIC_STRONG|DEVICE_CREDENTIAL on API
        // 28-29 with an IllegalArgumentException (crash-looped the Note 8 at
        // unlock, 2026-08-31). On those levels WEAK|DEVICE_CREDENTIAL is the
        // supported combo; the keyguard (device-credential) path is what
        // refreshes a validity-duration Keystore key either way. A weak-
        // biometric success that does NOT open the key window falls out as one
        // more KeystoreUserAuthRequired retry, never a crash.
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.DEVICE_CREDENTIAL or
                BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.DEVICE_CREDENTIAL or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        try {
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()
            // SEED-VIEW-AUTH: opens this prompt; arms the onStop deferral only on API 29 (the framework
            // launches the confirm). API <= 28 arms at the confirm launch, API >= 30 never.
            promptGen = OwnCredentialPromptExemption.openPrompt(SystemClock.elapsedRealtime(), Build.VERSION.SDK_INT)
            prompt.authenticate(info)
            // A caller cancelled (its effect left the composition): take the prompt down with it, so it
            // is not left over the next screen with nothing waiting on its answer. Main thread only.
            cont.invokeOnCancellation {
                OwnCredentialPromptExemption.closePrompt(promptGen, success = false)
                executor.execute { runCatching { prompt.cancelAuthentication() } }
            }
        } catch (t: Throwable) {
            OwnCredentialPromptExemption.closePrompt(promptGen, success = false)
            // A credential prompt must never be able to crash the wallet - the
            // caller treats this like any other prompt failure.
            if (cont.isActive) cont.resume(BiometricResult.Error(-1, t.message ?: "prompt failed"))
        }
    }
}

sealed class BiometricResult {
    data object Success : BiometricResult()
    data class Error(val errorCode: Int, val message: String) : BiometricResult()
}
