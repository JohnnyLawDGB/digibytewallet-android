package io.digibyte.core.security

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
}
