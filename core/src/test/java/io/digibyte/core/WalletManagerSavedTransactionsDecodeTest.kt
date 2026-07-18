package io.digibyte.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the `saved_transactions` restore coverage gap in
 * [WalletManager.restoreFromDisk]: it used to hex-decode the persisted blob with
 * the naive `hex.chunked(2).map { it.toInt(16).toByte() }`, which throws
 * [NumberFormatException] on ANY non-hex byte — odd length or a stray char — and
 * that throw escaped the function's try/finally (only the seed bytes are zeroed
 * in `finally`; nothing catches it), crashing startup on a corrupt tx cache.
 *
 * [WalletManager.restoreFromDisk] itself can't be exercised directly in a pure
 * JVM test: it unconditionally calls `NativeBridge.stopSync()` first, and
 * `NativeBridge` is a JNI object whose `init` block does `System.loadLibrary
 * ("core-lib")` — unavailable on the host JVM test runner (the same module-
 * boundary/native-linkage constraint noted for Task 2's BootGuard wiring).
 * So this exercises the extracted, pure decode helper
 * [decodeSavedTransactionsOrNull] that `restoreFromDisk` now calls in place of
 * the naive decoder — the fix is verified at the seam that actually changed.
 */
class WalletManagerSavedTransactionsDecodeTest {

    @Test fun `valid hex decodes to the expected bytes`() {
        assertArrayEquals(
            byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()),
            decodeSavedTransactionsOrNull("deadbeef")
        )
    }

    @Test fun `null blob decodes to null`() {
        assertNull(decodeSavedTransactionsOrNull(null))
    }

    @Test fun `odd-length hex is dropped as null instead of throwing`() {
        // The legacy `chunked(2).map { it.toInt(16).toByte() }` would either throw
        // or silently truncate here; the fix must return null, not throw.
        assertNull(decodeSavedTransactionsOrNull("abc"))
    }

    @Test fun `non-hex characters are dropped as null instead of throwing NumberFormatException`() {
        // "zz".toInt(16) is exactly the call that threw NumberFormatException in
        // the naive decoder this replaces.
        assertNull(decodeSavedTransactionsOrNull("00zz"))
        assertNull(decodeSavedTransactionsOrNull("gg"))
    }

    @Test fun `malformed or edge-case blobs never throw any exception`() {
        val blobs = listOf("abc", "00zz", "not-hex-at-all", "1", "")
        for (blob in blobs) {
            try {
                decodeSavedTransactionsOrNull(blob)
            } catch (e: Exception) {
                org.junit.Assert.fail("decodeSavedTransactionsOrNull(\"$blob\") threw ${e::class.simpleName} — must drop, never throw")
            }
        }
    }
}
