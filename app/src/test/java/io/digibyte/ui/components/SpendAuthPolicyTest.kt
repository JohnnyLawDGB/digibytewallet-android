package io.digibyte.ui.components

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind every value-moving / identity action: which credential must be
 * presented before the action runs.
 *
 * ## Why this gate exists
 *
 * Until the 2026-08-30 audit, a device without a BIOMETRIC_STRONG credential sent DGB,
 * sent DigiDollar and approved Digi-ID logins with NO credential at all — the code said
 * "PIN fallback handled by system", and nothing handled it. DigiAsset send, the funds
 * sweep, Hub quick-login and own-node pairing had no gate on any device. This policy is the
 * one place that decides, and the wiring test pins that every site consults it.
 */
class SpendAuthPolicyTest {

    @Test
    fun `biometric wins when available and a PIN exists`() {
        assertEquals(AuthMethod.BIOMETRIC, authMethodFor(biometricAvailable = true, hasPin = true))
    }

    @Test
    fun `no biometric falls back to the in-app PIN, never to nothing`() {
        assertEquals(AuthMethod.PIN, authMethodFor(biometricAvailable = false, hasPin = true))
    }

    @Test
    fun `no PIN denies even when biometric is available`() {
        // A biometric with no PIN behind it has no fall-through; a wallet screen without a
        // PIN is not a reachable state after onboarding, so the only safe answer is no.
        assertEquals(AuthMethod.DENY, authMethodFor(biometricAvailable = true, hasPin = false))
        assertEquals(AuthMethod.DENY, authMethodFor(biometricAvailable = false, hasPin = false))
    }

    @Test
    fun `the prompt's Use PIN button and a user cancel fall through to the PIN dialog`() {
        assertTrue(biometricErrorFallsThroughToPin(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertTrue(biometricErrorFallsThroughToPin(BiometricPrompt.ERROR_USER_CANCELED))
    }

    @Test
    fun `every other biometric error denies`() {
        for (code in listOf(
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NO_BIOMETRICS,
        )) {
            assertFalse("code $code", biometricErrorFallsThroughToPin(code))
        }
    }
}
