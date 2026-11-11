package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SeedKeyBindingTest {

    @Test
    fun `no lock screen means no binding on every API level`() {
        // The API 33 crash: keygen with auth required throws without a lock screen.
        for (api in intArrayOf(26, 28, 30, 33, 35)) {
            assertEquals(SeedKeyBinding.NONE, seedKeyBindingFor(api, deviceSecure = false))
        }
    }

    @Test
    fun `api 30 plus uses setUserAuthenticationParameters`() {
        for (api in intArrayOf(30, 33, 34, 35)) {
            assertEquals(SeedKeyBinding.TIMEOUT_PARAMS, seedKeyBindingFor(api, deviceSecure = true))
        }
    }

    @Test
    fun `api 26 to 29 uses the legacy validity-duration binding`() {
        for (api in intArrayOf(26, 27, 28, 29)) {
            assertEquals(SeedKeyBinding.TIMEOUT_LEGACY, seedKeyBindingFor(api, deviceSecure = true))
        }
    }

    @Test
    fun `auth window is minutes not the 10 seconds that crashed v3`() {
        // 256522c2 removed a 10s window; anything that tight guarantees
        // UserNotAuthenticatedException in normal use. Guard the constant.
        assert(SEED_KEY_AUTH_WINDOW_SECS >= 60)
    }

    // ── KS-BIND: the full matrix, every supported API level x secure/insecure ───────────

    /**
     * Literal expected rows, written out rather than recomputed with the implementation's own
     * `when`, so a change to the policy cannot carry its test along with it. Each historical crash
     * mode is a row: API 28 (encrypt-before-auth: legacy validity window, an auth event first),
     * API 33 with no lock screen (keygen with auth required throws: NONE), API 35 (the
     * auth-state inconsistency: parameters binding, failure handled by the probe fallback).
     */
    private val bindingRows = listOf(
        Triple(26, true, SeedKeyBinding.TIMEOUT_LEGACY), Triple(26, false, SeedKeyBinding.NONE),
        Triple(27, true, SeedKeyBinding.TIMEOUT_LEGACY), Triple(27, false, SeedKeyBinding.NONE),
        Triple(28, true, SeedKeyBinding.TIMEOUT_LEGACY), Triple(28, false, SeedKeyBinding.NONE),
        Triple(29, true, SeedKeyBinding.TIMEOUT_LEGACY), Triple(29, false, SeedKeyBinding.NONE),
        Triple(30, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(30, false, SeedKeyBinding.NONE),
        Triple(31, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(31, false, SeedKeyBinding.NONE),
        Triple(32, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(32, false, SeedKeyBinding.NONE),
        Triple(33, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(33, false, SeedKeyBinding.NONE),
        Triple(34, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(34, false, SeedKeyBinding.NONE),
        Triple(35, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(35, false, SeedKeyBinding.NONE),
        Triple(36, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(36, false, SeedKeyBinding.NONE),
        Triple(37, true, SeedKeyBinding.TIMEOUT_PARAMS), Triple(37, false, SeedKeyBinding.NONE),
    )

    @Test
    fun `KS-BIND binding policy holds for every API level and both lock-screen states`() {
        assertEquals("one row per API 26..37 x secure/insecure", 24, bindingRows.size)
        for ((api, secure, expected) in bindingRows) {
            assertEquals("api=$api secure=$secure", expected, seedKeyBindingFor(api, secure))
        }
    }

    @Test
    fun `KS-BIND readiness of a new record for every probe outcome and both lock-screen states`() {
        for (probe in AuthBoundProbe.values()) {
            assertEquals("an insecure device never prompts and never binds (probe=$probe)",
                SeedKeyReadiness.NO_LOCK_SCREEN, seedKeyReadinessFor(deviceSecure = false, probe = probe))
        }
        assertEquals(SeedKeyReadiness.READY, seedKeyReadinessFor(true, AuthBoundProbe.READY))
        // The API 28 encrypt-before-auth case: answered with ONE prompt, never a crash.
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, seedKeyReadinessFor(true, AuthBoundProbe.AUTH_REQUIRED))
        // The API 35 auth-state inconsistency and vendor quirks: no prompt (it would not help), legacy fallback.
        assertEquals(SeedKeyReadiness.UNAVAILABLE, seedKeyReadinessFor(true, AuthBoundProbe.UNAVAILABLE))
        assertEquals(SeedKeyReadiness.UNAVAILABLE, seedKeyReadinessFor(true, AuthBoundProbe.INVALIDATED))
    }

    /** (recordBound, boundKey, deviceSecure) -> status, every combination written out. */
    private val statusRows: List<Pair<Triple<Boolean, BoundKeyState, Boolean>, SeedKeyStatus>> = listOf(
        // A bound record: BOUND only while the key still opens AND the device has a lock.
        Triple(true, BoundKeyState.USABLE, true) to SeedKeyStatus.BOUND,
        Triple(true, BoundKeyState.UNKNOWN, true) to SeedKeyStatus.BOUND,
        // The lock was removed and a new one set: the record says bound, the key is gone (API 31+)
        // or invalidated (API 26-30). Never BOUND.
        Triple(true, BoundKeyState.ABSENT, true) to SeedKeyStatus.LOCK_REMOVED,
        Triple(true, BoundKeyState.INVALIDATED, true) to SeedKeyStatus.LOCK_REMOVED,
        Triple(true, BoundKeyState.USABLE, false) to SeedKeyStatus.LOCK_REMOVED,
        Triple(true, BoundKeyState.UNKNOWN, false) to SeedKeyStatus.LOCK_REMOVED,
        Triple(true, BoundKeyState.ABSENT, false) to SeedKeyStatus.LOCK_REMOVED,
        Triple(true, BoundKeyState.INVALIDATED, false) to SeedKeyStatus.LOCK_REMOVED,
        // A legacy record.
        Triple(false, BoundKeyState.ABSENT, true) to SeedKeyStatus.PENDING,
        Triple(false, BoundKeyState.USABLE, true) to SeedKeyStatus.PENDING,
        Triple(false, BoundKeyState.UNKNOWN, true) to SeedKeyStatus.PENDING,
        Triple(false, BoundKeyState.INVALIDATED, true) to SeedKeyStatus.CANNOT_BIND,
        Triple(false, BoundKeyState.ABSENT, false) to SeedKeyStatus.NO_LOCK_SCREEN,
        Triple(false, BoundKeyState.USABLE, false) to SeedKeyStatus.NO_LOCK_SCREEN,
        Triple(false, BoundKeyState.UNKNOWN, false) to SeedKeyStatus.NO_LOCK_SCREEN,
        Triple(false, BoundKeyState.INVALIDATED, false) to SeedKeyStatus.NO_LOCK_SCREEN,
    )

    @Test
    fun `KS-BIND status line for every stored-record, bound-key and lock-screen combination`() {
        assertEquals("every combination is a row", 2 * BoundKeyState.values().size * 2, statusRows.size)
        for ((input, expected) in statusRows) {
            val (bound, key, secure) = input
            assertEquals("bound=$bound key=$key secure=$secure", expected, seedKeyStatusFor(true, bound, key, secure))
            assertEquals("no wallet (bound=$bound key=$key secure=$secure)", SeedKeyStatus.NO_WALLET, seedKeyStatusFor(false, bound, key, secure))
        }
    }
}
