#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define BRPEER_HEADERS_KAT 1
#include "BRPeer.c"

static int failures = 0;

static void check(int condition, const char *description)
{
    printf("%s: %s\n", condition ? "PASS" : "FAIL", description);
    if (! condition) failures++;
}

typedef struct {
    BRPeer *peer;
    size_t relayed;
    int closeGateAfterFirst;
} HeaderRelayState;

static void relayedBlock(void *info, BRMerkleBlock *block)
{
    HeaderRelayState *state = info;
    state->relayed++;
    if (state->closeGateAfterFirst && state->relayed == 1) {
        BRPeerSetConvoyHdrGated(state->peer, 1);
    }
    BRMerkleBlockFree(block);
}

// One real DigiByte mainnet header (height 1,430,000, sha256d, from merkleblock_pow_kat/headers.inc: the
// node's hash, dSHA256 == block hash) repeated to the 20,000-header wire maximum. It carries real proof of
// work, so the batch is accepted at every DGB_HEADER_POW_CHECK level and the pacing cases below do not
// depend on the level. The stub relayedBlock does not link headers, so repetition is fine.
static const char kRealSha256dHeader[] =
    "02020000a77347c6a54882aacc5e31960e427ae540239ede3ec216978b530476a8694b6bc1c7022b884b845de803e9c1349a871f"
    "8d78e79d2ec759d40ad321457d2a8b05b09661564e7f091ab47446b2";

// The earlier fixture: a version-1 header (scrypt under DigiByte's version mask) whose scrypt hash does not
// meet its target, i.e. a header with no proof of work for the algorithm it names.
static const uint8_t kNoWorkHeader[80] = {
    0x01,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x3b,0xa3,0xed,0xfd,0x7a,0x7b,0x12,0xb2,0x7a,0xc7,0x2c,0x3e,
    0x67,0x76,0x8f,0x61,0x7f,0xc8,0x1b,0xc3,0x88,0x8a,0x51,0x32,
    0x3a,0x9f,0xb8,0xaa,0x4b,0x1e,0x5e,0x4a,
    0x29,0xab,0x5f,0x49,0xff,0xff,0x00,0x1d,0x1d,0xac,0x2b,0x7c
};

static uint8_t *makeHeadersMessage(const uint8_t header[80], size_t *messageLength)
{
    size_t prefixLength = BRVarIntSize(MAX_HEADERS_RESULTS);
    *messageLength = prefixLength + (size_t)MAX_HEADERS_RESULTS * 81u;
    uint8_t *message = calloc(*messageLength, 1);
    if (! message) return NULL;

    size_t offset = BRVarIntSet(message, *messageLength, MAX_HEADERS_RESULTS);
    for (size_t i = 0; i < MAX_HEADERS_RESULTS; i++) {
        memcpy(&message[offset + i * 81u], header, 80);
    }
    return message;
}

static void runCase(const uint8_t *message, size_t messageLength, int closeGateAfterFirst,
                    size_t expectedContinuationCount, const char *description)
{
    BRPeer *peer = BRPeerNew(0x12345678u);
    HeaderRelayState state = { peer, 0, closeGateAfterFirst };
    BRPeerSetCompactFiltersOnly(peer, 1);
    BRPeerSetConvoyHdrGated(peer, 0);
    BRPeerSetCallbacks(peer, &state, NULL, NULL, NULL, NULL, NULL, NULL,
                       relayedBlock, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

    int accepted = _BRPeerAcceptHeadersMessage(peer, message, messageLength);
    BRPeerContext *context = (BRPeerContext *)peer;

    check(accepted == 1, "the real parser accepts a full 20,000-header DigiByte response");
    check(state.relayed == MAX_HEADERS_RESULTS, "all 20,000 headers are relayed before the decision completes");
    check(context->katGetheadersCount == expectedContinuationCount, description);
    BRPeerFree(peer);
}

// A full batch whose FIRST header has no proof of work: at level >= 2 the parser refuses the message
// (verdict 0, which ends the connection), relays nothing and sends no continuation; below level 2 it is
// accepted as before (level 1 only counts, level 0 does not compute the hash).
static void runNoWorkCase(void)
{
    uint32_t before = BRMerkleBlockPoWMismatchCount();
    size_t len = 0;
    uint8_t *message = makeHeadersMessage(kNoWorkHeader, &len);
    BRPeer *peer = BRPeerNew(0x12345678u);
    HeaderRelayState state = { peer, 0, 0 };
    BRPeerSetCompactFiltersOnly(peer, 1);
    BRPeerSetConvoyHdrGated(peer, 0);
    BRPeerSetCallbacks(peer, &state, NULL, NULL, NULL, NULL, NULL, NULL,
                       relayedBlock, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
    int accepted = _BRPeerAcceptHeadersMessage(peer, message, len);
    BRPeerContext *context = (BRPeerContext *)peer;
    uint32_t mismatches = BRMerkleBlockPoWMismatchCount() - before;
#if DGB_HEADER_POW_CHECK >= 2
    check(accepted == 0 && state.relayed == 0 && context->katGetheadersCount == 0,
          "level 2: a batch starting with a no-work header is refused, nothing relayed, no continuation");
    check(mismatches == 1, "level 2: exactly one pow-mismatch counted (the loop stops at the first)");
#elif DGB_HEADER_POW_CHECK == 1
    check(accepted == 1 && state.relayed == MAX_HEADERS_RESULTS, "level 1: the no-work batch is still accepted (counted, not refused)");
    check(mismatches == MAX_HEADERS_RESULTS, "level 1: every no-work header is counted");
#else
    check(accepted == 1 && state.relayed == MAX_HEADERS_RESULTS && mismatches == 0,
          "level 0: the no-work batch is accepted and nothing is counted (comparison arm)");
#endif
    BRPeerFree(peer);
    free(message);
}

int main(void)
{
    uint8_t realHeader[80];
    for (size_t i = 0; i < 80; i++) sscanf(&kRealSha256dHeader[2*i], "%2hhx", &realHeader[i]);
    size_t messageLength = 0;
    uint8_t *message = makeHeadersMessage(realHeader, &messageLength);
    check(MAX_HEADERS_RESULTS == 20000u, "the wallet models DigiByte's observed 20,000-header wire maximum");
    check(message != NULL, "full headers fixture allocated");
    if (! message) return 1;
    printf("level %d\n", DGB_HEADER_POW_CHECK);

    uint32_t before = BRMerkleBlockPoWMismatchCount();
    runCase(message, messageLength, 1, 0,
            "a gate closed by relayedBlock suppresses the continuation (decision is AFTER relay)");
    runCase(message, messageLength, 0, 1,
            "an open gate still sends exactly one continuation after the response is relayed");
    check(BRMerkleBlockPoWMismatchCount() == before, "the real-work fixture raises no pow-mismatch");
    free(message);

    runNoWorkCase();

    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
