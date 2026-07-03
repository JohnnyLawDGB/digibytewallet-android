// Host KAT for BIP350 (bech32m) version-awareness in BRBech32.c.
//
// Task 2 of the "Taproot Crypto Foundation" plan (.superpowers/sdd/task-2-brief.md).
// BRBech32.c (in the digibytewallet-core submodule) hardcoded the bech32
// checksum constant to `1` for every witness version. BIP173 (bech32) uses
// `1` for witness v0; BIP350 (bech32m) requires `0x2bc830a3` for witness
// v1+ (Taproot). This harness pins known-answer vectors computed against a
// Python bech32m reference and links them against the LIVE submodule
// source, so it catches any future regression on either constant.
//
// Reference vectors computed via (bip_utils implements BIP173 v0 / BIP350 v1+
// dispatch by witness version, matching the reference `segwit_addr.py` in the
// BIPs repo):
//
//   python3 -c "
//   from bip_utils import SegwitBech32Encoder
//   print(SegwitBech32Encoder.Encode('dgb', 1, bytes(range(32))))
//   print(SegwitBech32Encoder.Encode('dgb', 0, bytes(range(20))))
//   "
//   -> dgb1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0s470eva   (v1 / bech32m)
//   -> dgb1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzhtfd6                       (v0 / bech32, regression)
//
// Run: see run.sh in this directory (compiles this file + the real
// BRBech32.c against the shim headers in shim_headers/, then runs it).
// Exit code 0 = all vectors pass, 1 = at least one failure.

#include "BRBech32.h"
#include "BRAddress.h" // OP_0 / OP_1
#include <stdio.h>
#include <string.h>

static int g_failures = 0;

static void check(int cond, const char *what) {
    if (!cond) {
        printf("FAIL: %s\n", what);
        g_failures++;
    } else {
        printf("PASS: %s\n", what);
    }
}

static void hex(const uint8_t *p, size_t n, char *out) {
    static const char *hexd = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) { out[2*i] = hexd[p[i] >> 4]; out[2*i+1] = hexd[p[i] & 0xf]; }
    out[2*n] = '\0';
}

int main(void) {
    // ---- v1 (witness v1 / Taproot, bech32m) ----
    uint8_t prog1[32];
    for (int i = 0; i < 32; i++) prog1[i] = (uint8_t)i;
    uint8_t data1[2 + 32];
    data1[0] = OP_1;
    data1[1] = 32;
    memcpy(&data1[2], prog1, 32);

    const char *expected_v1 = "dgb1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0s470eva";

    char addr91[91];
    size_t n = BRBech32Encode(addr91, "dgb", data1);
    check(n > 0 && strcmp(addr91, expected_v1) == 0, "v1 encode matches pinned bech32m reference string");

    char hrp84[84];
    uint8_t data42[42];
    size_t dn = BRBech32Decode(hrp84, data42, expected_v1);
    char progHex[65], expHex[65];
    hex(&data42[2], (dn >= 2) ? dn - 2 : 0, progHex);
    hex(prog1, 32, expHex);
    check(dn == 2 + 32 && strcmp(hrp84, "dgb") == 0 && data42[0] == OP_1 && data42[1] == 32 &&
          strcmp(progHex, expHex) == 0,
          "v1 decode of pinned reference string round-trips to original 32-byte program");

    // ---- v0 (witness v0 / P2WPKH, plain bech32 — regression, must be unchanged) ----
    uint8_t prog0[20];
    for (int i = 0; i < 20; i++) prog0[i] = (uint8_t)i;
    uint8_t data0[2 + 20];
    data0[0] = OP_0;
    data0[1] = 20;
    memcpy(&data0[2], prog0, 20);

    const char *expected_v0 = "dgb1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzhtfd6";

    char addr91_0[91];
    size_t n0 = BRBech32Encode(addr91_0, "dgb", data0);
    check(n0 > 0 && strcmp(addr91_0, expected_v0) == 0, "v0 encode matches pinned reference string (regression)");

    char hrp84_0[84];
    uint8_t data42_0[42];
    size_t dn0 = BRBech32Decode(hrp84_0, data42_0, expected_v0);
    char progHex0[41], expHex0[41];
    hex(&data42_0[2], (dn0 >= 2) ? dn0 - 2 : 0, progHex0);
    hex(prog0, 20, expHex0);
    check(dn0 == 2 + 20 && strcmp(hrp84_0, "dgb") == 0 && data42_0[0] == OP_0 && data42_0[1] == 20 &&
          strcmp(progHex0, expHex0) == 0,
          "v0 decode of pinned reference string round-trips to original 20-byte program (regression)");

    printf("\n%s (%d failure(s))\n", g_failures == 0 ? "ALL PASS" : "SOME FAILED", g_failures);
    return g_failures == 0 ? 0 : 1;
}
