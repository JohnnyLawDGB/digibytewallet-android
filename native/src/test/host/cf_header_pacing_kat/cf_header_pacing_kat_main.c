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

static uint8_t *makeHeadersMessage(size_t *messageLength)
{
    static const uint8_t bitcoinGenesisHeader[80] = {
        0x01,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x3b,0xa3,0xed,0xfd,0x7a,0x7b,0x12,0xb2,0x7a,0xc7,0x2c,0x3e,
        0x67,0x76,0x8f,0x61,0x7f,0xc8,0x1b,0xc3,0x88,0x8a,0x51,0x32,
        0x3a,0x9f,0xb8,0xaa,0x4b,0x1e,0x5e,0x4a,
        0x29,0xab,0x5f,0x49,0xff,0xff,0x00,0x1d,0x1d,0xac,0x2b,0x7c
    };

    size_t prefixLength = BRVarIntSize(MAX_HEADERS_RESULTS);
    *messageLength = prefixLength + (size_t)MAX_HEADERS_RESULTS * 81u;
    uint8_t *message = calloc(*messageLength, 1);
    if (! message) return NULL;

    size_t offset = BRVarIntSet(message, *messageLength, MAX_HEADERS_RESULTS);
    for (size_t i = 0; i < MAX_HEADERS_RESULTS; i++) {
        memcpy(&message[offset + i * 81u], bitcoinGenesisHeader, sizeof(bitcoinGenesisHeader));
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

int main(void)
{
    size_t messageLength = 0;
    uint8_t *message = makeHeadersMessage(&messageLength);
    check(MAX_HEADERS_RESULTS == 20000u, "the wallet models DigiByte's observed 20,000-header wire maximum");
    check(message != NULL, "full headers fixture allocated");
    if (! message) return 1;

    runCase(message, messageLength, 1, 0,
            "a gate closed by relayedBlock suppresses the continuation (decision is AFTER relay)");
    runCase(message, messageLength, 0, 1,
            "an open gate still sends exactly one continuation after the response is relayed");

    free(message);
    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
