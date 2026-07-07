// Host KAT for BRDigiDollarAddressEncode — the DigiDollar receive-address encoder
// (Option B: single C-core implementation of receive-address generation).
//
// The critical property is that a wrong receive address loses funds, so this KAT
// cross-checks our C encode against an INDEPENDENT, tested golden vector taken
// verbatim from tonymorony's Kotlin DdAddress implementation
// (digidollar/src/test/kotlin/io/digibyte/digidollar/DdAddressTest.kt): the
// 32-byte taproot output key `dcea…631b` must encode to the testnet address
// `TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC`. It also verifies the
// encode/decode round-trip against our own BRDigiDollarAddressDecode, and the
// mainnet "DD" prefix.
//
// Same real-file compile approach as the sibling DD KATs (see
// digidollar_decode_kat/run.sh): compiles the live submodule BRDigiDollar.c out
// of the tree plus its transitive dependency chain.
//
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "BRTransaction.h"
#include "BRDigiDollar.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) {
        printf("PASS: %s\n", desc);
    } else {
        printf("FAIL: %s\n", desc);
        g_failures++;
    }
}

static void hex_to_bytes(const char *hex, uint8_t *out, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        unsigned v;
        sscanf(hex + 2 * i, "%02x", &v);
        out[i] = (uint8_t)v;
    }
}

int main(void)
{
    // Golden vector — verbatim from tonymorony's DdAddressTest.kt (independent Kotlin impl).
    const char *goldenKeyHex = "dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b";
    const char *goldenAddr   = "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC";

    uint8_t key32[32];
    hex_to_bytes(goldenKeyHex, key32, 32);

    // 1. encode(goldenKey, testnet) == goldenAddr  (cross-check vs independent Kotlin impl)
    char addr[128] = { 0 };
    size_t n = BRDigiDollarAddressEncode(addr, sizeof(addr), key32, 1 /*testnet*/);
    check(n > 0, "encode returns nonzero length");
    check(strcmp(addr, goldenAddr) == 0, "testnet encode matches golden TD address");
    check(n == strlen(goldenAddr), "returned length excludes NUL");

    // 2. decode(goldenAddr) round-trips back to goldenKey (our own decoder agrees)
    uint8_t back[32];
    int dok = BRDigiDollarAddressDecode(back, goldenAddr, 1 /*testnet*/);
    check(dok == 1, "decode of golden address succeeds");
    check(memcmp(back, key32, 32) == 0, "decode(golden) == golden key");

    // 3. decode(encode(k)) == k for a couple of arbitrary keys (self round-trip)
    uint8_t k2[32];
    for (int i = 0; i < 32; i++) k2[i] = (uint8_t)(0x11 * (i + 1));
    char a2[128] = { 0 };
    BRDigiDollarAddressEncode(a2, sizeof(a2), k2, 1);
    uint8_t r2[32];
    check(BRDigiDollarAddressDecode(r2, a2, 1) == 1 && memcmp(r2, k2, 32) == 0,
          "testnet encode->decode round-trips arbitrary key");

    // 4. mainnet uses the "DD" prefix and a different string than testnet
    char am[128] = { 0 };
    BRDigiDollarAddressEncode(am, sizeof(am), key32, 0 /*mainnet*/);
    check(am[0] == 'D' && am[1] == 'D', "mainnet address starts with DD");
    check(strcmp(am, goldenAddr) != 0, "mainnet address differs from testnet");
    uint8_t rm[32];
    check(BRDigiDollarAddressDecode(rm, am, 0) == 1 && memcmp(rm, key32, 32) == 0,
          "mainnet encode->decode round-trips");

    // 5. cross-network decode must FAIL (testnet addr not valid as mainnet)
    uint8_t rx[32];
    check(BRDigiDollarAddressDecode(rx, goldenAddr, 0 /*mainnet*/) == 0,
          "testnet address rejected under mainnet version");

    // 6. too-small buffer fails cleanly
    char small[8] = { 0 };
    check(BRDigiDollarAddressEncode(small, sizeof(small), key32, 1) == 0,
          "undersized buffer returns 0");

    printf("\n%s (%d failure%s)\n", g_failures ? "FAILED" : "ALL PASSED",
           g_failures, g_failures == 1 ? "" : "s");
    return g_failures ? 1 : 0;
}
