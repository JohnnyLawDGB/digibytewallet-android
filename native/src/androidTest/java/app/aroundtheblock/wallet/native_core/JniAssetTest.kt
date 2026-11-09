package app.aroundtheblock.wallet.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the asset JNI bridge (Task 6).
 *
 * Tests run on-device (Samsung SM-N950U, Android 9, API 28).
 * Uses the real JNI implementation via the loaded core-lib.so.
 */
@RunWith(AndroidJUnit4::class)
class JniAssetTest {

    // ── isAssetTransaction ────────────────────────────────────────────────────

    @Test
    fun isAssetTransaction_withEmptyTx_returnsFalse() {
        // An empty byte array cannot be parsed as a transaction — must return false
        val result = NativeBridge.isAssetTransaction(ByteArray(0))
        assertFalse("Empty tx should not be an asset transaction", result)
    }

    @Test
    fun isAssetTransaction_withGarbageBytes_returnsFalse() {
        // Random bytes that do not form a valid Bitcoin-style transaction
        val garbage = ByteArray(64) { it.toByte() }
        val result = NativeBridge.isAssetTransaction(garbage)
        assertFalse("Garbage bytes should not be an asset transaction", result)
    }

    @Test
    fun isAssetTransaction_withMinimalValidTx_returnsFalse() {
        // A minimal valid mainnet DGB transaction (no outputs with OP_RETURN)
        // Built as: version(4 LE) + inCount(varint 0) + outCount(varint 0) + locktime(4 LE)
        val minimalTx = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, // version = 1
            0x00,                   // input count = 0
            0x00,                   // output count = 0
            0x00, 0x00, 0x00, 0x00  // locktime = 0
        )
        val result = NativeBridge.isAssetTransaction(minimalTx)
        assertFalse("A transaction with no outputs cannot contain an asset", result)
    }
}
