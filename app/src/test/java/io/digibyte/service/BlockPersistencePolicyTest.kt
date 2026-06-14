package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the saved-blocks monotonic persistence guard.
 *
 * Regression guard for the sync-persistence bug: `onSaveBlocks` blindly
 * overwrote `saved_blocks`, so a session resuming/saving a LOWER window could
 * replace a higher persisted tip (observed: 23,176,000 → 23,060,000 between
 * launches), forcing a ~480k-block re-sync. [shouldPersistBlocks] refuses any
 * write whose top height is below what's already on disk; the block tip only
 * legitimately advances (DGB reorgs are <<100 blocks), so a lower window is
 * always stale.
 */
class BlockPersistencePolicyTest {

    @Test
    fun `a higher tip is persisted`() {
        assertTrue(shouldPersistBlocks(newTopHeight = 23_184_000L, persistedTopHeight = 23_180_000L))
    }

    @Test
    fun `an equal tip is persisted (refreshes the window)`() {
        assertTrue(shouldPersistBlocks(newTopHeight = 23_660_000L, persistedTopHeight = 23_660_000L))
    }

    @Test
    fun `a lower tip is rejected (the regression case)`() {
        assertFalse(shouldPersistBlocks(newTopHeight = 23_060_000L, persistedTopHeight = 23_176_000L))
    }

    @Test
    fun `the first ever save is persisted (no prior tip)`() {
        assertTrue(shouldPersistBlocks(newTopHeight = 23_500_000L, persistedTopHeight = 0L))
    }

    @Test
    fun `unparseable top height fails open so saves are never silently lost`() {
        // parseSavedBlocksTopHeight returns -1 when the buffer is too short to
        // hold count+len+height; the guard must still allow the write.
        assertEquals(-1L, parseSavedBlocksTopHeight(ByteArray(4)))
        assertTrue(shouldPersistBlocks(newTopHeight = -1L, persistedTopHeight = 23_176_000L))
    }

    @Test
    fun `top height is parsed from the serialized block buffer`() {
        // Format (LE): [4B count][4B block0 len][4B block0 height][...]. block0 is
        // the highest block (saved walking backward from the chain tip), so its
        // height is the window's top.
        val buf = ByteArray(12)
        // count = 300
        writeLE(buf, 0, 300)
        // block0 serialized length = 119
        writeLE(buf, 4, 119)
        // block0 height = 23,666,195
        writeLE(buf, 8, 23_666_195)
        assertEquals(23_666_195L, parseSavedBlocksTopHeight(buf))
    }

    private fun writeLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v ushr 8) and 0xff).toByte()
        b[off + 2] = ((v ushr 16) and 0xff).toByte()
        b[off + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
