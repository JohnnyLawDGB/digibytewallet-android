package io.digibyte.service

/**
 * Pure logic for the saved-blocks monotonic persistence guard, split out of
 * [SyncService.onSaveBlocks] so it can be unit-tested without the service or
 * SharedPreferences.
 *
 * The bug it fixes: `onSaveBlocks` blindly overwrote the `saved_blocks` pref.
 * A session that resumed/saved a window BELOW the persisted tip (observed
 * 23,176,000 → 23,060,000 across launches) regressed the bootstrap anchor and
 * forced a ~480k-block, ~26-minute re-sync. Since the block tip only
 * legitimately advances (DGB reorgs are far shallower than the gaps seen), a
 * lower window is always stale and must never replace a higher one.
 */

/** Persist only if the new window's tip is at least the persisted tip. A
 *  negative [newTopHeight] (parse failure) fails open so a malformed-but-real
 *  save is never silently dropped. */
internal fun shouldPersistBlocks(newTopHeight: Long, persistedTopHeight: Long): Boolean =
    newTopHeight < 0L || newTopHeight >= persistedTopHeight

/**
 * Read the top block height from the serialized saved-blocks buffer produced by
 * `bridge_saveBlocks` (jni_peer.c). Layout, little-endian:
 *   [4B block count][4B block0 serialized-len][4B block0 height][block0 data]...
 * block0 is the highest block (the C side walks backward from the chain tip),
 * so its height is the window's top. Returns -1 if the buffer is too short.
 */
internal fun parseSavedBlocksTopHeight(data: ByteArray): Long {
    if (data.size < 12) return -1L
    // height is the 4 bytes at offset 8 (after count[0..4) and len[4..8)).
    return (data[8].toLong() and 0xff) or
        ((data[9].toLong() and 0xff) shl 8) or
        ((data[10].toLong() and 0xff) shl 16) or
        ((data[11].toLong() and 0xff) shl 24)
}
