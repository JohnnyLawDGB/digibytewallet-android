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

    /** minSdk 26 through the newest level the app targets, and one beyond. */
    private val everyApi = 26..37

    @Test
    fun `KS-BIND binding policy holds for every API level and both lock-screen states`() {
        for (api in everyApi) for (secure in listOf(true, false)) {
            val expected = when {
                !secure -> SeedKeyBinding.NONE                 // API 33 keygen-without-lock-screen crash
                api >= 30 -> SeedKeyBinding.TIMEOUT_PARAMS
                else -> SeedKeyBinding.TIMEOUT_LEGACY          // API 28 lives here: encrypt needs an auth event
            }
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

    @Test
    fun `KS-BIND status line for every stored-record and lock-screen combination`() {
        for (bound in listOf(true, false)) for (secure in listOf(true, false)) {
            assertEquals("no wallet (bound=$bound secure=$secure)", SeedKeyStatus.NO_WALLET, seedKeyStatusFor(false, bound, secure))
        }
        assertEquals(SeedKeyStatus.BOUND, seedKeyStatusFor(hasWallet = true, recordBound = true, deviceSecure = true))
        assertEquals(SeedKeyStatus.LOCK_REMOVED, seedKeyStatusFor(hasWallet = true, recordBound = true, deviceSecure = false))
        assertEquals(SeedKeyStatus.PENDING, seedKeyStatusFor(hasWallet = true, recordBound = false, deviceSecure = true))
        assertEquals(SeedKeyStatus.NO_LOCK_SCREEN, seedKeyStatusFor(hasWallet = true, recordBound = false, deviceSecure = false))
    }
}
