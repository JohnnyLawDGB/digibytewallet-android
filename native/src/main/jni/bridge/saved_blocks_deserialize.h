/*
 * saved_blocks_deserialize.h
 *
 * FORWARDING SHIM. The parser moved into the shared core as BRSavedBlocks.h
 * (push-down #8, 2026-09-08) so the iOS port can resume from saved blocks
 * instead of re-syncing ~126,000 blocks on every launch. It was always pure --
 * no JNI, no locking, no I/O -- so the move was a move, not a rewrite.
 *
 * This file stays so jni_peer.c and the three existing host KATs keep compiling
 * unchanged; it is the smallest possible Android-side diff for a core addition.
 * Retire it when those call sites are updated to the BR-prefixed name.
 */
#ifndef SAVED_BLOCKS_DESERIALIZE_H
#define SAVED_BLOCKS_DESERIALIZE_H

#include "BRSavedBlocks.h"

#define SAVED_BLOCKS_MAX_COUNT BR_SAVED_BLOCKS_MAX_COUNT

static inline size_t deserialize_saved_blocks_guarded(const uint8_t *b, size_t len,
                                                       BRMerkleBlock ***outBlocks) {
    return BRSavedBlocksDeserialize(b, len, outBlocks);
}

#endif /* SAVED_BLOCKS_DESERIALIZE_H */
