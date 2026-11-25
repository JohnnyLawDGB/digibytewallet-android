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

/* Puts one priority peer at the front of a saved-peers array (the bridge's g_savedPeers), the
 * pure half of jni_peer.c's _prependSavedPeerAddr so a host KAT can drive it.
 *
 * A peer already in the array (same address) is not added again: its timestamp is set to `now`
 * and `services` is OR-ed in, so a later injection never takes a capability bit away.
 * A new peer goes to index 0. The array never holds more than SAVED_PEERS_MAX_COUNT: at the
 * limit the LAST entry is dropped to make room. Until 2026-11 the array grew by one for every
 * new address with no limit, each time scanned and copied whole under the peer lock, so a list
 * of n new addresses cost about n*n/2 record copies (SEEDER-PEER-CAP).
 *
 * *arr is owned by the caller and may be reallocated; on a failed allocation nothing changes.
 * Returns 1 if a new entry was added, 0 if it was already there or could not be added. */
static inline int saved_peers_prepend_bounded(BRPeer **arr, size_t *count, UInt128 addr, uint16_t port,
                                              uint64_t services, uint64_t now) {
    size_t i, keep, n = *count;
    BRPeer *fresh;

    for (i = 0; i < n; i++) {
        if (UInt128Eq((*arr)[i].address, addr)) {
            (*arr)[i].timestamp = now;
            (*arr)[i].services |= services;
            return 0;
        }
    }
#ifdef SEEDER_CAP_UNFIXED
    keep = n;                                   /* RED ARM SEAM (seeder_peers_kat): grows without limit */
#else
    keep = (n < SAVED_PEERS_MAX_COUNT) ? n : SAVED_PEERS_MAX_COUNT - 1;
#endif
    fresh = malloc((keep + 1) * sizeof(BRPeer));
    if (!fresh) return 0;
    fresh[0] = (BRPeer){ addr, port, services, now, 0 };
    if (*arr && keep > 0) memcpy(fresh + 1, *arr, keep * sizeof(BRPeer));
    free(*arr);
    *arr = fresh;
    *count = keep + 1;
    return 1;
}

#endif /* SAVED_PEERS_DESERIALIZE_H */
