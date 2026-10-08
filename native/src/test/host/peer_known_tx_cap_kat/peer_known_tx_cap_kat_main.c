// Host KAT: a peer's knownTxHashes dedup cache is bounded and still dedups recent hashes.
//
// knownTxHashes is appended from the inv path and the mempool and was never trimmed, so a
// peer streaming novel hashes drove unbounded memory growth. The shipped code caps the
// array and evicts the oldest third on overflow, rebuilding knownTxHashSet from the
// surviving interior pointers (the same rebuild the realloc branch performs). The
// comparison arm (-DPEER_KNOWN_TX_CAP_UNFIXED) leaves it uncapped.
//
// Checks (shipped arm): after feeding far more than the cap of DISTINCT hashes the array
// stays at or below the cap; the most recently fed hash is still known (recent dedup
// works) and re-feeding it does not grow the array; the very first (oldest) hash has been
// evicted. The comparison arm grows to exactly the number fed.

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "BRPeer.c"   // reach the file-static knownTxHashes cache and its helpers

static UInt256 hashN(uint32_t i) {
    UInt256 h; memset(h.u8, 0, sizeof(h));
    h.u32[0] = i + 1;            // distinct, non-zero
    h.u32[7] = 0xA5A5A5A5u ^ i;  // spread the bytes so the set hash is well-distributed
    return h;
}

static int check(int cond, const char *msg) {
    printf("  [%s] %s\n", cond ? "PASS" : "FAIL", msg);
    return cond ? 0 : 1;
}

int main(void) {
    const uint32_t FED = 15000;   // >> any sane cap
    BRPeer *peer = BRPeerNew(0x12345678);
    BRPeerContext *ctx = (BRPeerContext *)peer;
    int fail = 0;

    for (uint32_t i = 0; i < FED; i++) {
        UInt256 h = hashN(i);
        _BRPeerAddKnownTxHashes(peer, &h, 1);
    }
    size_t count = array_count(ctx->knownTxHashes);

#ifdef PEER_KNOWN_TX_CAP_UNFIXED
    printf("comparison arm: uncapped\n");
    fail |= check(count == FED, "uncapped cache grows to exactly the number of distinct hashes fed");
    printf("RESULT count %zu\n", count);
#else
    printf("shipped arm: capped at %d\n", PEER_KNOWN_TX_HASHES_MAX);
    fail |= check(count <= (size_t)PEER_KNOWN_TX_HASHES_MAX, "cache stays at or below the cap after feeding 15000 distinct hashes");

    UInt256 recent = hashN(FED - 1);
    fail |= check(BRSetContains(ctx->knownTxHashSet, &recent) != 0, "the most recently fed hash is still known (recent dedup works)");
    size_t before = array_count(ctx->knownTxHashes);
    _BRPeerAddKnownTxHashes(peer, &recent, 1);
    fail |= check(array_count(ctx->knownTxHashes) == before, "re-feeding a known recent hash does not grow the cache (dedup)");

    UInt256 oldest = hashN(0);
    fail |= check(BRSetContains(ctx->knownTxHashSet, &oldest) == 0, "the oldest hash has been evicted");
    printf("RESULT count %zu\n", count);
#endif

    BRPeerFree(peer);
    printf("%s\n", fail ? "FAIL: peer_known_tx_cap_kat" : "PASS: peer_known_tx_cap_kat");
    return fail ? 1 : 0;
}
