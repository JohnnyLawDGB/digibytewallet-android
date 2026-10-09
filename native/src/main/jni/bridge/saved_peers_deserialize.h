/*
 * saved_peers_deserialize.h
 *
 * Pure (no-JNI) deserialization core of loadSavedPeers (jni_peer.c), so a
 * host KAT can drive it without a JVM -- the same split as
 * saved_blocks_deserialize.h.
 *
 * The blob is the wallet's own `saved_peers` preference, written by
 * bridge_savePeers:
 *   [4 bytes: LE peer count]
 *   repeated: [16 bytes addr][2 bytes LE port][8 bytes LE timestamp][8 bytes LE services]
 *
 * The loader used to size calloc(count, sizeof(BRPeer)) straight from the
 * 4-byte count, with no ceiling and no NULL check, then write the first record
 * through the result. A malformed count made calloc fail (certain on 32-bit,
 * where count * sizeof(BRPeer) overflows size_t), and the first write went
 * through NULL on every launch (BB-2026-10-09-harley F2). The count is now
 * capped and the allocation checked, as the sibling loaders already do.
 */
#ifndef SAVED_PEERS_DESERIALIZE_H
#define SAVED_PEERS_DESERIALIZE_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "BRInt.h"
#include "BRPeer.h"

/* The writer never emits more: BRPeerManager's _peerRelayedPeers trims its pool
 * to 2500 and only calls savePeers with fewer than 1000. A larger count is not
 * a blob this wallet wrote. */
#define SAVED_PEERS_MAX_COUNT 2500
#define SAVED_PEERS_RECORD_BYTES (16 + 2 + 8 + 8)

/* Parses a saved-peers blob. On a sane count, allocates *outPeers (caller owns
 * it and frees it with free()) and returns the number of records actually
 * present (<= count; a truncated blob yields the whole records it holds). On a
 * corrupt count (0 or > SAVED_PEERS_MAX_COUNT) or a failed allocation, sets
 * *outPeers = NULL and returns 0: the blob is treated as empty. */
static inline size_t deserialize_saved_peers_guarded(const uint8_t *b, size_t len,
                                                      BRPeer **outPeers) {
    *outPeers = NULL;
    if (!b || len < 4) return 0;

    size_t pos = 0;
    uint32_t count = UInt32GetLE(&b[pos]); pos += 4;

#ifndef BB_2026_10_09_HARLEY_UNFIXED
    if (count == 0 || count > SAVED_PEERS_MAX_COUNT) return 0;
#endif

    BRPeer *peers = calloc(count, sizeof(BRPeer));
#ifndef BB_2026_10_09_HARLEY_UNFIXED
    if (!peers) return 0;
#endif

    size_t loaded = 0;
    for (uint32_t i = 0; i < count && SAVED_PEERS_RECORD_BYTES <= len - pos; i++) {
        memcpy(&peers[i].address, &b[pos], 16); pos += 16;
        peers[i].port = UInt16GetLE(&b[pos]); pos += 2;
        peers[i].timestamp = (uint32_t)UInt64GetLE(&b[pos]); pos += 8;
        peers[i].services = UInt64GetLE(&b[pos]); pos += 8;
        loaded++;
    }

    *outPeers = peers;
    return loaded;
}

#endif /* SAVED_PEERS_DESERIALIZE_H */
