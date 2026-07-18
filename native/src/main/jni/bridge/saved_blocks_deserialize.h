/*
 * saved_blocks_deserialize.h
 *
 * Pure (no-JNI) deserialization core extracted from
 * loadSavedBlocks (jni_peer.c), so a host KAT can exercise the
 * count-cap + malloc-null-check guard directly, without a JVM.
 *
 * A corrupt/truncated persisted `saved_blocks` blob can carry an absurd
 * leading 4-byte block count. Before this guard, `malloc(count *
 * sizeof(BRMerkleBlock *))` was called unconditionally and its result went
 * unchecked -- on a memory-constrained device a huge count makes malloc
 * return NULL, and the first `blocks[loaded++] = block` write is a
 * NULL-pointer dereference (SIGSEGV) on every subsequent app launch, since
 * the same corrupt blob is reloaded every time. Rejecting absurd counts up
 * front, and null-checking the allocation, makes the load fail closed
 * (return 0 -> caller drops the blob and re-syncs) instead of crashing.
 *
 * Mirrors the cap + null-check convention already used by the sibling
 * guard, loadSerializedTransactions (jni_transaction_persist.c), which
 * rejects `txCount == 0 || txCount > 10000` and null-checks its calloc.
 * Saved blocks use a higher ceiling since a long-lived wallet's saved
 * chain segment can run to tens of thousands of headers, but never
 * anywhere near a corrupt/garbage 32-bit count.
 */
#ifndef SAVED_BLOCKS_DESERIALIZE_H
#define SAVED_BLOCKS_DESERIALIZE_H

#include <stdint.h>
#include <stdlib.h>
#include "BRInt.h"
#include "BRMerkleBlock.h"

#define SAVED_BLOCKS_MAX_COUNT 100000

/* Parses a persisted saved-blocks buffer:
 *   [4 bytes: LE block count]
 *   repeated: [4 bytes blockLen][4 bytes height][blockLen bytes serialized block]
 *
 * On a sane, in-range count, allocates *outBlocks (caller owns the array
 * and each BRMerkleBlock* in it, same as before this extraction) and
 * returns the number of blocks actually parsed (<= count, still bounded by
 * buffer length via the pre-existing per-iteration `pos + N <= len`
 * checks).
 *
 * On a corrupt count (0 or > SAVED_BLOCKS_MAX_COUNT) or a failed
 * allocation, sets *outBlocks = NULL and returns 0 WITHOUT ever touching
 * the count for allocation sizing again -- fails closed instead of
 * crashing.
 */
static inline size_t deserialize_saved_blocks_guarded(const uint8_t *b, size_t len,
                                                       BRMerkleBlock ***outBlocks) {
    *outBlocks = NULL;
    if (!b || len < 4) return 0;

    size_t pos = 0;
    uint32_t count = UInt32GetLE(&b[pos]); pos += 4;

    if (count == 0 || count > SAVED_BLOCKS_MAX_COUNT) return 0;

    BRMerkleBlock **blocks = malloc(count * sizeof(BRMerkleBlock *));
    if (!blocks) return 0;

    size_t loaded = 0;
    for (uint32_t i = 0; i < count && pos + 8 <= len; i++) {
        uint32_t blockLen = UInt32GetLE(&b[pos]); pos += 4;
        uint32_t height   = UInt32GetLE(&b[pos]); pos += 4;

        if (pos + blockLen > len) break;

        BRMerkleBlock *block = BRMerkleBlockParse(&b[pos], blockLen);
        pos += blockLen;

        if (block) {
            block->height = height;
            blocks[loaded++] = block;
        }
    }

    *outBlocks = blocks;
    return loaded;
}

#endif /* SAVED_BLOCKS_DESERIALIZE_H */
