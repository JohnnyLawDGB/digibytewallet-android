/*
 * slip13_identity_kat — known-answer tests for the Digi-ID per-site identity
 * derivation (bridge/slip13.c + BRBIP32PrivKeyPath).
 *
 * 1. SLIP-0013 / BitID BIP-draft test vector: index derivation for
 *    "http://bitid.bitcoin.blue/callback", i=0 must yield the four hardened
 *    child indexes the spec publishes.
 * 2. Determinism: same (uri, index) twice -> identical indexes.
 * 3. URI separation: different uri -> different indexes.
 * 4. Index separation: different index -> different indexes.
 * 5. End-to-end path derivation with a fixed test seed: the m/13'/A'/B'/C'/D'
 *    key exists, is deterministic, and two different URIs produce two
 *    different P2PKH addresses (the whole point of the feature).
 * 6. Failure hygiene: NULL args refused with zeroed output.
 */
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "slip13.h"
#include "BRBIP32Sequence.h"
#include "BRKey.h"

static int failures = 0;

#define CHECK(cond, name) do { \
    if (cond) { printf("  ok: %s\n", (name)); } \
    else { printf("  FAIL: %s\n", (name)); failures++; } \
} while (0)

int main(void) {
    const char *vectorUri = "http://bitid.bitcoin.blue/callback";

    /* 1 — published SLIP-0013/BitID vector */
    uint32_t idx[4];
    CHECK(slip13_indexes(idx, vectorUri, strlen(vectorUri), 0) == 1, "vector derivation succeeds");
    CHECK(idx[0] == 0xbe553112u, "vector A == 0xbe553112");
    CHECK(idx[1] == 0xc0af82cfu, "vector B == 0xc0af82cf");
    CHECK(idx[2] == 0x4361fb3bu, "vector C == 0x4361fb3b");
    CHECK(idx[3] == 0xedd2bf37u, "vector D == 0xedd2bf37");

    /* 2 — determinism */
    uint32_t idx2[4];
    slip13_indexes(idx2, vectorUri, strlen(vectorUri), 0);
    CHECK(memcmp(idx, idx2, sizeof(idx)) == 0, "same uri+index -> same indexes");

    /* 3 — URI separation */
    const char *otherUri = "https://example.com/digiid";
    uint32_t idxOther[4];
    slip13_indexes(idxOther, otherUri, strlen(otherUri), 0);
    CHECK(memcmp(idx, idxOther, sizeof(idx)) != 0, "different uri -> different indexes");

    /* 4 — index separation */
    uint32_t idxAcct1[4];
    slip13_indexes(idxAcct1, vectorUri, strlen(vectorUri), 1);
    CHECK(memcmp(idx, idxAcct1, sizeof(idx)) != 0, "different account index -> different indexes");

    /* 5 — end-to-end m/13'/A'/B'/C'/D' with a fixed seed */
    uint8_t seed[64];
    for (int i = 0; i < 64; i++) seed[i] = (uint8_t)(i + 1);

    BRKey keyA, keyA2, keyB;
    memset(&keyA, 0, sizeof(keyA)); memset(&keyA2, 0, sizeof(keyA2)); memset(&keyB, 0, sizeof(keyB));

    BRBIP32PrivKeyPath(&keyA, seed, sizeof(seed), 5,
        13 | BIP32_HARD, idx[0] | BIP32_HARD, idx[1] | BIP32_HARD, idx[2] | BIP32_HARD, idx[3] | BIP32_HARD);
    BRBIP32PrivKeyPath(&keyA2, seed, sizeof(seed), 5,
        13 | BIP32_HARD, idx[0] | BIP32_HARD, idx[1] | BIP32_HARD, idx[2] | BIP32_HARD, idx[3] | BIP32_HARD);
    BRBIP32PrivKeyPath(&keyB, seed, sizeof(seed), 5,
        13 | BIP32_HARD, idxOther[0] | BIP32_HARD, idxOther[1] | BIP32_HARD, idxOther[2] | BIP32_HARD, idxOther[3] | BIP32_HARD);

    keyA.compressed = 1; keyA2.compressed = 1; keyB.compressed = 1;

    char addrA[75] = {0}, addrA2[75] = {0}, addrB[75] = {0};
    BRKeyAddress(&keyA, addrA, sizeof(addrA));
    BRKeyAddress(&keyA2, addrA2, sizeof(addrA2));
    BRKeyAddress(&keyB, addrB, sizeof(addrB));

    CHECK(addrA[0] != '\0', "path key yields an address");
    CHECK(strcmp(addrA, addrA2) == 0, "path derivation is deterministic");
    CHECK(strcmp(addrA, addrB) != 0, "different site -> different identity address");

    BRKeyClean(&keyA); BRKeyClean(&keyA2); BRKeyClean(&keyB);

    /* 6 — failure hygiene */
    uint32_t z[4] = {1, 2, 3, 4};
    CHECK(slip13_indexes(z, NULL, 0, 0) == 0, "NULL uri refused");
    CHECK(z[0] == 0 && z[1] == 0 && z[2] == 0 && z[3] == 0, "output zeroed on failure");
    CHECK(slip13_indexes(NULL, vectorUri, strlen(vectorUri), 0) == 0, "NULL out refused");

    printf("\nslip13_identity_kat: %s (%d failure%s)\n",
           failures == 0 ? "PASS" : "FAIL", failures, failures == 1 ? "" : "s");
    return failures == 0 ? 0 : 1;
}
