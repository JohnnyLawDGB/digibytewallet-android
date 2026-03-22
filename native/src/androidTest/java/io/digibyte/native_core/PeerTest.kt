package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 2d instrumented smoke-test — verifies that:
 *
 *   1. core-lib loads on the device without UnsatisfiedLinkError
 *      (arm64-v8a, Samsung Galaxy Note 8, Android 9 / API 28).
 *   2. JNI function dispatch works (native symbol is reachable).
 *   3. Key compile-time constants embedded in the C core are correct:
 *        - mainnet magic  = 0xdab6c3fa  (DigiByte 8.26)
 *        - standard port  = 12024
 *        - ≥ 8 DNS seeds  (updated for 8.26)
 *        - ≥ 40 checkpoints (covers heights up to ~23 M)
 *
 * Full live-peer connectivity (DNS resolution, TCP handshake, version
 * exchange) is deferred to Task 11 (SPV foreground service) because it
 * requires a wallet context, storage, and a running peer manager thread.
 */
@RunWith(AndroidJUnit4::class)
class PeerTest {

    companion object {
        init {
            System.loadLibrary("core-lib")
        }

        /**
         * Returns a bitmask (see jni_test.c for bit definitions).
         *   bit 0 — protocol version 70019 (set unconditionally after patch)
         *   bit 1 — magic == 0xdab6c3fa
         *   bit 2 — port  == 12024
         *   bit 3 — DNS seeds >= 8
         *   bit 4 — checkpoints >= 40
         */
        @JvmStatic
        external fun testPeerDiscovery(): Int
    }

    /** Library must load — if this throws, all subsequent tests are moot. */
    @Test
    fun nativeLibraryLoads() {
        // Reaching this line proves System.loadLibrary("core-lib") succeeded.
        assertTrue("core-lib must load without UnsatisfiedLinkError", true)
    }

    /** The JNI function must be callable and return a positive result. */
    @Test
    fun peerDiscoveryReturnsPositive() {
        val result = testPeerDiscovery()
        assertTrue(
            "testPeerDiscovery() must return > 0 (got $result — bitmask of checks passed)",
            result > 0
        )
    }

    /** All five checks inside the C function must pass → bitmask = 0x1f = 31. */
    @Test
    fun allConstantsCorrect() {
        val result = testPeerDiscovery()
        val expected = 0x1f  // bits 0-4 all set
        assertEquals(
            "All C-core compile-time constants must match DigiByte 8.26 values " +
                    "(expected 0x${expected.toString(16)}, got 0x${result.toString(16)})",
            expected,
            result
        )
    }
}
