package io.digibyte.core.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the efficient hex decoder that replaces the OOM-prone
 *  `chunked(2).map { it.toInt(16).toByte() }` load path. */
class FilterHeaderStoreTest {

    @Test fun decodesLowercaseHex() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1f, 0xa4.toByte(), 0xff.toByte()),
            FilterHeaderStore.decodeHexOrNull("001fa4ff")
        )
    }

    @Test fun decodesUppercaseHex() {
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
            FilterHeaderStore.decodeHexOrNull("ABCD")
        )
    }

    @Test fun emptyStringDecodesToEmptyArray() {
        assertArrayEquals(ByteArray(0), FilterHeaderStore.decodeHexOrNull(""))
    }

    @Test fun oddLengthIsNull() {
        assertNull(FilterHeaderStore.decodeHexOrNull("abc"))
    }

    @Test fun nonHexCharIsNull() {
        assertNull(FilterHeaderStore.decodeHexOrNull("00zz"))
        assertNull(FilterHeaderStore.decodeHexOrNull("gg"))
    }

    /** Byte-for-byte equivalent to the old chunked(2).map decoder it replaces. */
    @Test fun matchesLegacyChunkedDecode() {
        val hex = "deadbeef0102fecab00b"
        val legacy = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(legacy, FilterHeaderStore.decodeHexOrNull(hex))
    }

    /** Round-trips the exact lowercase 2-char/byte format SyncService.bytesToHex emits. */
    @Test fun roundTripsAllByteValues() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, FilterHeaderStore.decodeHexOrNull(hex))
    }
}
