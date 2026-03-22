package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
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

    // ── getOpReturnData ───────────────────────────────────────────────────────

    @Test
    fun getOpReturnData_withEmptyTx_returnsNull() {
        // An empty byte array cannot be parsed — must return null
        val result = NativeBridge.getOpReturnData(ByteArray(0))
        assertNull("Empty tx should return null for OP_RETURN data", result)
    }

    @Test
    fun getOpReturnData_withGarbageBytes_returnsNull() {
        val garbage = ByteArray(64) { it.toByte() }
        val result = NativeBridge.getOpReturnData(garbage)
        assertNull("Garbage bytes should return null for OP_RETURN data", result)
    }

    @Test
    fun getOpReturnData_withNoOpReturnOutput_returnsNull() {
        // A minimal valid tx with no outputs — no OP_RETURN can exist
        val minimalTx = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, // version = 1
            0x00,                   // input count = 0
            0x00,                   // output count = 0
            0x00, 0x00, 0x00, 0x00  // locktime = 0
        )
        val result = NativeBridge.getOpReturnData(minimalTx)
        assertNull("Tx with no outputs should return null", result)
    }

    // ── round-trip consistency ────────────────────────────────────────────────

    @Test
    fun isAssetTransaction_andGetOpReturnData_agreeOnEmptyInput() {
        // Both functions must handle the empty-input case consistently
        val raw = ByteArray(0)
        assertFalse(NativeBridge.isAssetTransaction(raw))
        assertNull(NativeBridge.getOpReturnData(raw))
    }
}
