package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the JNI SOCKS5 proxy bridge functions.
 * These tests verify that the native functions are correctly registered
 * and do not crash the JNI layer. Actual proxy connectivity is not tested
 * here — that requires a running Tor daemon.
 */
@RunWith(AndroidJUnit4::class)
class JniProxyTest {

    @Test
    fun setSocksProxy_doesNotCrash() {
        // Should call the native function without throwing or crashing
        NativeBridge.setSocksProxy("127.0.0.1", 9050)
    }

    @Test
    fun clearSocksProxy_doesNotCrash() {
        // Should call the native function without throwing or crashing
        NativeBridge.clearSocksProxy()
    }

    @Test
    fun setSocksProxy_thenClear_doesNotCrash() {
        // Set then immediately clear — verifies both paths are stable
        NativeBridge.setSocksProxy("127.0.0.1", 9050)
        NativeBridge.clearSocksProxy()
        // A second clear should also be a no-op
        NativeBridge.clearSocksProxy()
    }
}
