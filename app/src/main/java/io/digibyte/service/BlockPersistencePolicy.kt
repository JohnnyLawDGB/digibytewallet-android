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

/** Max plausible size of a saved sync blob in hex chars (32 MB of bytes);
 *  anything larger is corruption, not a real blocks/peers window. */
internal const val MAX_SAVED_BLOB_HEX_LEN = 64 * 1024 * 1024

/**
 * Validate and hex-decode a stored sync blob. Returns the decoded bytes, or
 * null if [hex] is null OR malformed — odd length, non-hex chars, or an absurd
 * size. Pure (the caller drops the bad pref). This is the first line of defense
 * for the native block/peer deserializer: a corrupt blob is rejected here rather
 * than fed to native, where bad bytes can SIGSEGV (an uncatchable native crash
 * that bricked the app on every open before this + the BootGuard existed).
 */
internal fun decodeSyncBlobOrNull(hex: String?): ByteArray? {
    if (hex == null) return null
    if (hex.length % 2 != 0 || hex.length > MAX_SAVED_BLOB_HEX_LEN) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
}
